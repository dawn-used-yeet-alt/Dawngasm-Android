package dev.dawngasm.app.util

import platform.Foundation.NSLog

actual object PlatformLog {
    actual fun d(tag: String, message: String) {
        NSLog("DEBUG [%s] %s", tag, message)
    }

    actual fun i(tag: String, message: String) {
        NSLog("INFO [%s] %s", tag, message)
    }

    actual fun w(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) {
            NSLog("WARN [%s] %s: %s", tag, message, throwable.message ?: "")
        } else {
            NSLog("WARN [%s] %s", tag, message)
        }
    }

    actual fun e(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) {
            NSLog("ERROR [%s] %s: %s", tag, message, throwable.message ?: "")
        } else {
            NSLog("ERROR [%s] %s", tag, message)
        }
    }
}
