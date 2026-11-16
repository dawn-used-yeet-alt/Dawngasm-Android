package dev.dawngasm.app.util

/**
 * Phase 0 placeholder. Phase 2 routes these calls to a rotating file under the
 * per-OS config directory while still echoing to stdout for terminal runs.
 */
actual object PlatformLog {
    actual fun d(tag: String, message: String) {
        println("DEBUG [$tag] $message")
    }

    actual fun i(tag: String, message: String) {
        println("INFO [$tag] $message")
    }

    actual fun w(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) {
            println("WARN [$tag] $message: ${throwable.message}")
            throwable.printStackTrace()
        } else {
            println("WARN [$tag] $message")
        }
    }

    actual fun e(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) {
            System.err.println("ERROR [$tag] $message: ${throwable.message}")
            throwable.printStackTrace()
        } else {
            System.err.println("ERROR [$tag] $message")
        }
    }
}
