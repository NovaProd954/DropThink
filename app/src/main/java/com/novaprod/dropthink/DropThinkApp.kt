package com.novaprod.dropthink

import android.app.Application
import com.novaprod.dropthink.nativebridge.LlamaBridge

class DropThinkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LlamaBridge.init()
    }
}
