package com.novaprod.dropthink.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.novaprod.dropthink.chat.ChatOrchestrator
import com.novaprod.dropthink.data.*
import com.novaprod.dropthink.engine.LlamaEngine
import com.novaprod.dropthink.engine.LoadedModel
import com.novaprod.dropthink.mcp.McpManager
import com.novaprod.dropthink.mcp.McpStatus
import com.novaprod.dropthink.nativebridge.LlamaBridge
import com.novaprod.dropthink.tools.ToolCallParser
import com.novaprod.dropthink.tools.ToolLog
import com.novaprod.dropthink.tools.ToolRegistry
import com.novaprod.dropthink.util.MemoryAdvisor
import com.novaprod.dropthink.util.parseQuant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.FileInputStream

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app.applicationContext
    val engine = LlamaEngine(ctx)
    val mcp = McpManager(ctx)
    val toolLog = ToolLog()
    private val modelStore = ModelStore(ctx)
    private val settingsStore = SettingsStore(ctx)
    private val chatStore = ChatStore(ctx)

    val models: StateFlow<List<ModelEntry>> = modelStore.models
    private val _settings = MutableStateFlow(settingsStore.load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    private val _messages = MutableStateFlow(chatStore.load())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating.asStateFlow()
    private val _loaded = MutableStateFlow<LoadedModel?>(null)
    val loaded: StateFlow<LoadedModel?> = _loaded.asStateFlow()
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    val backendInfo: String = if (LlamaBridge.isReady) LlamaBridge.systemInfo() else "Native library failed to load: ${LlamaBridge.loadError}"

    private val orchestrator = ChatOrchestrator(engine, mcp, toolLog, _messages) { _status.value = it }
    private var job: Job? = null

    fun modelName(id: String) = models.value.firstOrNull { it.id == id }?.displayName ?: id
    fun memoryLine() = MemoryAdvisor.line(ctx)

    fun updateSettings(f: (AppSettings) -> AppSettings) {
        val n = f(_settings.value)
        _settings.value = n
        settingsStore.save(n)
    }

    fun updateSampling(f: (Sampling) -> Sampling) = updateSettings { s ->
        s.copy(presets = s.presets.mapIndexed { i, p -> if (i == s.activePreset) p.copy(sampling = f(p.sampling)) else p })
    }

    fun updateToolPreset(f: (Sampling) -> Sampling) = updateSettings { s ->
        s.copy(presets = s.presets.mapIndexed { i, p -> if (i == TOOL_PRESET_INDEX) p.copy(sampling = f(p.sampling)) else p })
    }

    fun resetPresets() = updateSettings { it.copy(presets = defaultPresets(), activePreset = 0) }

    // ---- models ----
    fun importModel(uri: Uri) {
        if (_busy.value != null) return
        viewModelScope.launch {
            _busy.value = "Reading GGUF…"
            try {
                val cr = ctx.contentResolver
                runCatching { cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                var name = "model.gguf"
                var size = -1L
                cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.let { name = it }
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
                if (size < 0) size = cr.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1
                if (models.value.any { it.uri == uri.toString() }) { _status.value = "$name is already in the list."; return@launch }
                val magicOk = cr.openFileDescriptor(uri, "r")?.use { pfd ->
                    val b = ByteArray(4)
                    FileInputStream(pfd.fileDescriptor).read(b) == 4 && String(b, Charsets.US_ASCII) == "GGUF"
                } ?: false
                if (!magicOk) { _status.value = "Not a GGUF file: $name"; return@launch }
                val r = engine.inspect(uri)
                val e = r.fold(
                    onSuccess = { m ->
                        ModelEntry(
                            newId(), m["name"].orEmpty().ifBlank { name.removeSuffix(".gguf") }, name, uri.toString(), null, size,
                            m["arch"].orEmpty(), parseQuant(name), m["ftype"].orEmpty(), m["n_params"]?.toLongOrNull() ?: 0,
                            m["n_ctx_train"]?.toIntOrNull() ?: 0, m["template_has_think"] == "1", m["template_supported"] == "1",
                            ModelStatus.INSPECTED, "", System.currentTimeMillis(),
                        )
                    },
                    onFailure = { t ->
                        ModelEntry(newId(), name.removeSuffix(".gguf"), name, uri.toString(), null, size, "", parseQuant(name), "", 0, 0, false, false,
                            ModelStatus.INCOMPATIBLE, t.message ?: "unsupported", System.currentTimeMillis())
                    },
                )
                modelStore.upsert(e)
                _status.value = if (e.status == ModelStatus.INCOMPATIBLE) "Added, but flagged incompatible: ${e.detail}" else "Added ${e.displayName} (linked in place, not copied)."
            } catch (t: Throwable) {
                _status.value = "Import failed: ${t.message}"
            } finally {
                _busy.value = null
            }
        }
    }

    fun loadModel(id: String) {
        if (_generating.value || _busy.value != null) return
        val e = models.value.firstOrNull { it.id == id } ?: return
        if (e.status == ModelStatus.INCOMPATIBLE) { _status.value = "Flagged incompatible: ${e.detail}"; return }
        val s = _settings.value
        val a = MemoryAdvisor.assess(ctx, e.sizeBytes)
        if (a.level == MemoryAdvisor.Level.BLOCK) { _status.value = a.message; return }
        viewModelScope.launch {
            _busy.value = "Loading ${e.displayName}…"
            _status.value = a.message.ifEmpty { null }
            _loaded.value = null
            engine.load(e, s).fold(
                onSuccess = { l ->
                    _loaded.value = l
                    modelStore.upsert(e.copy(status = ModelStatus.LOAD_OK, detail = ""))
                    updateSettings { it.copy(lastModelId = e.id) }
                    _status.value = "Loaded in ${l.loadMs / 1000.0} s"
                },
                onFailure = { t ->
                    modelStore.upsert(e.copy(detail = "Last load failed: ${t.message?.take(300)}"))
                    _status.value = "Load failed: ${t.message}"
                },
            )
            _busy.value = null
        }
    }

    fun unloadModel() {
        if (_generating.value) return
        viewModelScope.launch { engine.unload(); _loaded.value = null; _status.value = "Model unloaded" }
    }

    fun removeModel(id: String) {
        if (_loaded.value?.entryId == id) { _status.value = "Unload the model first. (Removing only forgets the link; your file is never deleted.)"; return }
        val e = models.value.firstOrNull { it.id == id }
        modelStore.remove(id)
        e?.uri?.let { u -> runCatching { if (models.value.none { it.uri == u }) ctx.contentResolver.releasePersistableUriPermission(Uri.parse(u), Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
    }

    // ---- chat ----
    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || _generating.value) return
        _generating.value = true
        _status.value = null
        job = viewModelScope.launch {
            try {
                orchestrator.run(t, _settings.value) { chatStore.save(_messages.value) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _messages.update { it + ChatMessage(newId(), "assistant", "", footer = "Error: ${e.message}", isError = true) }
            } finally {
                _generating.value = false
                chatStore.save(_messages.value)
            }
        }
    }

    fun stop() {
        orchestrator.stop()
    }

    fun clearChat() {
        if (_generating.value) return
        _messages.value = emptyList()
        chatStore.save(emptyList())
    }

    // ---- tools tab ----
    fun connectServer(id: String) { viewModelScope.launch { mcp.connect(id) } }
    fun setServerEnabled(id: String, on: Boolean) {
        mcp.setEnabled(id, on)
        if (on) connectServer(id)
    }
    fun setToken(id: String, token: String) = mcp.setToken(id, token)
    fun restoreServers() = mcp.restoreDefaults()
    fun clearCredentials() = mcp.secrets.clearAll()

    fun testTool(toolName: String, argsJson: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val spec = ToolRegistry.build(mcp.states.value).firstOrNull { it.name == toolName }
            if (spec == null) { onResult("Unknown tool"); return@launch }
            val args = try { JSONObject(argsJson.ifBlank { "{}" }) } catch (e: Exception) { onResult("Arguments are not valid JSON: ${e.message}"); return@launch }
            val coerced = ToolCallParser.coerce(args, spec.schema)
            ToolCallParser.validate(coerced, spec.schema)?.let { onResult("Invalid arguments: $it"); return@launch }
            val t0 = System.currentTimeMillis()
            val res = if (spec.serverId == null) com.novaprod.dropthink.tools.LocalTools.run(spec.remoteName, coerced) else mcp.call(spec.serverId, spec.remoteName, coerced)
            toolLog.add(com.novaprod.dropthink.tools.ToolLogEntry(System.currentTimeMillis(), "manual test", spec.name, coerced.toString().take(400), res.ok, System.currentTimeMillis() - t0, res.text.take(300), "direct test"))
            onResult((if (res.ok) "OK" else "FAILED") + " (${System.currentTimeMillis() - t0} ms)\n" + res.text.take(4000))
        }
    }

    fun connectedCount(): Int = mcp.states.value.values.count { it.status == McpStatus.CONNECTED }
}
