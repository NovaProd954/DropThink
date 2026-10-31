package com.novaprod.dropthink.tools

import com.novaprod.dropthink.mcp.McpManager
import com.novaprod.dropthink.mcp.McpStatus
import com.novaprod.dropthink.mcp.McpTool
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

class GwOut(val ok: Boolean, val text: String, val label: String)

object Gateway {
    const val ID = "app"
    const val LIST = "mcp_list_tools"
    const val CALL = "mcp_call_tool"

    const val RULES = "All external data comes through the app. Run a server tool with mcp_call_tool (server, tool, arguments); inspect tools with mcp_list_tools. " +
        "To pass a value from an earlier call into a later one, write @N.path as the value, where N is the call number shown in that result (example: @1.results.0.latitude). " +
        "Never state facts, numbers or news that did not come from tool results. If a call fails, read the error, fix the arguments and call again. After you have the data, answer from it only."

    val specs: List<ToolSpec> = listOf(
        ToolSpec(
            CALL, ID, CALL,
            "Run a tool on a connected MCP server through the app. Values from earlier calls can be reused as @N.path.",
            JSONObject("""{"type":"object","properties":{"server":{"type":"string","description":"server id"},"tool":{"type":"string","description":"tool name on that server"},"arguments":{"type":"object","description":"arguments for that tool"}},"required":["server","tool","arguments"]}"""),
        ),
        ToolSpec(
            LIST, ID, LIST,
            "List MCP servers and their tools, or show the full schema of one tool.",
            JSONObject("""{"type":"object","properties":{"server":{"type":"string","description":"server id (optional)"},"tool":{"type":"string","description":"tool name for its full schema (optional)"}}}"""),
        ),
    )

    private val REF = Regex("^@(\\d+)\\.(.+)$")

    private val QUERY_KEYS = setOf("objective", "query", "q", "search_query", "prompt", "question", "input", "text", "search")
    private val QUERIES_KEYS = setOf("search_queries", "queries", "keywords")
    private val URL_KEYS = setOf("url", "uri", "link")
    private val URLS_KEYS = setOf("urls", "uris", "links")

    fun fill(schema: JSONObject, query: String, url: String?): JSONObject {
        val props = schema.optJSONObject("properties") ?: return JSONObject()
        val reqArr = schema.optJSONArray("required")
        val required = if (reqArr == null) emptySet() else (0 until reqArr.length()).map { reqArr.getString(it) }.toSet()
        val o = JSONObject()
        for (k in props.keys().asSequence().toList()) {
            val p = props.optJSONObject(k) ?: continue
            val type = p.optString("type", "")
            val kl = k.lowercase()
            val value = url ?: query
            val en = p.optJSONArray("enum")
            when {
                kl in URL_KEYS && url != null && type == "string" -> o.put(k, url)
                kl in URLS_KEYS && url != null && type == "array" -> o.put(k, JSONArray().put(url))
                kl in QUERY_KEYS && type == "string" -> o.put(k, query)
                kl in QUERIES_KEYS && type == "array" -> o.put(k, JSONArray().put(query))
                kl == "formats" && type == "array" -> o.put(k, JSONArray().put("markdown"))
                kl == "onlymaincontent" && type == "boolean" -> o.put(k, true)
                k in required && en != null && en.length() > 0 -> o.put(k, en.get(0))
                k in required && type == "string" -> o.put(k, value)
                k in required && type == "array" -> o.put(k, JSONArray().put(value))
            }
        }
        return o
    }

    private fun score(spec: ToolSpec, url: String?): Int {
        val props = spec.schema.optJSONObject("properties") ?: return 0
        val keys = props.keys().asSequence().map { it.lowercase() }.toSet()
        val n = spec.remoteName.lowercase()
        var sc = 0
        if (url != null && keys.any { it in URL_KEYS || it in URLS_KEYS }) sc += 6
        if (url == null && keys.any { it in QUERY_KEYS || it in QUERIES_KEYS }) sc += 4
        if (url == null && "search" in n) sc += 2
        if (url != null && ("scrape" in n || "fetch" in n || "extract" in n)) sc += 2
        return sc
    }

    private fun best(selected: List<ToolSpec>, text: String): Pair<ToolSpec, JSONObject>? {
        val url = Router.firstUrl(text)
        var out: Pair<ToolSpec, JSONObject>? = null
        var bs = -1
        for (t in selected) {
            if (t.serverId == null || t.serverId == ID) continue
            val a = fill(t.schema, text.trim().take(500), url)
            if (ToolCallParser.validate(a, t.schema) != null) continue
            val sc = score(t, url)
            if (sc > bs) { out = t to a; bs = sc }
        }
        return out
    }

    fun rewrite(call: ParsedCall, pool: List<ToolSpec>, allowed: Set<String>?): ParsedCall? {
        if (call.error != null) return null
        val n = call.name.trim()
        val hit = pool.firstOrNull { it.serverId != null && it.serverId != ID && (it.remoteName == n || it.name == n) && (allowed == null || it.key in allowed) } ?: return null
        return ParsedCall(CALL, JSONObject().put("server", hit.serverId).put("tool", hit.remoteName).put("arguments", call.args), call.raw, null)
    }

    fun force(selected: List<ToolSpec>, text: String): ParsedCall? {
        val b = best(selected, text)
        if (b != null) return ParsedCall(CALL, JSONObject().put("server", b.first.serverId).put("tool", b.first.remoteName).put("arguments", b.second), "<tool_call>forced</tool_call>", null)
        for (t in selected) {
            if (t.serverId != null) continue
            val a = if (t.remoteName == "calculator") Router.forceArgs(t, text, Route.MATH) else JSONObject()
            if (a != null && ToolCallParser.validate(a, t.schema) == null) return ParsedCall(t.name, a, "<tool_call>forced</tool_call>", null)
        }
        return null
    }

    private fun render(name: String, args: JSONObject, xml: Boolean): String {
        if (!xml) return ("<tool_call>\n" + JSONObject().put("name", name).put("arguments", args).toString() + "\n</tool_call>").replace("\\/", "/")
        val sb = StringBuilder("<tool_call>\n<function=$name>\n")
        for (k in args.keys().asSequence().toList()) sb.append("<parameter=$k>\n").append(args.get(k).toString().replace("\\/", "/")).append("\n</parameter>\n")
        return sb.append("</function>\n</tool_call>").toString()
    }

    fun example(selected: List<ToolSpec>, text: String, xml: Boolean): String {
        val b = best(selected, text)
        if (b != null) return render(CALL, JSONObject().put("server", b.first.serverId).put("tool", b.first.remoteName).put("arguments", b.second), xml)
        val local = selected.firstOrNull { it.serverId == null } ?: return ""
        val args = (if (local.remoteName == "calculator") Router.forceArgs(local, text, Route.MATH) else null) ?: JSONObject()
        return render(local.name, args, xml)
    }

    private fun sig(t: McpTool): String = sig(t.name, t.schema)

    fun sig(name: String, schema: JSONObject): String {
        val props = schema.optJSONObject("properties")
        val reqArr = schema.optJSONArray("required")
        val req = if (reqArr == null) emptySet() else (0 until reqArr.length()).map { reqArr.getString(it) }.toSet()
        val ps = props?.keys()?.asSequence()?.take(14)?.joinToString(", ") { k ->
            val ty = props?.optJSONObject(k)?.optString("type", "") ?: ""
            k + (if (k in req) "*" else "") + (if (ty.isNotEmpty()) ":$ty" else "")
        } ?: ""
        return "$name($ps)"
    }

    private fun first(s: String, n: Int) = s.replace(Regex("\\s+"), " ").trim().substringBefore(". ").take(n)

    private fun ok(sid: String, tool: String, allowed: Set<String>?) = allowed == null || "$sid/$tool" in allowed

    fun catalog(sid: String, mcp: McpManager, allowed: Set<String>? = null): String {
        val cfg = mcp.config(sid) ?: return ""
        val sb = StringBuilder("Server `${cfg.id}` (${cfg.name}) tools (* = required):\n")
        for (t in mcp.state(sid).tools.filter { ok(sid, it.name, allowed) }) sb.append("- ").append(sig(t)).append(" — ").append(first(t.description, 110)).append('\n')
        return sb.toString().trimEnd()
    }

    private fun resolveServer(mcp: McpManager, key: String): String? {
        val k = key.trim().lowercase()
        if (k.isEmpty()) return null
        val cfgs = mcp.configs.value
        return cfgs.firstOrNull { it.id.lowercase() == k }?.id
            ?: cfgs.firstOrNull { it.name.lowercase().contains(k) || k.contains(it.id.lowercase()) }?.id
    }

    private fun ids(mcp: McpManager) = mcp.configs.value.joinToString { it.id }

    suspend fun run(name: String, args: JSONObject, mcp: McpManager, prior: MutableList<JSONObject?>, allowed: Set<String>?): GwOut = when (name) {
        LIST -> list(args, mcp, allowed)
        CALL -> call(args, mcp, prior, allowed)
        else -> GwOut(false, "ERROR: unknown app tool $name", name)
    }

    private suspend fun list(args: JSONObject, mcp: McpManager, allowed: Set<String>?): GwOut {
        val sKey = args.optString("server", "").trim()
        val tKey = args.optString("tool", "").trim()
        if (sKey.isEmpty()) {
            val sb = StringBuilder()
            for (c in mcp.configs.value.filter { it.enabled && (allowed == null || allowed.any { k -> k.startsWith(it.id + "/") }) }) {
                val st = mcp.ensureConnected(c.id)
                if (st.status == McpStatus.CONNECTED) sb.append(catalog(c.id, mcp, allowed)) else sb.append(c.id).append(" (").append(c.name).append("): not connected: ").append(st.error)
                sb.append('\n')
            }
            return GwOut(true, sb.toString().trim().ifEmpty { "no servers enabled" }, LIST)
        }
        val sid = resolveServer(mcp, sKey) ?: return GwOut(false, "ERROR: unknown server '$sKey'. Servers: ${ids(mcp)}", LIST)
        val st = mcp.ensureConnected(sid)
        if (st.status != McpStatus.CONNECTED) return GwOut(false, "ERROR: $sid not connected: ${st.error}", LIST)
        if (tKey.isEmpty()) return GwOut(true, catalog(sid, mcp, allowed), LIST)
        val avail = st.tools.filter { ok(sid, it.name, allowed) }
        val t = avail.firstOrNull { it.name == tKey }
            ?: return GwOut(false, "ERROR: server $sid has no usable tool '$tKey'. Tools: ${avail.joinToString { it.name }}", LIST)
        return GwOut(true, "${t.name}: ${t.description.take(1200)}\nSchema: ${t.schema}".take(4000), LIST)
    }

    private suspend fun call(args: JSONObject, mcp: McpManager, prior: MutableList<JSONObject?>, allowed: Set<String>?): GwOut {
        val sKey = args.optString("server", "").trim()
        val tKey = args.optString("tool", "").trim()
        val sid = resolveServer(mcp, sKey) ?: return GwOut(false, "ERROR: unknown server '$sKey'. Servers: ${ids(mcp)}", CALL)
        val st = mcp.ensureConnected(sid)
        if (st.status != McpStatus.CONNECTED) return GwOut(false, "ERROR: $sid not connected: ${st.error}", CALL)
        val avail = st.tools.filter { ok(sid, it.name, allowed) }
        val tool = avail.firstOrNull { it.name == tKey }
            ?: return GwOut(false, "ERROR: server $sid has no usable tool '$tKey'. Tools: ${avail.joinToString { it.name }}", CALL)
        val label = "$CALL → $sid/${tool.name}"
        val raw = args.opt("arguments")
        val base: JSONObject = when (raw) {
            is JSONObject -> raw
            is String -> try { JSONObject(raw) } catch (e: Exception) { return GwOut(false, "ERROR: 'arguments' is not valid JSON: ${e.message}", label) }
            else -> JSONObject()
        }
        val resolved = try {
            resolve(base, prior) as JSONObject
        } catch (e: IllegalArgumentException) {
            return GwOut(false, "ERROR: ${e.message}", label)
        }
        val coerced = ToolCallParser.coerce(resolved, tool.schema)
        ToolCallParser.validate(coerced, tool.schema)?.let {
            return GwOut(false, "ERROR: invalid arguments for $sid/${tool.name}: $it. Expected: ${sig(tool)}", label)
        }
        val res = mcp.call(sid, tool.name, coerced)
        val n = prior.size + 1
        prior.add(res.structured ?: try { JSONObject(res.text) } catch (e: Exception) { null })
        return if (res.ok) GwOut(true, "call $n · $sid/${tool.name}" + (if (prior[n - 1] != null) " (reuse values as @$n.path)" else "") + "\n" + res.text, label)
        else GwOut(false, "ERROR: call $n · $sid/${tool.name} failed: ${res.text.removePrefix("ERROR: ")}\nExpected: ${sig(tool)}", label)
    }

    fun resolve(v: Any?, prior: List<JSONObject?>): Any? = when (v) {
        is String -> {
            val m = REF.find(v.trim())
            if (m == null) v else {
                val n = m.groupValues[1].toInt()
                val data = prior.getOrNull(n - 1)
                    ?: throw IllegalArgumentException("reference $v: call $n has no structured result (calls so far: ${prior.size})")
                walk(data, m.groupValues[2])
                    ?: throw IllegalArgumentException("reference $v not found in call $n result; top-level keys: ${data.keys().asSequence().toList()}")
            }
        }
        is JSONObject -> {
            val o = JSONObject()
            for (k in v.keys().asSequence().toList()) o.put(k, resolve(v.get(k), prior))
            o
        }
        is JSONArray -> {
            val a = JSONArray()
            for (i in 0 until v.length()) a.put(resolve(v.get(i), prior))
            a
        }
        else -> v
    }

    fun walk(root: Any?, path: String): Any? {
        var cur: Any? = root
        for (p in path.split('.')) {
            val node = cur
            if (node is JSONObject) {
                if (!node.has(p) || node.isNull(p)) return null
                cur = node.get(p)
            } else if (node is JSONArray) {
                val i = p.toIntOrNull() ?: return null
                if (i < 0 || i >= node.length()) return null
                cur = node.get(i)
            } else return null
        }
        return cur
    }
}

object Grounding {
    private val FIG = Regex("(?<![\\w.:/-])(-?\\d+(?:\\.\\d+)?)(\\s?(?:°|%|mm|cm|km/h|kmh|km|mph|m/s|kn|hPa|µg|μg))?")
    private val NUM = Regex("-?\\d+(?:\\.\\d+)?")

    fun unsupported(answer: String, evidence: String): List<String> {
        val ev = NUM.findAll(evidence).mapNotNull { it.value.toDoubleOrNull() }.flatMap { sequenceOf(it, abs(it)) }.toList()
        val bad = LinkedHashSet<String>()
        for (m in FIG.findAll(answer)) {
            val raw = m.groupValues[1]
            val v = raw.toDoubleOrNull() ?: continue
            val unit = m.groupValues[2]
            val decimal = raw.contains('.')
            if (unit.isEmpty() && !decimal && abs(v) < 100) continue
            if (unit.isEmpty() && !decimal && abs(v) in 1900.0..2100.0) continue
            if (ev.none { abs(it - v) <= 0.51 }) bad.add(raw + unit.trim())
        }
        return bad.toList()
    }
}

object AppPrompt {
    fun build(selected: List<ToolSpec>, mcp: McpManager, allowed: Set<String>, text: String, xml: Boolean): String {
        val sb = StringBuilder()
        sb.append("TOOL MODE. The user switched tools on for this chat and selected the tools listed below. They are real and connected through the app. ")
        sb.append("Never say a tool is unavailable and never answer from memory when a tool can provide the data.\n")
        sb.append("How it works:\n")
        sb.append("1. Your first reply must be ONLY a tool call, with no other text.\n")
        sb.append("2. Server tools run through mcp_call_tool (server, tool, arguments). mcp_list_tools shows a tool's full schema.\n")
        sb.append("3. The app runs the tool and sends back a tool response. Then call another tool if needed, or write the final answer.\n")
        sb.append("4. To reuse a value from call N in a later call, write @N.path as the value, for example @1.results.0.latitude.\n")
        sb.append("5. If a call returns ERROR, read the error, fix the arguments and call again.\n")
        sb.append("6. Final answer: use only the tool results, quote numbers exactly with their units, cite source URLs, and say so if the results do not contain what was asked.\n")
        sb.append("Selected tools:\n")
        val sids = selected.mapNotNull { it.serverId }.distinct()
        for (sid in sids) sb.append(Gateway.catalog(sid, mcp, allowed)).append('\n')
        for (t in selected.filter { it.serverId == null }) sb.append("- ").append(Gateway.sig(t.name, t.schema)).append(" — ").append(t.description.take(110)).append('\n')
        val ex = Gateway.example(selected, text, xml)
        if (ex.isNotEmpty()) sb.append("Example tool call for the current request:\n").append(ex)
        return sb.toString().trimEnd()
    }
}
