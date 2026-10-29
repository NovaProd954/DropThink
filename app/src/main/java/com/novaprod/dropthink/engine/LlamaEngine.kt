package com.novaprod.dropthink.engine

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.novaprod.dropthink.data.AppSettings
import com.novaprod.dropthink.data.ModelEntry
import com.novaprod.dropthink.data.Sampling
import com.novaprod.dropthink.nativebridge.LlamaBridge
import com.novaprod.dropthink.nativebridge.TokenSink
import com.novaprod.dropthink.util.autoThreads
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

object Reason {
    const val EOG = 0
    const val MAX = 1
    const val CTX = 2
    const val CANCEL = 3
    const val ERROR = 4
}

data class LoadedModel(
    val entryId: String,
    val info: Map<String, String>,
    val ctxInfo: Map<String, String>,
    val loadMs: Long,
    val config: AppSettings,
)

class PromptResult(val bytes: ByteArray, val tokens: Int, val droppedMessages: Int, val startedInThink: Boolean)

data class GenResult(
    val reason: Int,
    val promptTokens: Int,
    val genTokens: Int,
    val promptMs: Double,
    val genMs: Double,
    val error: String?,
)

class LlamaEngine(private val appContext: Context) {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(null, r, "llama-native", 8L shl 20) }
    private val dispatcher = executor.asCoroutineDispatcher()

    @Volatile private var model = 0L
    @Volatile private var ctx = 0L
    @Volatile var loaded: LoadedModel? = null
        private set

    suspend fun inspect(uri: Uri): Result<Map<String, String>> = withContext(dispatcher) {
        runCatching {
            val pfd = appContext.contentResolver.openFileDescriptor(uri, "r") ?: error("Could not open the file")
            pfd.use { LlamaBridge.inspectFd(it.fd) ?: error(LlamaBridge.lastError().ifBlank { "Unsupported or unreadable GGUF" }) }
        }
    }

    suspend fun load(entry: ModelEntry, s: AppSettings): Result<LoadedModel> = withContext(dispatcher) {
        runCatching {
            unloadInternal()
            if (!LlamaBridge.isReady) error("Native library not available: ${LlamaBridge.loadError}")
            val t0 = SystemClock.elapsedRealtime()
            val uriStr = entry.uri
            val h = if (uriStr != null) {
                val pfd = appContext.contentResolver.openFileDescriptor(Uri.parse(uriStr), "r")
                    ?: error("Cannot open the file (permission lost or file moved). Import it again.")
                pfd.use { LlamaBridge.loadFd(it.fd, 0, s.useMmap) }
            } else LlamaBridge.loadPath(entry.path ?: error("Model has no location"), 0, s.useMmap)
            if (h == 0L) error(LlamaBridge.lastError().ifBlank { "Model failed to load" })
            val threads = if (s.threads > 0) s.threads else autoThreads()
            val nCtx = s.nCtx.coerceIn(256, 131072)
            val nBatch = s.nBatch.coerceIn(32, 4096)
            val c = LlamaBridge.createContext(h, nCtx, nBatch, s.nUbatch.coerceIn(32, nBatch), threads, threads)
            if (c == 0L) {
                LlamaBridge.freeModel(h)
                error(LlamaBridge.lastError().ifBlank { "Context creation failed (not enough memory for this context length?)" })
            }
            model = h
            ctx = c
            val lm = LoadedModel(entry.id, LlamaBridge.modelInfo(h), LlamaBridge.contextInfo(c), SystemClock.elapsedRealtime() - t0, s)
            loaded = lm
            lm
        }
    }

    suspend fun unload() = withContext(dispatcher) { unloadInternal() }

    private fun unloadInternal() {
        if (ctx != 0L) LlamaBridge.freeContext(ctx)
        if (model != 0L) LlamaBridge.freeModel(model)
        ctx = 0L
        model = 0L
        loaded = null
    }

    fun cancel() {
        val c = ctx
        if (c != 0L) LlamaBridge.cancel(c)
    }

    /** history: (role, content) pairs; the last [pinnedTail] messages are never dropped. */
    suspend fun buildPrompt(
        system: String,
        history: List<Pair<String, String>>,
        thinking: Boolean,
        nCtx: Int,
        maxOut: Int,
        pinnedTail: Int,
    ): PromptResult = withContext(dispatcher) {
        val m = model
        require(m != 0L) { "No model loaded" }
        val hasThink = loaded?.info?.get("template_has_think") == "1"
        val reserve = minOf(maxOut, nCtx / 2)
        val msgs = history.toMutableList()
        var dropped = 0
        while (true) {
            val roles = ArrayList<String>()
            val contents = ArrayList<ByteArray>()
            if (system.isNotBlank()) {
                roles.add("system"); contents.add(system.toByteArray(Charsets.UTF_8))
            }
            for (p in msgs) {
                roles.add(p.first); contents.add(p.second.toByteArray(Charsets.UTF_8))
            }
            var bytes = LlamaBridge.applyTemplate(m, roles.toTypedArray(), contents.toTypedArray(), true)
                ?: error("Chat template failed: ${LlamaBridge.lastError()}")
            val suffix = if (!hasThink) "" else if (thinking) "<think>\n" else "<think>\n\n</think>\n\n"
            bytes += suffix.toByteArray(Charsets.UTF_8)
            val n = LlamaBridge.countTokens(m, bytes)
            if (n + reserve <= nCtx) return@withContext PromptResult(bytes, n, dropped, hasThink && thinking)
            if (msgs.size <= pinnedTail.coerceAtLeast(1)) {
                error("This request needs about $n prompt tokens plus ${reserve} reserved for the answer, but the context is $nCtx. Raise the context length in Settings or shorten the request.")
            }
            msgs.removeAt(0)
            dropped++
            while (msgs.size > pinnedTail.coerceAtLeast(1) && msgs.first().first == "assistant") {
                msgs.removeAt(0); dropped++
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    suspend fun generate(
        prompt: ByteArray,
        s: Sampling,
        maxTokens: Int,
        onText: (String) -> Boolean,
    ): GenResult = withContext(dispatcher) {
        val c = ctx
        if (c == 0L) return@withContext GenResult(Reason.ERROR, 0, 0, 0.0, 0.0, "No model loaded")
        val sink = TokenSink { bytes -> onText(String(bytes, Charsets.UTF_8)) }
        val r = LlamaBridge.generate(
            c, prompt, maxTokens, s.temperature, s.topK, s.topP, s.minP, s.repeatPenalty,
            s.presencePenalty, s.frequencyPenalty, s.penaltyLastN, s.seed, sink,
        ) ?: return@withContext GenResult(Reason.ERROR, 0, 0, 0.0, 0.0, LlamaBridge.lastError().ifBlank { "generation failed" })
        val reason = r[0].toInt()
        val err = if (reason == Reason.ERROR || reason == Reason.CTX) LlamaBridge.lastError().ifBlank { null } else null
        GenResult(reason, r[1].toInt(), r[2].toInt(), r[3], r[4], err)
    }
}
