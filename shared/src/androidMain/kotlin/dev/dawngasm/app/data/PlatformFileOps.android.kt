package dev.dawngasm.app.data

import java.io.File

actual object PlatformFileOps {
    @Volatile
    private var customCacheDir: String? = null

    @Volatile
    private var customDataDir: String? = null

    fun setCacheDir(dir: String) {
        customCacheDir = dir
    }

    fun setDataDir(dir: String) {
        customDataDir = dir
    }

    actual fun getCacheDir(): String =
        customCacheDir ?: System.getProperty("java.io.tmpdir") ?: "/tmp"

    actual fun getDataDir(): String =
        customDataDir ?: customCacheDir ?: System.getProperty("java.io.tmpdir") ?: "/tmp"

    actual fun fileExists(path: String): Boolean = File(path).exists()

    actual fun fileLength(path: String): Long =
        File(path).takeIf { it.exists() }?.length() ?: 0L

    actual fun deleteFile(path: String): Boolean = File(path).delete()

    actual fun writeText(path: String, text: String) {
        val file = File(path)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
    }

    actual fun appendText(path: String, text: String) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.appendText(text)
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
