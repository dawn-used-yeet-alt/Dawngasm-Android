package dev.dawngasm.app.data

class NativeFileSaver : FileSaver {
    override fun folderLabel(treeUri: String?): String =
        resolveDirectory(treeUri)

    override fun exists(localUri: String?): Boolean {
        if (localUri.isNullOrBlank()) return false
        val path = localUri.removePrefix("file://")
        return PlatformFileOps.fileExists(path)
    }

    override suspend fun save(fileName: String, bytes: ByteArray, treeUri: String?): String {
        val directory = resolveDirectory(treeUri)
        PlatformFileOps.ensureDir(directory)
        val target = "$directory/$fileName"
        PlatformFileOps.writeBytes(target, bytes)
        return "file://$target"
    }

    companion object {
        fun resolveDirectory(treeUri: String?): String {
            if (treeUri.isNullOrBlank()) return DesktopPlatform.downloadDir
            return treeUri.removePrefix("file://").ifBlank { DesktopPlatform.downloadDir }
        }
    }
}
