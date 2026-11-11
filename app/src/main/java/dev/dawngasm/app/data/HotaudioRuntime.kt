package dev.dawngasm.app.data

import android.content.Context

fun HotaudioRuntime.initialize(context: Context) {
    val app = context.applicationContext

    PlatformFileOps.setCacheDir(app.cacheDir.absolutePath)
    if (HotaudioBridgeProvider.bridge == null) {
        HotaudioBridgeProvider.bridge = AndroidHotaudioBridge()
    }

    if (!isTlsReady()) {
        try {
            HotaudioLog.i("initAndroidTls: loading libhotaudio_ffi and platform verifier")
            net.hotaudio.extractor.Hotaudio.initAndroidTls(app)
            tlsReady = true
            tlsError = null
            HotaudioLog.i("initAndroidTls: OK")
        } catch (error: Throwable) {
            tlsError = error
            HotaudioLog.e(
                "initAndroidTls FAILED — hotaudio playback cannot work until this is fixed",
                error,
            )
        }
    } else {
        HotaudioLog.d("initAndroidTls: already initialized")
    }

    applyClearance(AppPreferences(app))
}
