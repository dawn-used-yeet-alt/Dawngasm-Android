package dev.dawngasm.app.data

import android.content.Context

/**
 * Process-wide hotaudio setup: the Android rustls verifier, the saved
 * Cloudflare clearance, and the saved IP-family hint.
 *
 * The upstream bindings require [net.hotaudio.extractor.Hotaudio.initAndroidTls]
 * once, before the first HTTPS request. It must not be silently skipped: if the
 * native library or the verifier is unavailable, every hotaudio request fails,
 * so failures are logged and kept for the UI.
 */
object HotaudioRuntime {
    @Volatile
    var tlsError: Throwable? = null
        private set

    @Volatile
    private var tlsReady = false

    @Synchronized
    fun initialize(context: Context) {
        val app = context.applicationContext

        if (!tlsReady) {
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

    /** Push the saved clearance and family hint into the native extractor. */
    fun applyClearance(prefs: AppPreferences?) {
        if (prefs == null) {
            HotaudioLog.w("applyClearance: no preferences")
            return
        }

        val clearance = prefs.hotaudioClearance()
        if (clearance == null) {
            HotaudioLog.i("applyClearance: no saved cf_clearance")
        } else {
            try {
                net.hotaudio.extractor.setClearance(clearance.first, clearance.second)
                HotaudioLog.i(
                    "applyClearance: OK cookieLen=${clearance.first.length} " +
                        "ua=${clearance.second.take(80)}",
                )
            } catch (error: Throwable) {
                HotaudioLog.e("applyClearance: setClearance failed", error)
            }
        }

        // The family is applied per attempt by HotaudioStreamer; doing it here
        // too would race with an active stream and override its fallback.
        HotaudioLog.i("applyClearance: saved IP-family hint=${prefs.hotaudioIpFamily()}")
    }

    fun isTlsReady(): Boolean = tlsReady
}
