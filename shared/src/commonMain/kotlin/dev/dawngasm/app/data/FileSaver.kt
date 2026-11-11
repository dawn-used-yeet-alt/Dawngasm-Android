package dev.dawngasm.app.data

interface FileSaver {
    fun folderLabel(treeUri: String?): String
    fun exists(localUri: String?): Boolean
    suspend fun save(fileName: String, bytes: ByteArray, treeUri: String? = null): String
}
