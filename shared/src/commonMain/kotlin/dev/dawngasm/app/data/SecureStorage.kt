package dev.dawngasm.app.data

/**
 * Platform-backed secure key-value storage for sync secrets (cached vault key
 * and remote auth tokens). Never falls back to plain `AppPreferences` or
 * synced payloads.
 */
interface SecureStorage {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

expect object PlatformSecureStorage : SecureStorage {
    override fun getString(key: String): String?
    override fun putString(key: String, value: String)
    override fun remove(key: String)
}
