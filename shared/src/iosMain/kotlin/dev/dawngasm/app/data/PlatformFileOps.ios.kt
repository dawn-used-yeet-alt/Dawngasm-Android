package dev.dawngasm.app.data

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithBytes
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

@OptIn(ExperimentalForeignApi::class)
actual object PlatformFileOps {
    actual fun getCacheDir(): String {
        val paths = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
        return (paths.firstOrNull() as? String) ?: NSTemporaryDirectory()
    }

    actual fun fileExists(path: String): Boolean =
        NSFileManager.defaultManager.fileExistsAtPath(path)

    actual fun fileLength(path: String): Long {
        val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, null) ?: return 0L
        val size = attrs["NSFileSize"] as? NSNumber
        return size?.longLongValue ?: 0L
    }

    actual fun deleteFile(path: String): Boolean =
        NSFileManager.defaultManager.removeItemAtPath(path, null)

    actual fun writeText(path: String, text: String) {
        val parent = path.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) {
            ensureDir(parent)
        }
        val bytes = text.encodeToByteArray()
        val nsData = if (bytes.isNotEmpty()) {
            bytes.usePinned { pinned ->
                NSData.dataWithBytes(pinned.addressOf(0), bytes.size.toULong())
            }
        } else {
            NSData()
        }
        nsData.writeToFile(path, atomically = true)
    }

    actual fun readText(path: String): String? =
        NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)

    actual fun readBytes(path: String): ByteArray? {
        val file = platform.posix.fopen(path, "rb") ?: return null
        try {
            platform.posix.fseek(file, 0, platform.posix.SEEK_END)
            val size = platform.posix.ftell(file).toInt()
            platform.posix.fseek(file, 0, platform.posix.SEEK_SET)
            if (size <= 0) return ByteArray(0)
            val bytes = ByteArray(size)
            bytes.usePinned { pinned ->
                platform.posix.fread(pinned.addressOf(0), 1.toULong(), size.toULong(), file)
            }
            return bytes
        } finally {
            platform.posix.fclose(file)
        }
    }

    actual fun writeBytes(path: String, bytes: ByteArray): Boolean {
        val parent = path.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) {
            ensureDir(parent)
        }
        val nsData = if (bytes.isNotEmpty()) {
            bytes.usePinned { pinned ->
                NSData.dataWithBytes(pinned.addressOf(0), bytes.size.toULong())
            }
        } else {
            NSData()
        }
        return nsData.writeToFile(path, atomically = true)
    }

    actual fun ensureDir(path: String) {
        NSFileManager.defaultManager.createDirectoryAtPath(path, true, null, null)
    }
}
