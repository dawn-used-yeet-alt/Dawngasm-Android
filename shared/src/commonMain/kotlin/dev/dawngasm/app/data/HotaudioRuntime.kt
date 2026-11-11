package dev.dawngasm.app.data

import dev.dawngasm.app.util.PlatformLog
import kotlin.concurrent.Volatile

object HotaudioRuntime {
    @Volatile
    var tlsError: Throwable? = null

    @Volatile
    var tlsReady: Boolean = false

    var onInitialize: ((Any?) -> Unit)? = null

    fun initialize(context: Any? = null) {
        onInitialize?.invoke(context)
        val prefs = when (context) {
            is AppPreferences -> context
            else -> null
        }
        applyClearance(prefs)
    }

    fun applyClearance(prefs: AppPreferences?) {
        if (prefs == null) {
            PlatformLog.w("HotaudioRuntime", "applyClearance: no preferences")
            return
        }

        val clearance = prefs.hotaudioClearance()
        if (clearance == null) {
            PlatformLog.i("HotaudioRuntime", "applyClearance: no saved cf_clearance")
        } else {
            try {
                HotaudioBridgeProvider.bridge?.setClearance(clearance.first, clearance.second)
                PlatformLog.i(
                    "HotaudioRuntime",
                    "applyClearance: OK cookieLen=${clearance.first.length} " +
                        "ua=${clearance.second.take(80)}",
                )
            } catch (error: Throwable) {
                PlatformLog.e("HotaudioRuntime", "applyClearance: setClearance failed", error)
            }
        }

        val family = prefs.hotaudioIpFamily()
        if (family != null) {
            try {
                HotaudioBridgeProvider.bridge?.setIpFamily(family)
            } catch (error: Throwable) {
                PlatformLog.e("HotaudioRuntime", "applyClearance: setIpFamily failed", error)
            }
        }
        PlatformLog.i("HotaudioRuntime", "applyClearance: saved IP-family hint=$family")
    }

    fun isTlsReady(): Boolean = tlsReady
}
