package dev.dawngasm.app.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.dawngasm.app.util.PlatformLog
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

actual object PlatformSecureStorage : SecureStorage {
    private const val PREFS_NAME = "dawngasm_secure_vault"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "dawngasm_sync_vault_aes_v1"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    private val lock = Any()

    @Volatile
    private var prefs: SharedPreferences? = null

    private val memoryFallback = mutableMapOf<String, String>()

    fun init(context: Context) {
        synchronized(lock) {
            if (prefs == null) {
                prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
        }
    }

    actual override fun getString(key: String): String? = synchronized(lock) {
        val sp = prefs ?: return memoryFallback[key]
        val encoded = sp.getString(key, null) ?: return memoryFallback[key]
        return runCatching {
            val raw = Base64.decode(encoded, Base64.NO_WRAP)
            if (raw.size <= GCM_IV_BYTES) return null
            val iv = raw.copyOfRange(0, GCM_IV_BYTES)
            val ct = raw.copyOfRange(GCM_IV_BYTES, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ct).decodeToString()
        }.getOrElse { error ->
            PlatformLog.w("SecureStorage", "Failed to decrypt key $key: ${error.message}")
            memoryFallback[key]
        }
    }

    actual override fun putString(key: String, value: String) {
        synchronized(lock) {
            val sp = prefs
            if (sp == null) {
                memoryFallback[key] = value
                return
            }
            val encrypted = runCatching {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
                val iv = cipher.iv
                val ct = cipher.doFinal(value.encodeToByteArray())
                val combined = ByteArray(iv.size + ct.size)
                iv.copyInto(combined, 0)
                ct.copyInto(combined, iv.size)
                Base64.encodeToString(combined, Base64.NO_WRAP)
            }.getOrNull()

            if (encrypted != null) {
                sp.edit().putString(key, encrypted).apply()
                memoryFallback.remove(key)
            } else {
                // Keep in-memory only rather than writing plaintext to SharedPreferences
                memoryFallback[key] = value
            }
        }
    }

    actual override fun remove(key: String) {
        synchronized(lock) {
            memoryFallback.remove(key)
            prefs?.edit()?.remove(key)?.apply()
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }
}
