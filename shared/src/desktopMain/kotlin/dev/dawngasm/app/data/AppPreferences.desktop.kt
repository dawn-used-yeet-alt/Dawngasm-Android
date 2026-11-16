package dev.dawngasm.app.data

private val defaultSettings by lazy { DesktopSettings() }

/** Desktop factory mirroring `AppPreferences(context)` on Android. */
fun AppPreferences(): AppPreferences = AppPreferences(defaultSettings)
