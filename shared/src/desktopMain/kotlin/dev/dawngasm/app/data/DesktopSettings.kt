package dev.dawngasm.app.data

import com.russhwolf.settings.Settings
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * A small, file-backed [Settings] implementation.
 *
 * Values are stored as a UTF-8 JSON object of string values. Every write goes
 * through a temp file and an atomic move, so a crash cannot leave a truncated
 * settings file. This deliberately avoids `java.util.prefs`, which has an
 * 8 KB value limit and uses the Windows registry.
 */
class DesktopSettings(
    private val file: Path = DesktopPlatform.settingsFile,
) : Settings {
    private val lock = Any()
    private val values: MutableMap<String, String> = readFile()

    override val keys: Set<String>
        get() = synchronized(lock) { values.keys.toSet() }

    override val size: Int
        get() = synchronized(lock) { values.size }

    override fun clear() {
        synchronized(lock) {
            values.clear()
            persist()
        }
    }

    override fun remove(key: String) {
        synchronized(lock) {
            values.remove(key)
            persist()
        }
    }

    override fun hasKey(key: String): Boolean = synchronized(lock) { values.containsKey(key) }

    override fun putInt(key: String, value: Int) = putString(key, value.toString())

    override fun getInt(key: String, defaultValue: Int): Int =
        synchronized(lock) { values[key]?.toIntOrNull() ?: defaultValue }

    override fun getIntOrNull(key: String): Int? = synchronized(lock) { values[key]?.toIntOrNull() }

    override fun putLong(key: String, value: Long) = putString(key, value.toString())

    override fun getLong(key: String, defaultValue: Long): Long =
        synchronized(lock) { values[key]?.toLongOrNull() ?: defaultValue }

    override fun getLongOrNull(key: String): Long? = synchronized(lock) { values[key]?.toLongOrNull() }

    override fun putString(key: String, value: String) {
        synchronized(lock) {
            values[key] = value
            persist()
        }
    }

    override fun getString(key: String, defaultValue: String): String =
        synchronized(lock) { values[key] ?: defaultValue }

    override fun getStringOrNull(key: String): String? = synchronized(lock) { values[key] }

    override fun putFloat(key: String, value: Float) = putString(key, value.toString())

    override fun getFloat(key: String, defaultValue: Float): Float =
        synchronized(lock) { values[key]?.toFloatOrNull() ?: defaultValue }

    override fun getFloatOrNull(key: String): Float? = synchronized(lock) { values[key]?.toFloatOrNull() }

    override fun putDouble(key: String, value: Double) = putString(key, value.toString())

    override fun getDouble(key: String, defaultValue: Double): Double =
        synchronized(lock) { values[key]?.toDoubleOrNull() ?: defaultValue }

    override fun getDoubleOrNull(key: String): Double? = synchronized(lock) { values[key]?.toDoubleOrNull() }

    override fun putBoolean(key: String, value: Boolean) = putString(key, value.toString())

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        synchronized(lock) { values[key]?.toBooleanStrictOrNull() ?: defaultValue }

    override fun getBooleanOrNull(key: String): Boolean? =
        synchronized(lock) { values[key]?.toBooleanStrictOrNull() }

    private fun readFile(): MutableMap<String, String> {
        if (!Files.exists(file)) return mutableMapOf()
        return runCatching {
            val text = Files.readString(file, Charsets.UTF_8)
            if (text.isBlank()) {
                mutableMapOf()
            } else {
                Json.decodeFromString(SERIALIZER, text).toMutableMap()
            }
        }.getOrElse { error ->
            System.err.println("DesktopSettings: could not read $file: ${error.message}")
            mutableMapOf()
        }
    }

    private fun persist() {
        runCatching {
            DesktopFs.writeTextAtomic(file, Json.encodeToString(SERIALIZER, values))
        }.onFailure { error ->
            System.err.println("DesktopSettings: could not write $file: ${error.message}")
        }
    }

    private companion object {
        val SERIALIZER = MapSerializer(String.serializer(), String.serializer())
    }
}
