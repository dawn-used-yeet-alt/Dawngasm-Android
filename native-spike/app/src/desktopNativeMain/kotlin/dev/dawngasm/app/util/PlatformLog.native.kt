package dev.dawngasm.app.util

actual object PlatformLog {
    actual fun d(tag: String, message: String) {
        println("[D/$tag] $message")
    }

    actual fun i(tag: String, message: String) {
        println("[I/$tag] $message")
    }

    actual fun w(tag: String, message: String, throwable: Throwable?) {
        val detail = throwable?.let { " - ${it.message}" } ?: ""
        println("[W/$tag] $message$detail")
    }

    actual fun e(tag: String, message: String, throwable: Throwable?) {
        val detail = throwable?.let { " - ${it.message}" } ?: ""
        println("[E/$tag] $message$detail")
    }
}
