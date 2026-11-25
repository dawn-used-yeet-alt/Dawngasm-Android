package dev.dawngasm.app.data

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

actual object PlatformFileOps {
    actual fun getCacheDir(): String {
        val dir = DesktopPlatform.cacheDir
        runCatching { Files.createDirectories(dir) }
        return dir.toString()
    }

    actual fun getDataDir(): String {
        val dir = DesktopPlatform.configDir
        runCatching { Files.createDirectories(dir) }
        return dir.toString()
    }

    actual fun fileExists(path: String): Boolean =
        runCatching { Files.exists(Path.of(path)) }.getOrDefault(false)

    actual fun fileLength(path: String): Long =
        runCatching { Files.size(Path.of(path)) }.getOrDefault(0L)

    actual fun deleteFile(path: String): Boolean =
        runCatching { Files.deleteIfExists(Path.of(path)) }.getOrDefault(false)

    actual fun writeText(path: String, text: String) {
        DesktopFs.writeTextAtomic(Path.of(path), text)
    }

    actual fun appendText(path: String, text: String) {
        val file = Path.of(path)
        file.parent?.let { Files.createDirectories(it) }
        Files.writeString(
            file,
            text,
            Charsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
            StandardOpenOption.WRITE,
        )
    }

    actual fun readText(path: String): String? =
        runCatching {
            val file = Path.of(path)
            if (Files.exists(file)) Files.readString(file, Charsets.UTF_8) else null
        }.getOrNull()

    actual fun readBytes(path: String): ByteArray? =
        runCatching {
            val file = Path.of(path)
            if (Files.exists(file)) Files.readAllBytes(file) else null
        }.getOrNull()

    actual fun writeBytes(path: String, bytes: ByteArray): Boolean =
        runCatching {
            DesktopFs.writeBytesAtomic(Path.of(path), bytes)
            true
        }.getOrDefault(false)

    actual fun ensureDir(path: String) {
        runCatching { Files.createDirectories(Path.of(path)) }
    }

    /** Append bytes to a growing file; used by streaming downloads in Phase 6. */
    internal fun appendBytes(path: String, bytes: ByteArray) {
        val file = Path.of(path)
        file.parent?.let { Files.createDirectories(it) }
        Files.write(
            file,
            bytes,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
            StandardOpenOption.WRITE,
        )
    }
}
