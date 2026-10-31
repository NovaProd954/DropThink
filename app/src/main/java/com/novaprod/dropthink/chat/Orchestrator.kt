package com.novaprod.dropthink.chat

import com.novaprod.dropthink.data.*
import com.novaprod.dropthink.engine.GenResult
import com.novaprod.dropthink.engine.LlamaEngine
import com.novaprod.dropthink.engine.Reason
import com.novaprod.dropthink.mcp.McpCallResult
import com.novaprod.dropthink.mcp.McpManager
import com.novaprod.dropthink.mcp.McpStatus
import com.novaprod.dropthink.tools.*
import com.novaprod.dropthink.util.*
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

class ChatOrchestrator(
    private val engine: LlamaEngine,
    private val mcp: McpManager,
    private val log: ToolLog,
    private val messages: MutableStateFlow<List<ChatMessage>>,
    private val status: (String?) -> Unit,
) {
    @Volatile private var stopped = false
    private val prior = ArrayList<JSONObject?>()
    private val evidence = StringBuilder()

    private class Pass(val raw: String, val res: GenResult, val continuations: Int, val repetition: Boolean, val totalTokens: Int, val note: String)
    private class Outcome(val ok: Boolean, val text: String, val label: String? = null)

    fun stop() {
        stopped = true
        engine.cancel()
    }

    private fun upd(id: String, f: (ChatMessage) -> ChatMessage) = messages.update { l -> l.map { if (it.id == id) f(it) else it } }
    private fun add(m: ChatMessage) = messages.update { it + m }

    fun modelHistory(list: List<ChatMessage>): List<Pair<String, String>> = list.filter { !it.isError && !(it.role == "assistant" && it.kind == "text" && it.content.isBlank()) }
        .map { m ->
            when {
                m.kind == "toolresult" -> "user" to "<tool_response>\n${m.content}\n</tool_response>"
                else -> m.role to m.content
            }
        }

    /** One logical generation: automatic continuation on output-limit, overlap trimming, repetition stop. */
    private suspend fun generateFull(prompt: ByteArray, sampling: Sampling, s: AppSettings, startedInThink: Boolean, onRaw: (String) -> Unit): Pass {
        val raw = StringBuilder()
        var remaining = s.totalOutputBudget.coerceAtLeast(16)
        var continuations = 0
        var total = 0
        var repetition = false
        var last = GenResult(Reason.ERROR, 0, 0, 0.0, 0.0, null)
        var note = ""
        while (true) {
            val perPass = minOf(s.maxTokensPerPass.coerceAtLeast(16), remaining)
            val full = if (raw.isEmpty()) prompt else prompt + raw.toString().toByteArray(Charsets.UTF_8)
            val isContinuation = raw.isNotEmpty()
            val head = StringBuilder()
            var holding = isContinuation
            var lastCheck = raw.length
            val before = raw.toString()
            last = engine.generate(full, sampling, perPass) { piece ->
                if (holding) {
                    head.append(piece)
                    if (head.length >= 160) {
                        raw.append(trimOverlap(before, head.toString()))
                        holding = false
                        onRaw(raw.toString())
                    }
                } else {
                    raw.append(piece)
                    onRaw(raw.toString())
                }
                if (raw.length - lastCheck >= 40) {
                    lastCheck = raw.length
                    if (looksRepetitive(raw.toString())) { repetition = true }
                }
                !(stopped || repetition)
            }
            if (holding && head.isNotEmpty()) {
                raw.append(trimOverlap(before, head.toString()))
                onRaw(raw.toString())
            }
            total += last.genTokens
            remaining -= last.genTokens
            if (repetition) { note = "Stopped: repeating text detected."; break }
            if (last.reason == Reason.MAX && s.autoContinue && continuations < s.maxContinuations && remaining > 0 && !stopped) {
                continuations++
                status("Continuing… (part ${continuations + 1})")
                continue
            }
            if (last.reason == Reason.MAX) {
                note = if (remaining <= 0) "Stopped: total output budget (${s.totalOutputBudget} tokens) reached."
                else if (!s.autoContinue) "Stopped: per-pass output limit (${s.maxTokensPerPass} tokens) reached; auto-continue is off."
                else "Stopped: continuation limit (${s.maxContinuations}) reached."
            }
            break
        }
        status(null)
        return Pass(raw.toString(), last, continuations, repetition, total, note)
    }

    private fun footerFor(p: Pass, s: AppSettings): String {
        val r = p.res
        val tps = if (r.genMs > 0) p.totalTokens / (r.genMs / 1000.0) else 0.0
        val head = when {
            p.repetition -> p.note
            stopped || r.reason == Reason.CANCEL -> "Cancelled"
            r.reason == Reason.EOG -> "Completed"
            r.reason == Reason.MAX -> p.note
            r.reason == Reason.CTX -> "Stopped: context window full (${s.nCtx} tokens). ${r.error ?: ""}".trim()
            else -> "Error: ${r.error ?: "unknown"}"
        }
        val cont = if (p.continuations > 0) " · ${p.continuations} continuation(s)" else ""
        return "$head\n${p.totalTokens} tokens · ${String.format("%.1f", tps)} tok/s · prompt ${r.promptTokens} tok in ${r.promptMs.toLong()} ms$cont"
    }

    private fun publish(id: String, raw: String, started: Boolean, notice: String) {
        val sp = splitThinking(raw, started)
        upd(id) { it.copy(content = sp.answer, reasoning = sp.reasoning, notice = notice) }
    }

    private suspend fun execute(spec: ToolSpec, args: JSONObject, route: Route, note: String): Outcome {
        val t0 = System.currentTimeMillis()
        var label = spec.name
        val res: McpCallResult = try {
            withTimeout(75_000) {
                when (spec.serverId) {
                    Gateway.ID -> { val g = Gateway.run(spec.remoteName, args, mcp, prior); label = g.label; McpCallResult(g.ok, g.text) }
                    null -> LocalTools.run(spec.remoteName, args)
                    else -> mcp.call(spec.serverId!!, spec.remoteName, args)
                }
            }
        } catch (e: TimeoutCancellationException) {
            McpCallResult(false, "ERROR: tool timed out after 75 s")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            McpCallResult(false, "ERROR: ${e.message}")
        }
        val text = if (res.ok) res.text.ifBlank { "(empty result)" } else if (res.text.startsWith("ERROR")) res.text else "ERROR: ${res.text}"
        log.add(ToolLogEntry(System.currentTimeMillis(), route.label, label, args.toString().take(800), res.ok, System.currentTimeMillis() - t0, text.take(2000), note))
        return Outcome(res.ok, text, label)
    }

    private suspend fun prepareTools(route: Route, text: String, s: AppSettings): Pair<List<ToolSpec>, String> {
        val sid = route.serverId
        var problem = ""
        if (sid != null) {
            val cfg = mcp.config(sid)
            if (cfg == null || !cfg.enabled) problem = "${cfg?.name ?: sid} is disabled in the Tools tab"
            else {
                status("Connecting to ${cfg.name}…")
                val st = mcp.ensureConnected(sid)
                status(null)
                if (st.status != McpStatus.CONNECTED) problem = "${cfg.name}: ${st.error ?: "not connected"}"
            }
        }
        val all = ToolRegistry.build(mcp.states.value)
        if (sid != null && mcp.state(sid).tools.isEmpty()) return emptyList<ToolSpec>() to problem
        return Router.select(route, text, all, s.maxToolsExposed.coerceAtLeast(1)) to problem
    }

    suspend fun run(userText: String, s: AppSettings, persist: () -> Unit) {
        stopped = false
        prior.clear()
        evidence.setLength(0)
        val lm = engine.loaded
        if (lm == null) { status("Load a model in the Models tab first."); return }
        val userMsg = ChatMessage(newId(), "user", userText)
        add(userMsg)
        val route = if (s.toolsEnabled) Router.classify(userText) else Route.NONE
        var tools: List<ToolSpec> = emptyList()
        if (route != Route.NONE) {
            val (sel, problem) = prepareTools(route, userText, s)
            log.note(route.label, "route=${route.name} tools=${sel.joinToString { it.name }}${if (problem.isNotEmpty()) " problem=$problem" else ""}")
            tools = sel
            if (tools.isEmpty()) {
                add(
                    ChatMessage(
                        newId(), "assistant",
                        "This request needs ${route.label}, but the required tool is unavailable (${problem.ifEmpty { "no matching tool was advertised by the server" }}). I won't answer it from the model's memory. Check the Tools tab and try again.",
                        isError = true,
                    ),
                )
                persist()
                return
            }
        } else log.note(route.label, "route=NONE (answered locally, no tools exposed)")

        val xml = lm.info["template_tools_xml"] == "1"
        val sampling = if (tools.isNotEmpty()) s.presets.getOrElse(TOOL_PRESET_INDEX) { s.presets[0] }.sampling else s.presets[s.activePreset.coerceIn(0, s.presets.lastIndex)].sampling
        var system = s.systemPrompt
        if (tools.isNotEmpty()) system += "\n\n" + ToolPrompt.RULES + "\n\n" + ToolPrompt.build(tools, xml)
        if (tools.any { it.serverId == Gateway.ID }) route.serverId?.let { system += "\n\n" + Gateway.RULES + "\n\n" + Gateway.catalog(it, mcp) }

        val work = ArrayList(modelHistory(messages.value))
        val baseSize = work.size
        var active = tools
        val used = ArrayList<String>()
        val sources = LinkedHashSet<String>()
        var calls = 0
        var retries = 0
        var successes = 0
        var asstId = newId()
        add(ChatMessage(asstId, "assistant", ""))
        val maxSteps = s.maxToolCalls + s.toolRetries + 3

        for (step in 0 until maxSteps) {
            if (stopped) break
            val pr = engine.buildPrompt(system, work, s.thinking, s.nCtx, s.maxTokensPerPass, work.size - baseSize + 1)
            val notice = if (pr.droppedMessages > 0) "ℹ ${pr.droppedMessages} older message(s) were left out of the model's context for this reply (still stored in the chat)." else ""
            val pass = generateFull(pr.bytes, sampling, s, pr.startedInThink) { raw -> publish(asstId, raw, pr.startedInThink, notice) }
            publish(asstId, pass.raw, pr.startedInThink, notice)
            val answer = splitThinking(pass.raw, pr.startedInThink).answer
            val r = pass.res
            val failedPass = r.reason == Reason.ERROR || r.reason == Reason.CTX || r.reason == Reason.CANCEL || pass.repetition
            var parsed = if (active.isNotEmpty() && !failedPass) ToolCallParser.parse(answer) else emptyList()

            if (!failedPass && parsed.isEmpty() && route != Route.NONE && calls == 0 && active.isNotEmpty()) {
                if (retries < s.toolRetries) {
                    retries++
                    log.note(route.label, "model produced no tool call; correction retry $retries/${s.toolRetries}")
                    work.add("assistant" to answer.ifBlank { "(no answer)" })
                    work.add("user" to "Correction: this request requires tool data and cannot be answered from memory. Reply ONLY with a tool call in the required format, using one of: ${active.joinToString { it.name }}${route.serverId?.let { " (server id: $it)" } ?: ""}.")
                    upd(asstId) { it.copy(content = "", reasoning = "") }
                    continue
                }
                val spec = active.first()
                val forced = if (route == Route.MATH) Router.forceArgs(spec, userText, route) else null
                if (forced == null) {
                    upd(asstId) { it.copy(content = "The model did not call the required tool after ${s.toolRetries} correction attempt(s), so I won't answer from memory. Try again, rephrase, or use a larger model. The Tools tab log shows what happened.", isError = true, footer = "No tool result") }
                    persist(); return
                }
                log.note(route.label, "forced by router: ${spec.name}")
                parsed = listOf(ParsedCall(spec.name, forced, "<tool_call>forced</tool_call>", null))
            }

            if (parsed.isEmpty()) {
                val unsupported = if (route != Route.NONE && successes > 0 && !failedPass) Grounding.unsupported(answer, evidence.toString()) else emptyList()
                val groundNote = if (unsupported.isEmpty()) "" else "⚠ Not found in tool data: ${unsupported.take(6).joinToString(", ")}. Verify against the tool results above."
                upd(asstId) { m ->
                    val ok = used.count { it.startsWith("✓") }
                    val bad = used.count { it.startsWith("✗") }
                    val toolLine = if (used.isEmpty()) "" else "\nTools: ${used.joinToString(", ")}" + if (bad > 0 && ok == 0) "\n⚠ no tool call succeeded" else ""
                    m.copy(footer = footerFor(pass, s) + toolLine, isError = r.reason == Reason.ERROR, sources = sources.take(8), notice = listOf(m.notice, groundNote).filter { it.isNotBlank() }.joinToString("\n"))
                }
                persist(); return
            }

            val callRaw = parsed.joinToString("\n") { it.raw }
            upd(asstId) { it.copy(kind = "toolcall", content = callRaw.trim(), reasoning = "", footer = "", notice = "") }
            work.add("assistant" to callRaw)
            val responses = StringBuilder()
            var validationFailure = false
            for (call in parsed) {
                if (calls >= s.maxToolCalls) break
                if (stopped) break
                val spec = active.firstOrNull { it.name == call.name }
                val out: Outcome = when {
                    call.error != null -> Outcome(false, "ERROR: ${call.error}")
                    spec == null -> Outcome(false, "ERROR: unknown tool '${call.name}'. Available tools: ${active.joinToString { it.name }}")
                    else -> {
                        val args = ToolCallParser.coerce(call.args, spec.schema)
                        val bad = ToolCallParser.validate(args, spec.schema)
                        if (bad != null) Outcome(false, "ERROR: invalid arguments: $bad")
                        else {
                            status("Running ${spec.name}…")
                            val o = execute(spec, args, route, if (call.raw.contains("forced")) "forced by router" else "")
                            status(null)
                            o
                        }
                    }
                }
                if (!out.ok && (out.text.startsWith("ERROR: invalid arguments") || out.text.startsWith("ERROR: unknown tool") || call.error != null)) validationFailure = true
                calls++
                used.add((if (out.ok) "✓ " else "✗ ") + (out.label ?: spec?.name ?: call.name.ifEmpty { "?" }))
                if (out.ok) { successes++; sources.addAll(extractUrls(out.text)); evidence.append(out.text).append('\n') }
                var text = out.text
                val limit = s.maxToolResultChars.coerceAtLeast(500)
                if (text.length > limit) text = text.take(limit) + "\n[truncated: ${out.text.length - limit} more characters omitted to protect context]"
                val label = "[${spec?.name ?: call.name}] "
                add(ChatMessage(newId(), "tool", label + text, isError = !out.ok, kind = "toolresult"))
                responses.append("<tool_response>\n").append(label).append(text).append("\n</tool_response>\n")
            }
            if (stopped) break
            // results already stored as toolresult messages; model history gets one merged user turn
            work.add("user" to responses.toString().trim())
            if (route != Route.NONE && successes == 0 && calls >= 1) {
                val recoverable = validationFailure && retries < s.toolRetries && calls < s.maxToolCalls
                if (recoverable) {
                    retries++
                    log.note(route.label, "tool call rejected by validation; correction retry $retries/${s.toolRetries}")
                } else {
                    add(
                        ChatMessage(
                            newId(), "assistant",
                            "I could not retrieve the data: every tool call failed (${used.joinToString(", ")}). Nothing was fetched, so I won't produce an answer from memory. See the tool result above and the log in the Tools tab.",
                            isError = true, footer = "Tool failure",
                        ),
                    )
                    persist(); return
                }
            }
            if (calls >= s.maxToolCalls) {
                active = emptyList()
                system = s.systemPrompt + "\n\nAnswer the user now using only the tool results above. Cite their source URLs. Do not call tools."
            }
            asstId = newId()
            add(ChatMessage(asstId, "assistant", ""))
        }
        upd(asstId) { it.copy(footer = if (stopped) "Cancelled" else "Stopped: tool step limit reached", sources = sources.take(8)) }
        persist()
    }
}
