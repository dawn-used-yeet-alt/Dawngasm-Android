package dev.dawngasm.app.data

import com.russhwolf.settings.NSUserDefaultsSettings
import platform.Foundation.NSUserDefaults

fun AppPreferences(): AppPreferences {
    return AppPreferences(NSUserDefaultsSettings(NSUserDefaults.standardUserDefaults))
}
