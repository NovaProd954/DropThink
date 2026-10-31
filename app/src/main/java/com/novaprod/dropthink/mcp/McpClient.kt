package com.novaprod.dropthink.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

class McpException(message: String, val httpCode: Int = 0) : Exception(message)

data class McpTool(val name: String, val description: String, val schema: JSONObject)
data class McpCallResult(val ok: Boolean, val text: String, val structured: JSONObject? = null)
data class McpInit(val protocol: String, val serverName: String)

/** Minimal MCP client over Streamable HTTP (JSON or SSE responses). */
class McpClient(private val url: String, private val tokenProvider: () -> String?) {
    @Volatile private var sessionId: String? = null
    @Volatile private var protocol: String? = null
    private val ids = AtomicLong(1)

    companion object {
        const val PREFERRED = "2025-06-18"
        val SUPPORTED = setOf("2025-06-18", "2025-03-26", "2024-11-05")
    }

    suspend fun connect(): Pair<McpInit, List<McpTool>> = withContext(Dispatchers.IO) {
        val init = handshake()
        init to listToolsInternal()
    }

    suspend fun callTool(name: String, args: JSONObject): McpCallResult = withContext(Dispatchers.IO) {
        try {
            callOnce(name, args)
        } catch (e: McpException) {
            if (e.httpCode == 404 && sessionId != null) {
                handshake()
                callOnce(name, args)
            } else throw e
        }
    }

    private fun handshake(): McpInit {
        sessionId = null
        protocol = null
        val res = rpc(
            "initialize",
            JSONObject().put("protocolVersion", PREFERRED).put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", "DropThink").put("version", "0.4")),
        )
        val negotiated = res.optString("protocolVersion", PREFERRED)
        if (negotiated !in SUPPORTED) throw McpException("Server requires unsupported MCP protocol version $negotiated")
        protocol = negotiated
        notify("notifications/initialized")
        return McpInit(negotiated, res.optJSONObject("serverInfo")?.optString("name") ?: "")
    }

    private fun listToolsInternal(): List<McpTool> {
        val out = ArrayList<McpTool>()
        var cursor: String? = null
        var guard = 0
        do {
            val p = JSONObject()
            cursor?.let { p.put("cursor", it) }
            val r = rpc("tools/list", p)
            val arr = r.optJSONArray("tools") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                out.add(
                    McpTool(
                        t.getString("name"),
                        t.optString("description", ""),
                        t.optJSONObject("inputSchema") ?: JSONObject().put("type", "object"),
                    ),
                )
            }
            cursor = if (r.has("nextCursor") && !r.isNull("nextCursor")) r.getString("nextCursor").ifEmpty { null } else null
        } while (cursor != null && ++guard < 20)
        return out
    }

    private fun callOnce(name: String, args: JSONObject): McpCallResult {
        val r = rpc("tools/call", JSONObject().put("name", name).put("arguments", args))
        val sb = StringBuilder()
        val content = r.optJSONArray("content")
        if (content != null) {
            for (i in 0 until content.length()) {
                val c = content.optJSONObject(i) ?: continue
                when (c.optString("type")) {
                    "text" -> sb.append(c.optString("text")).append('\n')
                    "resource" -> c.optJSONObject("resource")?.let { sb.append(it.optString("text", "[binary resource omitted]")).append('\n') }
                    else -> sb.append("[").append(c.optString("type")).append(" content omitted]\n")
                }
            }
        }
        if (sb.isBlank() && r.has("structuredContent")) sb.append(r.opt("structuredContent").toString())
        return McpCallResult(!r.optBoolean("isError", false), sb.toString().trim(), r.optJSONObject("structuredContent"))
    }

    private fun notify(method: String) {
        post(JSONObject().put("jsonrpc", "2.0").put("method", method), null)
    }

    private fun rpc(method: String, params: JSONObject?): JSONObject {
        val id = ids.getAndIncrement()
        val body = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method)
        if (params != null) body.put("params", params)
        val msg = post(body, id) ?: throw McpException("Empty response to $method")
        msg.optJSONObject("error")?.let { throw McpException("$method failed: ${it.optString("message")} (code ${it.optInt("code")})") }
        return msg.optJSONObject("result") ?: JSONObject()
    }

    private fun post(body: JSONObject, expectId: Long?): JSONObject? {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 60000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "application/json, text/event-stream")
            sessionId?.let { c.setRequestProperty("Mcp-Session-Id", it) }
            protocol?.let { c.setRequestProperty("MCP-Protocol-Version", it) }
            tokenProvider()?.takeIf { it.isNotBlank() }?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            c.getHeaderField("Mcp-Session-Id")?.let { sessionId = it }
            if (code >= 400) {
                val err = runCatching { c.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty().take(1500)
                throw McpException("HTTP $code${if (err.isNotBlank()) ": $err" else ""}", code)
            }
            if (expectId == null || code == 202 || code == 204) return null
            val ct = c.contentType ?: ""
            return if (ct.contains("text/event-stream")) readSse(c.inputStream, expectId)
            else pick(c.inputStream.bufferedReader().use { it.readText() }, expectId)
                ?: throw McpException("Response did not contain a reply to request $expectId")
        } finally {
            c.disconnect()
        }
    }

    private fun readSse(input: InputStream, id: Long): JSONObject {
        val reader = input.bufferedReader()
        val data = StringBuilder()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) {
                val m = pick(data.toString(), id)
                data.setLength(0)
                if (m != null) return m
            } else if (line.startsWith("data:")) {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.removePrefix("data:").trimStart())
            }
        }
        pick(data.toString(), id)?.let { return it }
        throw McpException("Stream ended without a response")
    }

    private fun pick(text: String, id: Long): JSONObject? {
        val t = text.trim()
        if (t.isEmpty()) return null
        return try {
            if (t.startsWith("[")) {
                val a = JSONArray(t)
                (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { matches(it, id) }
            } else JSONObject(t).takeIf { matches(it, id) }
        } catch (_: Exception) {
            null
        }
    }

    private fun matches(o: JSONObject, id: Long) = o.has("id") && o.optLong("id", -1) == id && (o.has("result") || o.has("error"))
}
