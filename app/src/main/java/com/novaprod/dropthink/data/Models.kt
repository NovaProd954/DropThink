package com.novaprod.dropthink.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

enum class ModelStatus { INSPECTED, LOAD_OK, INCOMPATIBLE }

data class ModelEntry(
    val id: String,
    val displayName: String,
    val fileName: String,
    val uri: String?,
    val path: String?,
    val sizeBytes: Long,
    val arch: String,
    val quantFromName: String,
    val ftype: String,
    val nParams: Long,
    val nCtxTrain: Int,
    val templateHasThink: Boolean,
    val templateSupported: Boolean,
    val status: ModelStatus,
    val detail: String,
    val addedAt: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("displayName", displayName).put("fileName", fileName)
        .put("uri", uri ?: "").put("path", path ?: "").put("sizeBytes", sizeBytes)
        .put("arch", arch).put("quantFromName", quantFromName).put("ftype", ftype)
        .put("nParams", nParams).put("nCtxTrain", nCtxTrain)
        .put("templateHasThink", templateHasThink).put("templateSupported", templateSupported)
        .put("status", status.name).put("detail", detail).put("addedAt", addedAt)

    companion object {
        fun fromJson(o: JSONObject) = ModelEntry(
            id = o.getString("id"),
            displayName = o.optString("displayName"),
            fileName = o.optString("fileName"),
            uri = o.optString("uri").ifEmpty { null },
            path = o.optString("path").ifEmpty { null },
            sizeBytes = o.optLong("sizeBytes", -1),
            arch = o.optString("arch"),
            quantFromName = o.optString("quantFromName"),
            ftype = o.optString("ftype"),
            nParams = o.optLong("nParams", 0),
            nCtxTrain = o.optInt("nCtxTrain", 0),
            templateHasThink = o.optBoolean("templateHasThink", false),
            templateSupported = o.optBoolean("templateSupported", false),
            status = runCatching { ModelStatus.valueOf(o.optString("status")) }.getOrDefault(ModelStatus.INSPECTED),
            detail = o.optString("detail"),
            addedAt = o.optLong("addedAt", 0),
        )
    }
}

data class ChatMessage(
    val id: String,
    val role: String,
    val content: String,
    val reasoning: String = "",
    val footer: String = "",
    val notice: String = "",
    val isError: Boolean = false,
    val kind: String = "text",
    val sources: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("role", role).put("content", content).put("reasoning", reasoning)
        .put("footer", footer).put("notice", notice).put("isError", isError).put("kind", kind)
        .put("sources", JSONArray(sources))

    companion object {
        fun fromJson(o: JSONObject): ChatMessage {
            val src = o.optJSONArray("sources")
            return ChatMessage(
                id = o.getString("id"), role = o.optString("role", "user"), content = o.optString("content"),
                reasoning = o.optString("reasoning"), footer = o.optString("footer"), notice = o.optString("notice"),
                isError = o.optBoolean("isError", false), kind = o.optString("kind", "text"),
                sources = if (src == null) emptyList() else (0 until src.length()).map { src.getString(it) },
            )
        }
    }
}

data class Sampling(
    val temperature: Float,
    val topP: Float,
    val topK: Int,
    val minP: Float,
    val repeatPenalty: Float,
    val presencePenalty: Float,
    val frequencyPenalty: Float,
    val penaltyLastN: Int,
    val seed: Int,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("temperature", temperature.toDouble()).put("topP", topP.toDouble()).put("topK", topK)
        .put("minP", minP.toDouble()).put("repeatPenalty", repeatPenalty.toDouble())
        .put("presencePenalty", presencePenalty.toDouble()).put("frequencyPenalty", frequencyPenalty.toDouble())
        .put("penaltyLastN", penaltyLastN).put("seed", seed)

    companion object {
        fun fromJson(o: JSONObject, d: Sampling) = Sampling(
            o.optDouble("temperature", d.temperature.toDouble()).toFloat(),
            o.optDouble("topP", d.topP.toDouble()).toFloat(),
            o.optInt("topK", d.topK),
            o.optDouble("minP", d.minP.toDouble()).toFloat(),
            o.optDouble("repeatPenalty", d.repeatPenalty.toDouble()).toFloat(),
            o.optDouble("presencePenalty", d.presencePenalty.toDouble()).toFloat(),
            o.optDouble("frequencyPenalty", d.frequencyPenalty.toDouble()).toFloat(),
            o.optInt("penaltyLastN", d.penaltyLastN),
            o.optInt("seed", d.seed),
        )
    }
}

data class Preset(val name: String, val sampling: Sampling)

val GENERAL_SAMPLING = Sampling(0.7f, 0.8f, 20, 0.0f, 1.0f, 0.5f, 0.0f, 64, -1)
val TOOL_SAMPLING = Sampling(0.2f, 0.9f, 20, 0.05f, 1.0f, 0.0f, 0.0f, 64, -1)
const val TOOL_PRESET_INDEX = 1

fun defaultPresets() = listOf(
    Preset("General conversation", GENERAL_SAMPLING),
    Preset("Low-randomness tool calling", TOOL_SAMPLING),
)

data class AppSettings(
    val nCtx: Int = 4096,
    val nBatch: Int = 512,
    val nUbatch: Int = 512,
    val threads: Int = 0,
    val useMmap: Boolean = true,
    val maxTokensPerPass: Int = 1024,
    val totalOutputBudget: Int = 8192,
    val autoContinue: Boolean = true,
    val maxContinuations: Int = 6,
    val thinking: Boolean = false,
    val systemPrompt: String = "You are a helpful assistant.",
    val toolsEnabled: Boolean = true,
    val maxToolsExposed: Int = 3,
    val maxToolCalls: Int = 4,
    val toolRetries: Int = 2,
    val maxToolResultChars: Int = 6000,
    val presets: List<Preset> = defaultPresets(),
    val activePreset: Int = 0,
    val lastModelId: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("nCtx", nCtx).put("nBatch", nBatch).put("nUbatch", nUbatch).put("threads", threads)
        .put("useMmap", useMmap).put("maxTokensPerPass", maxTokensPerPass)
        .put("totalOutputBudget", totalOutputBudget).put("autoContinue", autoContinue)
        .put("maxContinuations", maxContinuations).put("thinking", thinking).put("systemPrompt", systemPrompt)
        .put("toolsEnabled", toolsEnabled).put("maxToolsExposed", maxToolsExposed)
        .put("maxToolCalls", maxToolCalls).put("toolRetries", toolRetries).put("maxToolResultChars", maxToolResultChars)
        .put("activePreset", activePreset).put("lastModelId", lastModelId ?: "")
        .put("presets", JSONArray(presets.map { JSONObject().put("name", it.name).put("sampling", it.sampling.toJson()) }))

    companion object {
        fun fromJson(o: JSONObject): AppSettings {
            val d = AppSettings()
            val defaults = defaultPresets()
            val pa = o.optJSONArray("presets")
            val presets = if (pa == null || pa.length() == 0) defaults else (0 until pa.length()).map { i ->
                val p = pa.getJSONObject(i)
                val base = defaults.getOrElse(i) { defaults[0] }.sampling
                Preset(p.optString("name", "Preset ${i + 1}"), Sampling.fromJson(p.optJSONObject("sampling") ?: JSONObject(), base))
            }
            return AppSettings(
                nCtx = o.optInt("nCtx", d.nCtx), nBatch = o.optInt("nBatch", d.nBatch), nUbatch = o.optInt("nUbatch", d.nUbatch),
                threads = o.optInt("threads", d.threads), useMmap = o.optBoolean("useMmap", d.useMmap),
                maxTokensPerPass = o.optInt("maxTokensPerPass", d.maxTokensPerPass),
                totalOutputBudget = o.optInt("totalOutputBudget", d.totalOutputBudget),
                autoContinue = o.optBoolean("autoContinue", d.autoContinue),
                maxContinuations = o.optInt("maxContinuations", d.maxContinuations),
                thinking = o.optBoolean("thinking", d.thinking), systemPrompt = o.optString("systemPrompt", d.systemPrompt),
                toolsEnabled = o.optBoolean("toolsEnabled", d.toolsEnabled),
                maxToolsExposed = o.optInt("maxToolsExposed", d.maxToolsExposed),
                maxToolCalls = o.optInt("maxToolCalls", d.maxToolCalls), toolRetries = o.optInt("toolRetries", d.toolRetries),
                maxToolResultChars = o.optInt("maxToolResultChars", d.maxToolResultChars),
                presets = presets, activePreset = o.optInt("activePreset", 0).coerceIn(0, presets.lastIndex),
                lastModelId = o.optString("lastModelId").ifEmpty { null },
            )
        }
    }
}
