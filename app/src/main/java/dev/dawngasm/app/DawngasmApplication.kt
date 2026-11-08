package dev.dawngasm.app

import android.app.Application
import dev.dawngasm.app.data.HotaudioLog
import dev.dawngasm.app.data.HotaudioRuntime

/**
 * Initializes the hotaudio native runtime before any Activity or network call.
 * The upstream bindings require `initAndroidTls` exactly once, early.
 */
class DawngasmApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        HotaudioLog.i("Application.onCreate: initializing hotaudio runtime")
        HotaudioRuntime.initialize(this)
    }
}
