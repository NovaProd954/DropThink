#include <jni.h>
#include <android/log.h>
#include <unistd.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <climits>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

namespace {

constexpr int R_EOG = 0, R_MAX = 1, R_CTX = 2, R_CANCEL = 3, R_ERROR = 4;

std::mutex g_mu;
std::string g_err;
std::string g_log_tail;
bool g_inited = false;

void set_err(const std::string & s) {
    std::lock_guard<std::mutex> l(g_mu);
    g_err = s;
}

std::string get_err() {
    std::lock_guard<std::mutex> l(g_mu);
    return g_err;
}

void clear_log_tail() {
    std::lock_guard<std::mutex> l(g_mu);
    g_log_tail.clear();
}

std::string log_tail(size_t max_chars) {
    std::lock_guard<std::mutex> l(g_mu);
    if (g_log_tail.size() <= max_chars) return g_log_tail;
    return g_log_tail.substr(g_log_tail.size() - max_chars);
}

void log_cb(enum ggml_log_level level, const char * text, void *) {
    if (!text) return;
    int prio = ANDROID_LOG_INFO;
    if (level == GGML_LOG_LEVEL_ERROR) prio = ANDROID_LOG_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN) prio = ANDROID_LOG_WARN;
    else if (level == GGML_LOG_LEVEL_DEBUG) prio = ANDROID_LOG_DEBUG;
    __android_log_write(prio, "llama.cpp", text);
    if (level == GGML_LOG_LEVEL_ERROR) {
        std::lock_guard<std::mutex> l(g_mu);
        g_log_tail += text;
        if (g_log_tail.size() > 4000) g_log_tail.erase(0, g_log_tail.size() - 4000);
    }
}

struct ModelHolder {
    llama_model * model = nullptr;
    FILE * fp = nullptr;
};

struct Session {
    ModelHolder * mh = nullptr;
    llama_context * ctx = nullptr;
    std::atomic<bool> cancel{false};
};

struct SamplerGuard {
    llama_sampler * p = nullptr;
    ~SamplerGuard() { if (p) llama_sampler_free(p); }
};

bool abort_cb(void * data) {
    return static_cast<Session *>(data)->cancel.load();
}

jbyteArray to_jbytes(JNIEnv * env, const std::string & s) {
    jbyteArray a = env->NewByteArray(static_cast<jsize>(s.size()));
    if (a && !s.empty()) {
        env->SetByteArrayRegion(a, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    }
    return a;
}

std::string from_jbytes(JNIEnv * env, jbyteArray a) {
    if (!a) return {};
    jsize n = env->GetArrayLength(a);
    std::string s(static_cast<size_t>(n), '\0');
    if (n > 0) env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte *>(&s[0]));
    return s;
}

std::string clean(const std::string & in) {
    std::string o = in;
    for (auto & c : o) if (c == '\t' || c == '\n' || c == '\r') c = ' ';
    return o;
}

size_t valid_utf8_prefix(const std::string & s) {
    size_t i = 0, n = s.size();
    while (i < n) {
        unsigned char c = static_cast<unsigned char>(s[i]);
        size_t len = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 1;
        if (i + len > n) break;
        i += len;
    }
    return i;
}

bool run_template(const char * tmpl, const std::vector<llama_chat_message> & msgs, size_t total, bool add_ass, std::string & out) {
    std::vector<char> buf(std::max<size_t>(1024, total * 2 + 256));
    int32_t n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), add_ass, buf.data(), static_cast<int32_t>(buf.size()));
    if (n > static_cast<int32_t>(buf.size())) {
        buf.resize(static_cast<size_t>(n));
        n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), add_ass, buf.data(), static_cast<int32_t>(buf.size()));
    }
    if (n < 0) return false;
    out.assign(buf.data(), static_cast<size_t>(n));
    return true;
}

bool apply_template(const llama_model * model, const std::vector<std::string> & roles, const std::vector<std::string> & contents,
                    bool add_ass, std::string & out, bool allow_fallback, bool * used_model_template) {
    std::vector<llama_chat_message> msgs(roles.size());
    size_t total = 0;
    for (size_t i = 0; i < roles.size(); i++) {
        msgs[i].role = roles[i].c_str();
        msgs[i].content = contents[i].c_str();
        total += contents[i].size() + roles[i].size() + 16;
    }
    const char * tmpl = llama_model_chat_template(model, nullptr);
    if (tmpl && run_template(tmpl, msgs, total, add_ass, out)) {
        if (used_model_template) *used_model_template = true;
        return true;
    }
    if (used_model_template) *used_model_template = false;
    return allow_fallback && run_template("chatml", msgs, total, add_ass, out);
}

std::string model_info(const llama_model * m) {
    std::string o;
    char buf[1024];
    auto kv = [&](const char * k, const std::string & v) { o += k; o += '\t'; o += clean(v); o += '\n'; };
    auto meta = [&](const char * key) -> std::string {
        int32_t n = llama_model_meta_val_str(m, key, buf, sizeof(buf));
        return n >= 0 ? std::string(buf) : std::string();
    };
    kv("arch", meta("general.architecture"));
    kv("name", meta("general.name"));
    buf[0] = 0;
    llama_model_desc(m, buf, sizeof(buf));
    kv("desc", buf);
    const char * ft = llama_ftype_name(llama_model_ftype(m));
    kv("ftype", ft ? ft : "");
    kv("size_bytes", std::to_string(llama_model_size(m)));
    kv("n_params", std::to_string(llama_model_n_params(m)));
    kv("n_ctx_train", std::to_string(llama_model_n_ctx_train(m)));
    kv("n_layer", std::to_string(llama_model_n_layer(m)));
    kv("n_embd", std::to_string(llama_model_n_embd(m)));
    kv("n_vocab", std::to_string(llama_vocab_n_tokens(llama_model_get_vocab(m))));
    kv("recurrent", llama_model_is_recurrent(m) ? "1" : "0");
    kv("hybrid", llama_model_is_hybrid(m) ? "1" : "0");
    const char * tmpl = llama_model_chat_template(m, nullptr);
    kv("has_template", tmpl ? "1" : "0");
    kv("template_has_think", (tmpl && std::strstr(tmpl, "<think>")) ? "1" : "0");
    kv("template_tools_xml", (tmpl && std::strstr(tmpl, "<function=")) ? "1" : "0");
    bool native_ok = false;
    if (tmpl) {
        std::vector<std::string> r{"user"}, c{"hi"};
        std::string tmp;
        bool used = false;
        apply_template(m, r, c, true, tmp, false, &used);
        native_ok = used;
    }
    kv("template_supported", native_ok ? "1" : "0");
    return o;
}

ModelHolder * open_model(int fd, const char * path, bool vocab_only, bool use_mmap, int n_gpu_layers) {
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = n_gpu_layers;
    mp.vocab_only = vocab_only;
    mp.load_mode = (use_mmap && !vocab_only && llama_supports_mmap()) ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;
    clear_log_tail();
    auto * h = new ModelHolder();
    if (fd >= 0) {
        int d = dup(fd);
        if (d < 0) {
            set_err("dup() of the file descriptor failed");
            delete h;
            return nullptr;
        }
        lseek(d, 0, SEEK_SET);
        h->fp = fdopen(d, "rb");
        if (!h->fp) {
            close(d);
            set_err("fdopen() failed");
            delete h;
            return nullptr;
        }
        h->model = llama_model_load_from_file_ptr(h->fp, mp);
    } else {
        h->model = llama_model_load_from_file(path, mp);
    }
    if (!h->model) {
        if (h->fp) fclose(h->fp);
        delete h;
        std::string tail = log_tail(700);
        set_err("llama.cpp could not load this file." + (tail.empty() ? std::string() : "\n" + tail));
        return nullptr;
    }
    return h;
}

void free_holder(ModelHolder * h) {
    if (!h) return;
    if (h->model) llama_model_free(h->model);
    if (h->fp) fclose(h->fp);
    delete h;
}

std::string piece_of(const llama_vocab * v, llama_token t) {
    char buf[256];
    int32_t n = llama_token_to_piece(v, t, buf, sizeof(buf), 0, false);
    if (n < 0) {
        std::string big(static_cast<size_t>(-n), '\0');
        n = llama_token_to_piece(v, t, &big[0], static_cast<int32_t>(big.size()), 0, false);
        if (n < 0) return {};
        big.resize(static_cast<size_t>(n));
        return big;
    }
    return std::string(buf, static_cast<size_t>(n));
}

bool tokenize(const llama_vocab * vocab, const std::string & text, std::vector<llama_token> & out) {
    int32_t r = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()), nullptr, 0, true, true);
    if (r == INT32_MIN) return false;
    int32_t n = r < 0 ? -r : r;
    out.assign(static_cast<size_t>(n), 0);
    if (n == 0) return true;
    int32_t got = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()), out.data(), n, true, true);
    if (got < 0) return false;
    out.resize(static_cast<size_t>(got));
    llama_token bos = llama_vocab_bos(vocab);
    if (out.size() >= 2 && bos != LLAMA_TOKEN_NULL && out[0] == bos && out[1] == bos) out.erase(out.begin());
    return true;
}

jdoubleArray make_result(JNIEnv * env, int reason, int n_prompt, int n_gen, double p_ms, double g_ms) {
    jdoubleArray a = env->NewDoubleArray(5);
    if (!a) return nullptr;
    jdouble v[5] = {static_cast<jdouble>(reason), static_cast<jdouble>(n_prompt), static_cast<jdouble>(n_gen), p_ms, g_ms};
    env->SetDoubleArrayRegion(a, 0, 5, v);
    return a;
}

double ms_since(std::chrono::steady_clock::time_point t) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t).count();
}

}  // namespace


extern "C" {

JNIEXPORT void JNICALL Java_com_novaprod_dropthink_nativebridge_LlamaBridge_nBackendInit(JNIEnv *, jobject) {
    if (g_inited) return;
    g_inited = true;
    llama_log_set(log_cb, nullptr);
    llama_backend_init();
}

JNIEXPORT jbyteArray JNICALL Java_com_novaprod_dropthink_nativebridge_LlamaBridge_nSystemInfo(JNIEnv * env, jobject) {
    std::string s = std::string("llama_version=") + llama_version() + "\n";
    s += std::string("gpu_offload_supported=") + (llama_supports_gpu_offload() ? "yes" : "no") + "\n";
    s += std::string("mmap_supported=") + (llama_supports_mmap() ? "yes" : "no") + "\n";
    s += std::string("mlock_supported=") + (llama_supports_mlock() ? "yes" : "no") + "\n";
    s += std::string("max_devices=") + std::to_string(llama_max_devices()) + "\n";
    s += llama_print_system_info();
    return to_jbytes(env, s);
}

JNIEXPORT jbyteArray JNICALL Java_com_novaprod_dropthink_nativebridge_LlamaBridge_nLastError(JNIEnv * env, jobject) {
    return to_jbytes(env, get_err());
}

JNIEXPORT jbyteArray JNICALL Java_com_novaprod_dropthink_nativebridge_LlamaBridge_nInspectFd(JNIEnv * env, jobject, jint fd) {
    try {
        ModelHolder * h = open_model(fd, nullptr, true, false, 0);
        if (!h) return nullptr;
        std::string info = model_info(h->model);
        free_holder(h);
        return to_jbytes(env, info);
    } catch (const std::exception & e) {
        set_err(std::string("inspect failed: ") + e.what());
        return nullptr;
    }
}

JNIEXPORT jlong JNICALL Java_com_novaprod_dropthink_nativebridge_LlamaBridge_nLoadFd(JNIEnv *, jobject, jint fd, jint n_gpu, jboolean use_mmap) {
    try {
        return reinterpret_cast<jlong>(open_model(fd, nullptr, false, use_mmap == JNI_TRUE, n_gpu));
    } catch (const std::exception & e) {
        set_err(std::string("load failed: ") + e.what());
        return 0;
    }
}

JNIEXPORT jlong JNICALL Java_com_novaprod_dropthink_nativebridge_LlamaBridge_nLoadPath(JNIEnv * env, jobject, jbyteArray path, jint n_gpu, jboolean use_mmap) {
    try {
        std::string p = from_jbytes(env, path);
        return reinterpret_cast<jlong>(open_model(-1, p.c_str(), false, use_mmap == JNI_TRUE, n_gpu));
    } catch (const std::exception & e) {
        set_err(std::string("load failed: ") + e.what());
        return 0;
    }
}

JNIEXPORT void JNICALL Java_com_novaprod_dropthink_nativebridge_LlamaBridge_nFreeModel(JNIEnv *, jobject, jlong h) {
    free_holder(reinterpret_cast<ModelHolder *>(h));
}

JNIEXPORT jbyteArray JNICALL Java_com_novaprod_dropthink_nativebridge_LlamaBridge_nModelInfo(JNIEnv * env, jobject, jlong h) {
    auto * mh = reinterpret_cast<ModelHolder *>(h);
    if (!mh || !mh->model) return nullptr;
    return to_jbytes(env, model_info(mh->model));
}

JNIEXPORT jbyteArray JNICALL Java_com_novaprod_dropthink_nativebridge_LlamaBridge_nApplyTemplate(
        JNIEnv * env, jobject, jlong h, jobjectArray roles, jobjectArray contents, jboolean add_ass) {
    auto * mh = reinterpret_cast<ModelHolder *>(h);
    if (!mh || !mh->model) return nullptr;
    try {
        jsize n = env->GetArrayLength(roles);
        std::vector<std::string> r, c;
        for (jsize i = 0; i < n; i++) {
            auto js = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
            const char * cs = env->GetStringUTFChars(js, nullptr);
            r.emplace_back(cs ? cs : "user");
            if (cs) env->ReleaseStringUTFChars(js, cs);
            env->DeleteLocalRef(js);
            auto jb = static_cast<jbyteArray>(env->GetObjectArrayElement(contents, i));
            c.push_back(from_jbytes(env, jb));
            env->DeleteLocalRef(jb);
        }
        std::string out;
        if (!apply_template(mh->model, r, c, add_ass == JNI_TRUE, out, true, nullptr)) {
            set_err("chat template could not be applied");
            return nullptr;
        }
        return to_jbytes(env, out);
    } catch (const std::exception & e) {
        set_err(std::string("template failed: ") + e.what());
        return nullptr;
    }
}

JNIEXPORT jint JNICALL Java_com_novaprod_dropthink_nativebridge_LlamaBridge_nCountTokens(JNIEnv * env, jobject, jlong h, jbyteArray text) {
    auto * mh = reinterpret_cast<ModelHolder *>(h);
    if (!mh || !mh->model) return -1;
    std::vector<llama_token> toks;
    if (!tokenize(llama_model_get_vocab(mh->model), from_jbytes(env, text), toks)) return -1;
    return static_cast<jint>(toks.size());
}

JNIEXPORT jlong JNICALL Java_com_novaprod_dropthink_nativebridge_LlamaBridge_nCreateContext(
        JNIEnv *, jobject, jlong h, jint n_ctx, jint n_batch, jint n_ubatch, jint n_threads, jint n_threads_batch) {
    auto * mh = reinterpret_cast<ModelHolder *>(h);
    if (!mh || !mh->model) {
        set_err("no model");
        return 0;
    }
    try {
        clear_log_tail();
        auto * s = new Session();
        s->mh = mh;
        llama_context_params cp = llama_context_default_params();
        cp.n_ctx = static_cast<uint32_t>(n_ctx);
        cp.n_batch = static_cast<uint32_t>(n_batch);
        cp.n_ubatch = static_cast<uint32_t>(std::min(n_ubatch, n_batch));
        cp.n_threads = n_threads;
        cp.n_threads_batch = n_threads_batch;
        cp.no_perf = true;
        cp.abort_callback = abort_cb;
        cp.abort_callback_data = s;
        s->ctx = llama_init_from_model(mh->model, cp);
        if (!s->ctx) {
            std::string tail = log_tail(700);
            set_err("Could not create the inference context (often not enough memory for this context length)." + (tail.empty() ? std::string() : "\n" + tail));
            delete s;
            return 0;
        }
        return reinterpret_cast<jlong>(s);
    } catch (const std::exception & e) {
        set_err(std::string("context creation failed: ") + e.what());
        return 0;
    }
}

JNIEXPORT void JNICALL Java_com_novaprod_dropthink_nativebridge_LlamaBridge_nFreeContext(JNIEnv *, jobject, jlong c) {
    auto * s = reinterpret_cast<Session *>(c);
    if (!s) return;
    if (s->ctx) llama_free(s->ctx);
    delete s;
}

JNIEXPORT jbyteArray JNICALL Java_com_novaprod_dropthink_nativebridge_LlamaBridge_nContextInfo(JNIEnv * env, jobject, jlong c) {
    auto * s = reinterpret_cast<Session *>(c);
    if (!s || !s->ctx) return nullptr;
    std::string o;
    auto kv = [&](const char * k, long long v) { o += k; o += '\t'; o += std::to_string(v); o += '\n'; };
    kv("n_ctx", llama_n_ctx(s->ctx));
    kv("n_batch", llama_n_batch(s->ctx));
    kv("n_ubatch", llama_n_ubatch(s->ctx));
    kv("n_threads", llama_n_threads(s->ctx));
    kv("n_threads_batch", llama_n_threads_batch(s->ctx));
    return to_jbytes(env, o);
}

JNIEXPORT void JNICALL Java_com_novaprod_dropthink_nativebridge_LlamaBridge_nCancel(JNIEnv *, jobject, jlong c) {
    auto * s = reinterpret_cast<Session *>(c);
    if (s) s->cancel.store(true);
}

JNIEXPORT jdoubleArray JNICALL Java_com_novaprod_dropthink_nativebridge_LlamaBridge_nGenerate(
        JNIEnv * env, jobject, jlong c, jbyteArray jprompt, jint max_tokens, jfloat temp, jint top_k, jfloat top_p, jfloat min_p,
        jfloat repeat, jfloat presence, jfloat freq, jint last_n, jint seed, jobject sink) {
    auto * s = reinterpret_cast<Session *>(c);
    if (!s || !s->ctx) {
        set_err("no context");
        return nullptr;
    }
    try {
        s->cancel.store(false);
        set_err("");
        const llama_vocab * vocab = llama_model_get_vocab(s->mh->model);
        jclass sink_cls = env->FindClass("com/novaprod/dropthink/nativebridge/TokenSink");
        jmethodID on_piece = sink_cls ? env->GetMethodID(sink_cls, "onPiece", "([B)Z") : nullptr;
        if (!on_piece) {
            env->ExceptionClear();
            set_err("TokenSink.onPiece not found");
            return make_result(env, R_ERROR, 0, 0, 0, 0);
        }

        std::vector<llama_token> toks;
        if (!tokenize(vocab, from_jbytes(env, jprompt), toks) || toks.empty()) {
            set_err("tokenization failed or empty prompt");
            return make_result(env, R_ERROR, 0, 0, 0, 0);
        }
        const int32_t n_ctx = static_cast<int32_t>(llama_n_ctx(s->ctx));
        const int32_t n_prompt = static_cast<int32_t>(toks.size());
        if (n_prompt >= n_ctx) {
            set_err("Prompt is " + std::to_string(n_prompt) + " tokens but the context holds only " + std::to_string(n_ctx));
            return make_result(env, R_CTX, n_prompt, 0, 0, 0);
        }
        if (max_tokens <= 0) max_tokens = INT32_MAX;

        SamplerGuard smpl;
        smpl.p = llama_sampler_chain_init(llama_sampler_chain_default_params());
        const int32_t n_vocab = llama_vocab_n_tokens(vocab);
        if (repeat <= 0.0f) repeat = 1.0f;
        if (last_n != 0 && (repeat != 1.0f || freq != 0.0f || presence != 0.0f)) {
            llama_sampler_chain_add(smpl.p, llama_sampler_init_penalties(n_vocab, last_n, repeat, freq, presence));
        }
        if (temp > 0.0f) {
            if (top_k > 0) llama_sampler_chain_add(smpl.p, llama_sampler_init_top_k(top_k));
            if (top_p < 1.0f) llama_sampler_chain_add(smpl.p, llama_sampler_init_top_p(top_p, 1));
            if (min_p > 0.0f) llama_sampler_chain_add(smpl.p, llama_sampler_init_min_p(min_p, 1));
            llama_sampler_chain_add(smpl.p, llama_sampler_init_temp(temp));
            llama_sampler_chain_add(smpl.p, llama_sampler_init_dist(seed < 0 ? LLAMA_DEFAULT_SEED : static_cast<uint32_t>(seed)));
        } else {
            llama_sampler_chain_add(smpl.p, llama_sampler_init_greedy());
        }

        llama_memory_clear(llama_get_memory(s->ctx), true);

        auto t_start = std::chrono::steady_clock::now();
        const int32_t nb = std::max<int32_t>(1, static_cast<int32_t>(llama_n_batch(s->ctx)));
        for (int32_t i = 0; i < n_prompt; i += nb) {
            int32_t n = std::min(nb, n_prompt - i);
            llama_batch b = llama_batch_get_one(toks.data() + i, n);
            int32_t r = llama_decode(s->ctx, b);
            if (r != 0) {
                int reason = r == 1 ? R_CTX : (r == 2 ? R_CANCEL : R_ERROR);
                if (reason == R_ERROR) {
                    std::string tail = log_tail(400);
                    set_err("llama_decode failed while reading the prompt (code " + std::to_string(r) + ")" + (tail.empty() ? std::string() : "\n" + tail));
                } else if (reason == R_CTX) {
                    set_err("No room in the context for this prompt");
                }
                return make_result(env, reason, n_prompt, 0, ms_since(t_start), 0);
            }
            if (s->cancel.load()) return make_result(env, R_CANCEL, n_prompt, 0, ms_since(t_start), 0);
        }
        const double prompt_ms = ms_since(t_start);

        auto emit = [&](const std::string & bytes) -> bool {
            if (bytes.empty()) return true;
            jbyteArray arr = to_jbytes(env, bytes);
            jboolean cont = env->CallBooleanMethod(sink, on_piece, arr);
            env->DeleteLocalRef(arr);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
                return false;
            }
            return cont == JNI_TRUE;
        };

        auto t_gen = std::chrono::steady_clock::now();
        int32_t n_gen = 0;
        int reason = R_MAX;
        std::string pending;
        while (true) {
            if (s->cancel.load()) { reason = R_CANCEL; break; }
            llama_token tok = llama_sampler_sample(smpl.p, s->ctx, -1);
            if (llama_vocab_is_eog(vocab, tok)) { reason = R_EOG; break; }
            pending += piece_of(vocab, tok);
            n_gen++;
            size_t k = valid_utf8_prefix(pending);
            if (k > 0) {
                std::string out = pending.substr(0, k);
                pending.erase(0, k);
                if (!emit(out)) { reason = R_CANCEL; break; }
            }
            if (n_gen >= max_tokens) { reason = R_MAX; break; }
            if (n_prompt + n_gen >= n_ctx) { reason = R_CTX; break; }
            llama_batch b = llama_batch_get_one(&tok, 1);
            int32_t r = llama_decode(s->ctx, b);
            if (r != 0) {
                reason = r == 1 ? R_CTX : (r == 2 ? R_CANCEL : R_ERROR);
                if (reason == R_ERROR) set_err("llama_decode failed during generation (code " + std::to_string(r) + ")");
                break;
            }
        }
        return make_result(env, reason, n_prompt, n_gen, prompt_ms, ms_since(t_gen));
    } catch (const std::exception & e) {
        set_err(std::string("generation failed: ") + e.what());
        return make_result(env, R_ERROR, 0, 0, 0, 0);
    }
}

}  // extern "C"
