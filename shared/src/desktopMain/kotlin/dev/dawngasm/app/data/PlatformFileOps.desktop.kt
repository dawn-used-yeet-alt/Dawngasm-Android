package dev.dawngasm.app.data

import java.io.File

/**
 * Phase 0 placeholder. Phase 2 replaces this with the per-OS directory layout
 * from [dev.dawngasm.app.data.DesktopPlatform].
 */
actual object PlatformFileOps {
    actual fun getCacheDir(): String =
        System.getProperty("java.io.tmpdir") ?: "/tmp"

    actual fun fileExists(path: String): Boolean = File(path).exists()

    actual fun fileLength(path: String): Long =
        File(path).takeIf { it.exists() }?.length() ?: 0L

    actual fun deleteFile(path: String): Boolean = File(path).delete()

    actual fun writeText(path: String, text: String) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    actual fun readText(path: String): String? =
        File(path).takeIf { it.exists() }?.readText()

    actual fun readBytes(path: String): ByteArray? =
        File(path).takeIf { it.exists() }?.readBytes()

    actual fun writeBytes(path: String, bytes: ByteArray): Boolean =
        runCatching {
            val file = File(path)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            true
        }.getOrDefault(false)

    actual fun ensureDir(path: String) {
        File(path).mkdirs()
    }
}
