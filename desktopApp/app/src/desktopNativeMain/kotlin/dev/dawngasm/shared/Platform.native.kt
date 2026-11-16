package dev.dawngasm.shared

import dev.dawngasm.app.data.DesktopPlatform
import dev.dawngasm.app.util.PlatformLog

actual val platformName: String
    get() = DesktopPlatform.displayName

actual fun logInfo(message: String) {
    PlatformLog.i("Dawngasm", message)
}
