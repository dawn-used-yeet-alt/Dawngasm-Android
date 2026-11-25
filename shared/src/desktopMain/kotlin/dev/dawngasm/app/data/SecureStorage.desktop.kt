package dev.dawngasm.app.data

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

actual object PlatformSecureStorage : SecureStorage {
    private val lock = Any()
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    private fun vaultFile(): Path = DesktopPlatform.configDir.resolve("vault.json")

    actual override fun getString(key: String): String? = synchronized(lock) {
        readMap()[key]
    }

    actual override fun putString(key: String, value: String) {
        synchronized(lock) {
            val map = readMap().toMutableMap()
            map[key] = value
            writeMap(map)
        }
    }

    actual override fun remove(key: String) {
        synchronized(lock) {
            val map = readMap().toMutableMap()
            if (map.remove(key) != null) {
                writeMap(map)
            }
        }
    }

    private fun readMap(): Map<String, String> {
        val file = vaultFile()
        if (!Files.exists(file)) return emptyMap()
        return runCatching {
            val text = Files.readString(file, Charsets.UTF_8)
            if (text.isBlank()) emptyMap() else Json.decodeFromString(serializer, text)
        }.getOrDefault(emptyMap())
    }

    private fun writeMap(map: Map<String, String>) {
        val file = vaultFile()
        DesktopFs.writeTextAtomic(file, Json.encodeToString(serializer, map))
        runCatching {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        }
    }
}
