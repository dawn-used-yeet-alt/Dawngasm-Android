package dev.dawngasm.shared

import platform.Foundation.NSLog
import platform.UIKit.UIDevice

actual val platformName: String =
    "${UIDevice.currentDevice.systemName} ${UIDevice.currentDevice.systemVersion}"

actual fun logInfo(message: String) {
    NSLog("%@", message)
}
