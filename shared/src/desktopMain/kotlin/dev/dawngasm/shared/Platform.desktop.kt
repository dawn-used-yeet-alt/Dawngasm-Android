package dev.dawngasm.shared

actual val platformName: String
    get() = buildString {
        append(System.getProperty("os.name").orEmpty().ifBlank { "Desktop" })
        val version = System.getProperty("os.version").orEmpty()
        if (version.isNotBlank()) append(" $version")
        append(" (")
        append(System.getProperty("os.arch").orEmpty().ifBlank { "unknown" })
        append(")")
    }

actual fun logInfo(message: String) {
    println("[Dawngasm] $message")
}
