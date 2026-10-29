# DropThink

Free, offline-first Android app for running local GGUF models (llama.cpp) with MCP tool servers, tool routing, and long-response completion.

## Status — read this first

Written but **not yet built by CI and not yet run on a device**. Nothing below is claimed to work until a build and a device run prove it.

| Area | State |
|---|---|
| llama.cpp `v0.8.0` JNI runtime (CPU only) | written; JNI file compile-checked against the real `llama.h`; CMake configure checked on a host |
| GGUF import via file picker, linked in place (no copy) | written, untested on device |
| Streaming chat, cancel, stop-reason reporting | written, untested on device |
| Auto-continuation, overlap trimming, repetition stop, total output budget | written, untested on device |
| Sampling presets (general, low-randomness tool calling), runtime settings | written, untested on device |
| MCP client (Streamable HTTP, JSON/SSE), 3 preconfigured servers, tool discovery, connection test | written, **never run against the live servers** |
| Routing rules A-D, tool prompt, Qwen tool-call parsing, validation, bounded retries, router-forced fallback | written, untested |
| Local tools: calculator, device date/time | written; calculator logic unit-tested |
| Tool log, direct tool-test screen | written |
| Hugging Face download, local RAG, embeddings, other local tools, stop sequences, KV-cache options, GPU backend | **not implemented** |

Pure logic (calculator, thinking split, quant parsing, overlap trim, repetition check, URL extraction) was run against the real Kotlin compiler. Everything that touches Android, the network, or a model has not been run.

## Preconfigured MCP servers

| Name | Endpoint | Used for |
|---|---|---|
| Parallel Search | `https://search.parallel.ai/mcp` | Rule A: current web research |
| Firecrawl | `https://mcp.firecrawl.dev/v2/mcp` | Rule B: reading a specific URL |
| Open-Meteo Weather | `https://open-meteo.caseyjhand.com/mcp` | Rule C: weather and climate |

Tool lists and schemas are discovered from each server at connect time, never hardcoded. Rule D: requests that need no external data stay local and expose no tools. If a required tool is unavailable or every call fails, the app says so and does not answer from the model's memory.

## Build

JDK 17, Android SDK 35, NDK 27.2.12479018, CMake 3.22.1, Gradle 8.9.

```
bash scripts/fetch-llama.sh      # clones the llama.cpp tag in llama.cpp.version
gradle :app:assembleDebug        # app/build/outputs/apk/debug/
```

CI builds on every push, uploads the APK as an artifact, and refreshes the `debug-latest` pre-release on `main`. If a device lacks dot-product instructions, build with `-PllamaArmArch=armv8-a`.

## Privacy

The INTERNET permission is used only by the MCP client. Local inference, local tools and chat storage never touch the network. Optional server keys are encrypted with an Android Keystore key and can be deleted in the Tools tab.

## Known limitations

- Prompt reuse between turns is not implemented (each reply re-reads the prompt; Qwen3.5 is a hybrid model, where partial cache reuse is not generally possible).
- Tool-call format for Qwen3.5 is chosen by checking the model's own template for `<function=`; this detection is unverified on a real Qwen3.5 file.
- Older messages are dropped (with a visible notice) rather than summarized when the context fills up.
- Debug signing key is cached in CI; if the cache expires the next APK cannot update the installed one (uninstall first).
- No project license has been chosen yet. Third-party: llama.cpp (MIT), AndroidX/Compose/Kotlin/kotlinx.coroutines (Apache-2.0).
