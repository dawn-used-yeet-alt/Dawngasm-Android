package dev.dawngasm.app.data

import android.util.Log

/**
 * One place for hotaudio diagnostics. Everything is tagged `Hotaudio` so a
 * single `adb logcat -s Hotaudio` shows the whole pipeline: TLS init, clearance,
 * IP-family attempts, download progress, and the streaming data source.
 */
object HotaudioLog {
    const val TAG = "Hotaudio"

    fun d(message: String) {
        Log.d(TAG, message)
    }

    fun i(message: String) {
        Log.i(TAG, message)
    }

    fun w(message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.w(TAG, message, throwable) else Log.w(TAG, message)
    }

    fun e(message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.e(TAG, message, throwable) else Log.e(TAG, message)
    }
}
