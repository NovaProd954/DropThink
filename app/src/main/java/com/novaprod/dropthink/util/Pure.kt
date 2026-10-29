package com.novaprod.dropthink.util

data class ThinkSplit(val reasoning: String, val answer: String, val inThink: Boolean)

fun splitThinking(raw: String, startedInThink: Boolean): ThinkSplit {
    var text = raw
    var inThink = startedInThink
    if (!inThink) {
        val t = text.trimStart()
        if (t.startsWith("<think>")) {
            inThink = true
            text = t.removePrefix("<think>")
        } else return ThinkSplit("", raw, false)
    }
    val end = text.indexOf("</think>")
    return if (end < 0) ThinkSplit(text.trimStart('\n'), "", true)
    else ThinkSplit(text.substring(0, end).trim(), text.substring(end + 8).trimStart('\n'), false)
}

private val QUANT = Regex(
    "(?<![A-Za-z0-9])((?:UD-)?(?:IQ\\d(?:_[A-Za-z0-9]+)*|Q\\d(?:_[A-Za-z0-9]+)*|BF16|F16|F32))(?![A-Za-z0-9])",
    RegexOption.IGNORE_CASE,
)

fun parseQuant(fileName: String): String =
    QUANT.find(fileName.removeSuffix(".gguf"))?.groupValues?.get(1)?.uppercase() ?: ""

fun formatBytes(b: Long): String {
    if (b < 0) return "?"
    val gb = b / 1073741824.0
    return if (gb >= 1) String.format("%.2f GB", gb) else String.format("%.0f MB", b / 1048576.0)
}

fun autoThreads(): Int {
    val n = Runtime.getRuntime().availableProcessors()
    return if (n >= 8) 4 else maxOf(2, n / 2)
}

fun trimOverlap(prev: String, next: String, minLen: Int = 24, maxLen: Int = 400): String {
    val tail = prev.takeLast(maxLen)
    val max = minOf(tail.length, next.length)
    var k = max
    while (k >= minLen) {
        if (tail.endsWith(next.substring(0, k))) return next.substring(k)
        k--
    }
    return next
}

fun looksRepetitive(text: String, unit: Int = 60, times: Int = 4): Boolean {
    if (text.length < unit * times) return false
    val tail = text.takeLast(unit)
    if (tail.isBlank()) return false
    val window = text.takeLast(unit * times * 2)
    var count = 0
    var idx = 0
    while (true) {
        idx = window.indexOf(tail, idx)
        if (idx < 0) break
        count++
        idx += tail.length
    }
    return count >= times
}

private val URL_RE = Regex("https?://[^\\s<>\"')\\]]+")

fun extractUrls(text: String): List<String> =
    URL_RE.findAll(text).map { it.value.trimEnd('.', ',', ';', ':', '!', '?') }.distinct().toList()
