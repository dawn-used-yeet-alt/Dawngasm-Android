package dev.dawngasm.app.data

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
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
        val nsStr = text as NSString
        val parent = (path as NSString).stringByDeletingLastPathComponent
        ensureDir(parent)
        nsStr.writeToFile(path, true, NSUTF8StringEncoding, null)
    }

    actual fun readText(path: String): String? =
        NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) as? String

    actual fun ensureDir(path: String) {
        NSFileManager.defaultManager.createDirectoryAtPath(path, true, null, null)
    }
}
