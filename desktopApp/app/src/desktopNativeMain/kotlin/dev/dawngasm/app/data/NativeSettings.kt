package dev.dawngasm.app.data

import com.russhwolf.settings.Settings
import dev.dawngasm.app.util.PlatformLog
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class NativeSettings(
    private val filePath: String = DesktopPlatform.settingsFile,
) : Settings {
    private val values: MutableMap<String, String> = readFile()

    override val keys: Set<String>
        get() = values.keys.toSet()

    override val size: Int
        get() = values.size

    override fun clear() {
        values.clear()
        persist()
    }

    override fun remove(key: String) {
        values.remove(key)
        persist()
    }

    override fun hasKey(key: String): Boolean = values.containsKey(key)

    override fun putInt(key: String, value: Int) = putString(key, value.toString())

    override fun getInt(key: String, defaultValue: Int): Int =
        values[key]?.toIntOrNull() ?: defaultValue

    override fun getIntOrNull(key: String): Int? = values[key]?.toIntOrNull()

    override fun putLong(key: String, value: Long) = putString(key, value.toString())

    override fun getLong(key: String, defaultValue: Long): Long =
        values[key]?.toLongOrNull() ?: defaultValue

    override fun getLongOrNull(key: String): Long? = values[key]?.toLongOrNull()

    override fun putString(key: String, value: String) {
        values[key] = value
        persist()
    }

    override fun getString(key: String, defaultValue: String): String =
        values[key] ?: defaultValue

    override fun getStringOrNull(key: String): String? = values[key]

    override fun putFloat(key: String, value: Float) = putString(key, value.toString())

    override fun getFloat(key: String, defaultValue: Float): Float =
        values[key]?.toFloatOrNull() ?: defaultValue

    override fun getFloatOrNull(key: String): Float? = values[key]?.toFloatOrNull()

    override fun putDouble(key: String, value: Double) = putString(key, value.toString())

    override fun getDouble(key: String, defaultValue: Double): Double =
        values[key]?.toDoubleOrNull() ?: defaultValue

    override fun getDoubleOrNull(key: String): Double? = values[key]?.toDoubleOrNull()

    override fun putBoolean(key: String, value: Boolean) = putString(key, value.toString())

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        values[key]?.toBooleanStrictOrNull() ?: defaultValue

    override fun getBooleanOrNull(key: String): Boolean? =
        values[key]?.toBooleanStrictOrNull()

    private fun readFile(): MutableMap<String, String> {
        val text = PlatformFileOps.readText(filePath) ?: return mutableMapOf()
        if (text.isBlank()) return mutableMapOf()
        return runCatching {
            Json.decodeFromString(SERIALIZER, text).toMutableMap()
        }.getOrElse { error ->
            PlatformLog.w("NativeSettings", "could not read $filePath: ${error.message}")
            mutableMapOf()
        }
    }

    private fun persist() {
        runCatching {
            PlatformFileOps.writeText(filePath, Json.encodeToString(SERIALIZER, values))
        }.onFailure { error ->
            PlatformLog.w("NativeSettings", "could not write $filePath: ${error.message}")
        }
    }

    private companion object {
        val SERIALIZER = MapSerializer(String.serializer(), String.serializer())
    }
}
