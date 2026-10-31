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

    private fun sig(t: McpTool): String {
        val props = t.schema.optJSONObject("properties")
        val reqArr = t.schema.optJSONArray("required")
        val req = if (reqArr == null) emptySet() else (0 until reqArr.length()).map { reqArr.getString(it) }.toSet()
        val ps = props?.keys()?.asSequence()?.take(14)?.joinToString(", ") { k ->
            val ty = props?.optJSONObject(k)?.optString("type", "") ?: ""
            k + (if (k in req) "*" else "") + (if (ty.isNotEmpty()) ":$ty" else "")
        } ?: ""
        return "${t.name}($ps)"
    }

    private fun first(s: String, n: Int) = s.replace(Regex("\\s+"), " ").trim().substringBefore(". ").take(n)

    fun catalog(sid: String, mcp: McpManager): String {
        val cfg = mcp.config(sid) ?: return ""
        val sb = StringBuilder("Server `${cfg.id}` (${cfg.name}) tools (* = required):\n")
        for (t in mcp.state(sid).tools) sb.append("- ").append(sig(t)).append(" — ").append(first(t.description, 110)).append('\n')
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

    suspend fun run(name: String, args: JSONObject, mcp: McpManager, prior: MutableList<JSONObject?>): GwOut = when (name) {
        LIST -> list(args, mcp)
        CALL -> call(args, mcp, prior)
        else -> GwOut(false, "ERROR: unknown app tool $name", name)
    }

    private suspend fun list(args: JSONObject, mcp: McpManager): GwOut {
        val sKey = args.optString("server", "").trim()
        val tKey = args.optString("tool", "").trim()
        if (sKey.isEmpty()) {
            val sb = StringBuilder()
            for (c in mcp.configs.value.filter { it.enabled }) {
                val st = mcp.ensureConnected(c.id)
                sb.append(c.id).append(" (").append(c.name).append("): ")
                if (st.status == McpStatus.CONNECTED) sb.append(st.tools.joinToString { it.name }) else sb.append("not connected: ").append(st.error)
                sb.append('\n')
            }
            return GwOut(true, sb.toString().trim().ifEmpty { "no servers enabled" }, LIST)
        }
        val sid = resolveServer(mcp, sKey) ?: return GwOut(false, "ERROR: unknown server '$sKey'. Servers: ${ids(mcp)}", LIST)
        val st = mcp.ensureConnected(sid)
        if (st.status != McpStatus.CONNECTED) return GwOut(false, "ERROR: $sid not connected: ${st.error}", LIST)
        if (tKey.isEmpty()) return GwOut(true, catalog(sid, mcp), LIST)
        val t = st.tools.firstOrNull { it.name == tKey }
            ?: return GwOut(false, "ERROR: server $sid has no tool '$tKey'. Tools: ${st.tools.joinToString { it.name }}", LIST)
        return GwOut(true, "${t.name}: ${t.description.take(1200)}\nSchema: ${t.schema}".take(4000), LIST)
    }

    private suspend fun call(args: JSONObject, mcp: McpManager, prior: MutableList<JSONObject?>): GwOut {
        val sKey = args.optString("server", "").trim()
        val tKey = args.optString("tool", "").trim()
        val sid = resolveServer(mcp, sKey) ?: return GwOut(false, "ERROR: unknown server '$sKey'. Servers: ${ids(mcp)}", CALL)
        val st = mcp.ensureConnected(sid)
        if (st.status != McpStatus.CONNECTED) return GwOut(false, "ERROR: $sid not connected: ${st.error}", CALL)
        val tool = st.tools.firstOrNull { it.name == tKey }
            ?: return GwOut(false, "ERROR: server $sid has no tool '$tKey'. Tools: ${st.tools.joinToString { it.name }}", CALL)
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
