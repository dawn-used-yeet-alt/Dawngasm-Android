package dev.dawngasm.app.data

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * Saves downloaded audio to `Music/Dawngasm` or a user-selected directory.
 * Phase 6 adds streaming downloads; Phase 12 hardens cross-OS file names.
 */
class DesktopFileSaver : FileSaver {
    override fun folderLabel(treeUri: String?): String {
        val dir = resolveDirectory(treeUri)
        return dir.toString()
    }

    override fun exists(localUri: String?): Boolean {
        if (localUri.isNullOrBlank()) return false
        return runCatching { Files.exists(toPath(localUri)) }.getOrDefault(false)
    }

    override suspend fun save(fileName: String, bytes: ByteArray, treeUri: String?): String {
        val directory = resolveDirectory(treeUri)
        Files.createDirectories(directory)
        val target = directory.resolve(fileName)
        DesktopFs.writeBytesAtomic(target, bytes)
        return target.toUri().toString()
    }

    private fun resolveDirectory(treeUri: String?): Path {
        if (treeUri.isNullOrBlank()) return DesktopPlatform.downloadDir
        return runCatching { toPath(treeUri) }
            .getOrElse { DesktopPlatform.downloadDir }
    }

    private fun toPath(value: String): Path =
        if (value.startsWith("file:", ignoreCase = true)) {
            Path.of(URI(value))
        } else {
            Path.of(value)
        }
}
