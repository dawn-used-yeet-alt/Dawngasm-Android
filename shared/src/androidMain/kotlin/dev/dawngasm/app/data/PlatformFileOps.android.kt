package dev.dawngasm.app.data

import java.io.File

actual object PlatformFileOps {
    @Volatile
    private var customCacheDir: String? = null

    fun setCacheDir(dir: String) {
        customCacheDir = dir
    }

    actual fun getCacheDir(): String =
        customCacheDir ?: System.getProperty("java.io.tmpdir") ?: "/tmp"

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

    actual fun ensureDir(path: String) {
        File(path).mkdirs()
    }
}
