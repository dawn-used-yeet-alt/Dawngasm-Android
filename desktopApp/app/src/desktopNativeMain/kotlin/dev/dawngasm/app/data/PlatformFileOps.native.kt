package dev.dawngasm.app.data

import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer

actual object PlatformFileOps {
    actual fun getCacheDir(): String = DesktopPlatform.cacheDir

    actual fun getDataDir(): String = DesktopPlatform.configDir

    actual fun fileExists(path: String): Boolean =
        FileSystem.SYSTEM.exists(path.toPath())

    actual fun fileLength(path: String): Long =
        FileSystem.SYSTEM.metadataOrNull(path.toPath())?.size ?: 0L

    actual fun deleteFile(path: String): Boolean = runCatching {
        FileSystem.SYSTEM.delete(path.toPath())
        true
    }.getOrDefault(false)

    actual fun writeText(path: String, text: String) {
        val p = path.toPath()
        p.parent?.let { FileSystem.SYSTEM.createDirectories(it) }
        val tmp = "$path.tmp".toPath()
        FileSystem.SYSTEM.write(tmp) {
            writeUtf8(text)
        }
        FileSystem.SYSTEM.atomicMove(tmp, p)
    }

    actual fun appendText(path: String, text: String) {
        val p = path.toPath()
        p.parent?.let { FileSystem.SYSTEM.createDirectories(it) }
        FileSystem.SYSTEM.appendingSink(p).buffer().use { sink ->
            sink.writeUtf8(text)
        }
    }

    actual fun readText(path: String): String? = runCatching {
        val p = path.toPath()
        if (!FileSystem.SYSTEM.exists(p)) return null
        FileSystem.SYSTEM.read(p) {
            readUtf8()
        }
    }.getOrNull()

    actual fun readBytes(path: String): ByteArray? = runCatching {
        val p = path.toPath()
        if (!FileSystem.SYSTEM.exists(p)) return null
        FileSystem.SYSTEM.read(p) {
            readByteArray()
        }
    }.getOrNull()

    actual fun writeBytes(path: String, bytes: ByteArray): Boolean = runCatching {
        val p = path.toPath()
        p.parent?.let { FileSystem.SYSTEM.createDirectories(it) }
        FileSystem.SYSTEM.write(p) {
            write(bytes)
        }
        true
    }.getOrDefault(false)

    actual fun ensureDir(path: String) {
        FileSystem.SYSTEM.createDirectories(path.toPath())
    }
}
