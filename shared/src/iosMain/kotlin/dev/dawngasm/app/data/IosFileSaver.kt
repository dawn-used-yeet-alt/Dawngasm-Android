package dev.dawngasm.app.data

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithBytes
import platform.Foundation.writeToFile

class IosFileSaver : FileSaver {
    override fun folderLabel(treeUri: String?): String = "App Documents"

    override fun exists(localUri: String?): Boolean {
        if (localUri.isNullOrBlank()) return false
        val path = if (localUri.startsWith("file://")) {
            localUri.removePrefix("file://")
        } else {
            localUri
        }
        return NSFileManager.defaultManager.fileExistsAtPath(path)
    }

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun save(fileName: String, bytes: ByteArray, treeUri: String?): String {
        val paths = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
        val docsDir = paths.firstOrNull() as? String
            ?: throw IllegalStateException("Could not locate Documents directory")
        val filePath = "$docsDir/$fileName"

        val nsData = if (bytes.isNotEmpty()) {
            bytes.usePinned { pinned ->
                NSData.dataWithBytes(pinned.addressOf(0), bytes.size.toULong())
            }
        } else {
            NSData()
        }

        val success = nsData.writeToFile(filePath, atomically = true)
        if (!success) {
            throw IllegalStateException("Failed to write file to $filePath")
        }
        return "file://$filePath"
    }
}
