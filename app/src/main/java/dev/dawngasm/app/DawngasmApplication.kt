package dev.dawngasm.app

import android.app.Application
import dev.dawngasm.app.data.AppPreferences
import dev.dawngasm.app.data.HotaudioLog
import dev.dawngasm.app.data.HotaudioRuntime
import dev.dawngasm.app.data.Network

/**
 * Initializes the hotaudio native runtime before any Activity or network call.
 * The upstream bindings require `initAndroidTls` exactly once, early.
 */
class DawngasmApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        HotaudioLog.i("Application.onCreate: initializing hotaudio runtime")
        HotaudioRuntime.initialize(this)
        val audiochanClearance = AppPreferences(this).audiochanClearance()
        if (audiochanClearance != null) {
            Network.syncCookie("https://audiochan.com/", audiochanClearance.first)
            Network.syncCookie("https://api.audiochan.com/", audiochanClearance.first)
        }
    }
}
