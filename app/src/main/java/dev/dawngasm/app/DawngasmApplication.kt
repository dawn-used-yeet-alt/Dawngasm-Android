package dev.dawngasm.app

import android.app.Application
import dev.dawngasm.app.data.AndroidHotaudioBridge
import dev.dawngasm.app.data.AppPreferences
import dev.dawngasm.app.data.HotaudioBridgeProvider
import dev.dawngasm.app.data.HotaudioLog
import dev.dawngasm.app.data.HotaudioRuntime
import dev.dawngasm.app.data.Network
import dev.dawngasm.app.data.PlatformFileOps

/**
 * Initializes the hotaudio native runtime before any Activity or network call.
 * The upstream bindings require `initAndroidTls` exactly once, early.
 */
class DawngasmApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        HotaudioLog.i("Application.onCreate: initializing hotaudio runtime")
        PlatformFileOps.setCacheDir(cacheDir.absolutePath)
        if (HotaudioBridgeProvider.bridge == null) {
            HotaudioBridgeProvider.bridge = AndroidHotaudioBridge()
        }
        HotaudioRuntime.onInitialize = {
            try {
                net.hotaudio.extractor.Hotaudio.initAndroidTls(this)
                HotaudioRuntime.tlsReady = true
                HotaudioRuntime.tlsError = null
            } catch (t: Throwable) {
                HotaudioRuntime.tlsError = t
                HotaudioLog.e("initAndroidTls failed", t)
            }
        }
        HotaudioRuntime.initialize(this)

        val audiochanClearance = AppPreferences(this).audiochanClearance()
        if (audiochanClearance != null) {
            Network.syncCookie("https://audiochan.com/", audiochanClearance.first)
            Network.syncCookie("https://api.audiochan.com/", audiochanClearance.first)
        }
    }
}
