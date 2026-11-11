package dev.dawngasm.shared

/** Human-readable name of the platform this code is running on. */
expect val platformName: String

/** Platform logging sink. Android uses logcat; iOS uses NSLog. */
expect fun logInfo(message: String)
