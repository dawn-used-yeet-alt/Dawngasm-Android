package dev.dawngasm.shared

import android.os.Build
import android.util.Log

actual val platformName: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

actual fun logInfo(message: String) {
    Log.i("Dawngasm", message)
}
