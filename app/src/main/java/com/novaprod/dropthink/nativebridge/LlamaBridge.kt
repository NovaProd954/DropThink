package com.novaprod.dropthink.nativebridge

fun interface TokenSink {
    fun onPiece(bytes: ByteArray): Boolean
}

object LlamaBridge {
    @Volatile
    var loadError: String? = null
        private set

    @Volatile
    private var ready = false
    val isReady: Boolean get() = ready

    @Synchronized
    fun init(): Boolean {
        if (ready) return true
        return try {
            System.loadLibrary("dropthink")
            nBackendInit()
            ready = true
            true
        } catch (t: Throwable) {
            loadError = t.message ?: t.toString()
            false
        }
    }

    private fun str(b: ByteArray?): String = if (b == null) "" else String(b, Charsets.UTF_8)

    private fun kv(b: ByteArray?): Map<String, String> {
        if (b == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (line in String(b, Charsets.UTF_8).lineSequence()) {
            val i = line.indexOf('\t')
            if (i > 0) out[line.substring(0, i)] = line.substring(i + 1)
        }
        return out
    }

    fun systemInfo(): String = str(nSystemInfo())
    fun lastError(): String = str(nLastError())
    fun inspectFd(fd: Int): Map<String, String>? = nInspectFd(fd)?.let { kv(it) }
    fun loadFd(fd: Int, nGpuLayers: Int, useMmap: Boolean): Long = nLoadFd(fd, nGpuLayers, useMmap)
    fun loadPath(path: String, nGpuLayers: Int, useMmap: Boolean): Long = nLoadPath(path.toByteArray(Charsets.UTF_8), nGpuLayers, useMmap)
    fun freeModel(h: Long) = nFreeModel(h)
    fun modelInfo(h: Long): Map<String, String> = kv(nModelInfo(h))
    fun applyTemplate(h: Long, roles: Array<String>, contents: Array<ByteArray>, addAssistant: Boolean): ByteArray? =
        nApplyTemplate(h, roles, contents, addAssistant)
    fun countTokens(h: Long, text: ByteArray): Int = nCountTokens(h, text)
    fun createContext(h: Long, nCtx: Int, nBatch: Int, nUbatch: Int, nThreads: Int, nThreadsBatch: Int): Long =
        nCreateContext(h, nCtx, nBatch, nUbatch, nThreads, nThreadsBatch)
    fun freeContext(c: Long) = nFreeContext(c)
    fun contextInfo(c: Long): Map<String, String> = kv(nContextInfo(c))
    fun cancel(c: Long) = nCancel(c)
    fun generate(
        c: Long, prompt: ByteArray, maxTokens: Int, temp: Float, topK: Int, topP: Float, minP: Float,
        repeat: Float, presence: Float, freq: Float, lastN: Int, seed: Int, sink: TokenSink,
    ): DoubleArray? = nGenerate(c, prompt, maxTokens, temp, topK, topP, minP, repeat, presence, freq, lastN, seed, sink)

    private external fun nBackendInit()
    private external fun nSystemInfo(): ByteArray
    private external fun nLastError(): ByteArray
    private external fun nInspectFd(fd: Int): ByteArray?
    private external fun nLoadFd(fd: Int, nGpuLayers: Int, useMmap: Boolean): Long
    private external fun nLoadPath(path: ByteArray, nGpuLayers: Int, useMmap: Boolean): Long
    private external fun nFreeModel(h: Long)
    private external fun nModelInfo(h: Long): ByteArray?
    private external fun nApplyTemplate(h: Long, roles: Array<String>, contents: Array<ByteArray>, addAssistant: Boolean): ByteArray?
    private external fun nCountTokens(h: Long, text: ByteArray): Int
    private external fun nCreateContext(h: Long, nCtx: Int, nBatch: Int, nUbatch: Int, nThreads: Int, nThreadsBatch: Int): Long
    private external fun nFreeContext(c: Long)
    private external fun nContextInfo(c: Long): ByteArray?
    private external fun nCancel(c: Long)
    private external fun nGenerate(
        c: Long, prompt: ByteArray, maxTokens: Int, temp: Float, topK: Int, topP: Float, minP: Float,
        repeat: Float, presence: Float, freq: Float, lastN: Int, seed: Int, sink: TokenSink,
    ): DoubleArray?
}
