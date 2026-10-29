package com.novaprod.dropthink.util

import android.app.ActivityManager
import android.content.Context

object MemoryAdvisor {
    enum class Level { OK, WARN, BLOCK }
    data class Assessment(val level: Level, val message: String)

    private fun mb(b: Long) = b / 1048576

    fun snapshot(ctx: Context): ActivityManager.MemoryInfo {
        val mi = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        return mi
    }

    fun line(ctx: Context): String {
        val mi = snapshot(ctx)
        return "RAM: ${mb(mi.availMem)} MB free of ${mb(mi.totalMem)} MB"
    }

    fun assess(ctx: Context, modelBytes: Long): Assessment {
        val mi = snapshot(ctx)
        val need = (modelBytes * 1.15).toLong() + 300L * 1048576
        return when {
            need > mi.totalMem * 0.9 -> Assessment(Level.BLOCK, "This model needs roughly ${mb(need)} MB but the device has only ${mb(mi.totalMem)} MB in total. Not loading it.")
            need > mi.availMem -> Assessment(Level.WARN, "Estimated need ${mb(need)} MB exceeds currently free RAM (${mb(mi.availMem)} MB). Loading may be slow or the app may be killed; close other apps or lower the context length.")
            else -> Assessment(Level.OK, "")
        }
    }
}
