package dev.dawngasm.app.data

import dev.dawngasm.app.util.PlatformLog

/**
 * One place for hotaudio diagnostics. Everything is tagged `Hotaudio` so a
 * single `adb logcat -s Hotaudio` shows the whole pipeline: TLS init, clearance,
 * IP-family attempts, download progress, and the streaming data source.
 */
object HotaudioLog {
    const val TAG = "Hotaudio"

    fun d(message: String) {
        PlatformLog.d(TAG, message)
    }

    fun i(message: String) {
        PlatformLog.i(TAG, message)
    }

    fun w(message: String, throwable: Throwable? = null) {
        PlatformLog.w(TAG, message, throwable)
    }

    fun e(message: String, throwable: Throwable? = null) {
        PlatformLog.e(TAG, message, throwable)
    }
}
