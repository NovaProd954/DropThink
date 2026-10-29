package com.novaprod.dropthink.mcp

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

data class McpServerConfig(val id: String, val name: String, val url: String, val enabled: Boolean, val purpose: String)

enum class McpStatus { IDLE, CONNECTING, CONNECTED, ERROR }

data class McpState(
    val status: McpStatus = McpStatus.IDLE,
    val error: String? = null,
    val tools: List<McpTool> = emptyList(),
    val protocol: String? = null,
    val latencyMs: Long? = null,
    val serverName: String = "",
)

object McpDefaults {
    const val PARALLEL = "parallel"
    const val FIRECRAWL = "firecrawl"
    const val OPENMETEO = "openmeteo"

    fun servers() = listOf(
        McpServerConfig(PARALLEL, "Parallel Search", "https://search.parallel.ai/mcp", true, "Live web search and page fetch (keyless endpoint)"),
        McpServerConfig(FIRECRAWL, "Firecrawl", "https://mcp.firecrawl.dev/v2/mcp", true, "Search, scrape and parse pages (keyless endpoint)"),
        McpServerConfig(OPENMETEO, "Open-Meteo Weather", "https://open-meteo.caseyjhand.com/mcp", true, "Geocoding, weather, climate, air quality, marine, elevation (community-hosted)"),
    )
}

class McpManager(context: Context) {
    private val prefs = context.getSharedPreferences("mcp", Context.MODE_PRIVATE)
    val secrets = SecretStore(context)
    private val clients = ConcurrentHashMap<String, McpClient>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    private val _configs = MutableStateFlow(loadConfigs())
    val configs: StateFlow<List<McpServerConfig>> = _configs.asStateFlow()
    private val _states = MutableStateFlow<Map<String, McpState>>(emptyMap())
    val states: StateFlow<Map<String, McpState>> = _states.asStateFlow()

    private fun loadConfigs(): List<McpServerConfig> {
        val saved = runCatching { JSONObject(prefs.getString("enabled", "{}") ?: "{}") }.getOrDefault(JSONObject())
        return McpDefaults.servers().map { it.copy(enabled = saved.optBoolean(it.id, true)) }
    }

    private fun saveEnabled() {
        val o = JSONObject()
        _configs.value.forEach { o.put(it.id, it.enabled) }
        prefs.edit().putString("enabled", o.toString()).apply()
    }

    private fun setState(id: String, s: McpState) { _states.value = _states.value + (id to s) }
    fun state(id: String): McpState = _states.value[id] ?: McpState()
    fun config(id: String): McpServerConfig? = _configs.value.firstOrNull { it.id == id }

    fun setEnabled(id: String, enabled: Boolean) {
        _configs.value = _configs.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
        saveEnabled()
        if (!enabled) disconnect(id)
    }

    fun disconnect(id: String) {
        clients.remove(id)
        setState(id, McpState())
    }

    fun restoreDefaults() {
        clients.clear()
        _states.value = emptyMap()
        _configs.value = McpDefaults.servers()
        saveEnabled()
    }

    fun setToken(id: String, token: String) { secrets.put(id, token) }
    fun hasToken(id: String) = secrets.has(id)

    private fun describe(t: Throwable): String = when (t) {
        is UnknownHostException -> "Cannot reach the server (no internet or DNS failure)"
        is SocketTimeoutException -> "Timed out waiting for the server"
        else -> t.message ?: t.javaClass.simpleName
    }

    /** Initialize + discover tools. Safe to call repeatedly; it reconnects from scratch. */
    suspend fun connect(id: String): McpState {
        val cfg = config(id) ?: return McpState(McpStatus.ERROR, "Unknown server")
        val lock = locks.getOrPut(id) { Mutex() }
        return lock.withLock {
            setState(id, McpState(McpStatus.CONNECTING))
            val t0 = System.currentTimeMillis()
            try {
                val client = McpClient(cfg.url) { secrets.get(id) }
                val (init, tools) = client.connect()
                clients[id] = client
                val st = McpState(McpStatus.CONNECTED, null, tools, init.protocol, System.currentTimeMillis() - t0, init.serverName)
                setState(id, st)
                st
            } catch (e: Exception) {
                clients.remove(id)
                val st = McpState(McpStatus.ERROR, describe(e))
                setState(id, st)
                st
            }
        }
    }

    suspend fun ensureConnected(id: String): McpState {
        val s = state(id)
        return if (s.status == McpStatus.CONNECTED && clients.containsKey(id)) s else connect(id)
    }

    suspend fun call(id: String, tool: String, args: JSONObject): McpCallResult {
        val client = clients[id] ?: run {
            val st = ensureConnected(id)
            if (st.status != McpStatus.CONNECTED) return McpCallResult(false, "ERROR: server not connected: ${st.error}")
            clients[id]!!
        }
        return try {
            client.callTool(tool, args)
        } catch (e: Exception) {
            McpCallResult(false, "ERROR: ${describe(e)}")
        }
    }
}
