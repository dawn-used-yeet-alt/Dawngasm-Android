package dev.dawngasm.app.data

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@OptIn(ExperimentalForeignApi::class)
actual object PlatformSecureStorage : SecureStorage {
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    private fun vaultPath(): String = "${DesktopPlatform.configDir}/vault.json"

    actual override fun getString(key: String): String? = readMap()[key]

    actual override fun putString(key: String, value: String) {
        val map = readMap().toMutableMap()
        map[key] = value
        writeMap(map)
    }

    actual override fun remove(key: String) {
        val map = readMap().toMutableMap()
        if (map.remove(key) != null) {
            writeMap(map)
        }
    }

    private fun readMap(): Map<String, String> {
        val text = PlatformFileOps.readText(vaultPath()) ?: return emptyMap()
        if (text.isBlank()) return emptyMap()
        return runCatching { Json.decodeFromString(serializer, text) }.getOrDefault(emptyMap())
    }

    private fun writeMap(map: Map<String, String>) {
        val path = vaultPath()
        PlatformFileOps.writeText(path, Json.encodeToString(serializer, map))
        runCatching {
            platform.posix.chmod(path, 384.convert()) // 0600
        }
    }
}
