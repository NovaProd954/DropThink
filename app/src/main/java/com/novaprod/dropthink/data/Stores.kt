package com.novaprod.dropthink.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private fun writeAtomic(file: File, text: String) {
    val tmp = File(file.path + ".tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(file)) file.writeText(text)
}

class ModelStore(context: Context) {
    private val file = File(context.filesDir, "models.json")
    private val _models = MutableStateFlow(read())
    val models: StateFlow<List<ModelEntry>> = _models.asStateFlow()

    private fun read(): List<ModelEntry> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { ModelEntry.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    @Synchronized
    fun upsert(e: ModelEntry) {
        val cur = _models.value
        val l = if (cur.any { it.id == e.id }) cur.map { if (it.id == e.id) e else it } else cur + e
        _models.value = l
        writeAtomic(file, JSONArray(l.map { it.toJson() }).toString())
    }

    @Synchronized
    fun remove(id: String) {
        val l = _models.value.filter { it.id != id }
        _models.value = l
        writeAtomic(file, JSONArray(l.map { it.toJson() }).toString())
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun load(): AppSettings = runCatching { AppSettings.fromJson(JSONObject(prefs.getString("json", "") ?: "")) }.getOrDefault(AppSettings())
    fun save(s: AppSettings) { prefs.edit().putString("json", s.toJson().toString()).apply() }
}

class ChatStore(context: Context) {
    private val file = File(context.filesDir, "chat.json")
    fun load(): List<ChatMessage> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { ChatMessage.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    @Synchronized
    fun save(list: List<ChatMessage>) = writeAtomic(file, JSONArray(list.map { it.toJson() }).toString())
}
