package com.novaprod.dropthink.tools

import com.novaprod.dropthink.mcp.McpDefaults
import org.json.JSONArray
import org.json.JSONObject

enum class Route(val label: String, val serverId: String?) {
    NONE("local", null),
    WEB_SEARCH("live web search", McpDefaults.PARALLEL),
    URL_EXTRACT("page extraction", McpDefaults.FIRECRAWL),
    WEATHER("weather", McpDefaults.OPENMETEO),
    MATH("calculator", null),
    SELECTED("selected tools", null),
}

object Router {
    private val I = RegexOption.IGNORE_CASE
    private val URL = Regex("https?://[^\\s<>\"')]+", I)
    private val CREATIVE = Regex("\\b(write|story|poem|essay|song|haiku|imagine|fiction|roleplay|pretend)\\b", I)
    private val WEATHER = Regex(
        "\\b(weather|forecast|temperature|rain(ing|fall)?|snow(fall|ing)?|humidity|windy|wind speed|storm|typhoon|hurricane|climate|air quality|aqi|pollution|uv index|sunrise|sunset|wave height|marine|elevation|altitude)\\b", I,
    )
    private val WEB = Regex(
        "\\b(latest|current(ly)?|today|tonight|yesterday|this (week|month|year)|recent(ly)?|news|breaking|headlines?|price of|stock|scores?|who (is|are|won)|release date|just released|search (the )?(web|internet|online)|look up|google|verify|fact[- ]?check|is it true|202[4-9])\\b", I,
    )
    private val MATH = Regex("(\\d+(\\.\\d+)?\\s*[-+*/^%×÷]\\s*\\d+)|\\b(calculate|compute|sqrt|square root|percent of)\\b", I)
    private val DOC = Regex("\\.(pdf|docx?|xlsx?|pptx?)(\\?|$|\\s)", I)

    fun firstUrl(text: String): String? = URL.find(text)?.value?.trimEnd('.', ',', ')', ';')

    fun classify(text: String): Route {
        if (URL.containsMatchIn(text)) return Route.URL_EXTRACT
        if (CREATIVE.containsMatchIn(text)) return Route.NONE
        if (WEATHER.containsMatchIn(text)) return Route.WEATHER
        if (MATH.containsMatchIn(text)) return Route.MATH
        if (WEB.containsMatchIn(text)) return Route.WEB_SEARCH
        return Route.NONE
    }

    fun select(route: Route, text: String, all: List<ToolSpec>, max: Int): List<ToolSpec> {
        return when (route) {
            Route.NONE, Route.SELECTED -> emptyList()
            Route.MATH -> all.filter { it.serverId == null && it.remoteName == "calculator" }
            Route.WEB_SEARCH, Route.URL_EXTRACT, Route.WEATHER -> Gateway.specs
        }
    }

    /** Deterministic arguments for a router-forced call, built only from the advertised schema. */
    fun forceArgs(spec: ToolSpec, text: String, route: Route): JSONObject? {
        val props = spec.schema.optJSONObject("properties") ?: return JSONObject()
        val reqArr = spec.schema.optJSONArray("required")
        val required = if (reqArr == null) emptyList() else (0 until reqArr.length()).map { reqArr.getString(it) }
        val url = firstUrl(text)
        val o = JSONObject()
        val names = if (required.isEmpty()) props.keys().asSequence().toList().take(1) else required
        for (k in names) {
            val type = props.optJSONObject(k)?.optString("type", "string") ?: "string"
            val value = if (route == Route.URL_EXTRACT && url != null) url
            else if (route == Route.MATH) (Regex("[0-9(][0-9+\\-*/^%().×÷ ]*[0-9)]").findAll(text).maxByOrNull { it.value.length }?.value ?: text)
            else text
            when {
                type == "string" -> o.put(k, value)
                type == "array" -> o.put(k, JSONArray().put(value))
                else -> return null
            }
        }
        if (route == Route.WEATHER) return null
        return o
    }
}
