package dev.dawngasm.app.data

import android.content.Context
import com.russhwolf.settings.SharedPreferencesSettings

fun AppPreferences(context: Context): AppPreferences {
    val prefs = context.getSharedPreferences("dawngasm", Context.MODE_PRIVATE)
    return AppPreferences(SharedPreferencesSettings(prefs))
}
