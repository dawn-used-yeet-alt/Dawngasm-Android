package dev.dawngasm.app.data

expect object PlatformFileOps {
    fun getCacheDir(): String
    fun fileExists(path: String): Boolean
    fun fileLength(path: String): Long
    fun deleteFile(path: String): Boolean
    fun writeText(path: String, text: String)
    fun readText(path: String): String?
    fun ensureDir(path: String)
}
