package dev.dawngasm.app.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CryptoAndCompressionTest {

    @Test
    fun blake2bMatchesRfc7693EmptyVector() {
        val digest = Blake2b.hash(ByteArray(0), outLen = 64)
        assertEquals(
            "786a02f742015903c6c6fd852552d272912f4740e15847618a86e217f71f5419" +
                "d25e1031afee585313896444934eb04b903a685b1448b755d56f701afe9be2ce",
            digest.toHex(),
        )
    }

    @Test
    fun argon2idMatchesRfc9106TestVector() {
        val password = ByteArray(32) { 0x01 }
        val salt = ByteArray(16) { 0x02 }
        val secret = ByteArray(8) { 0x03 }
        val ad = ByteArray(12) { 0x04 }

        val derived = Argon2id.deriveKey(
            password = password,
            salt = salt,
            outputLength = 32,
            memoryKiB = 32,
            iterations = 3,
            parallelism = 4,
            secret = secret,
            associatedData = ad,
        )
        assertEquals(
            "0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659",
            derived.toHex(),
        )
    }

    @Test
    fun hChaCha20MatchesDraftVector() {
        val key = hexToBytes("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
        val nonce = hexToBytes("000000090000004a0000000031415927")
        val subkey = XChaCha20Poly1305.hChaCha20(key, nonce)
        assertEquals(
            "82413b4227b27bfed30e42508a877d73a0f9e4d58a74a853c12ec41326d3ecdc",
            subkey.toHex(),
        )
    }

    @Test
    fun xChaCha20Poly1305RoundTripAndTamperDetection() {
        val key = ByteArray(32) { (it + 1).toByte() }
        val nonce = ByteArray(24) { (it * 3).toByte() }
        val aad = "header-aad".encodeToByteArray()
        val plaintext = "Secret Dawngasm payload with unicode 🎧".encodeToByteArray()

        val sealed = XChaCha20Poly1305.encrypt(key, nonce, plaintext, aad)
        val opened = XChaCha20Poly1305.decrypt(key, nonce, sealed, aad)
        assertContentEquals(plaintext, opened)

        // Tamper ciphertext
        val badCt = sealed.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
        assertNull(XChaCha20Poly1305.decrypt(key, nonce, badCt, aad))

        // Tamper AAD
        assertNull(XChaCha20Poly1305.decrypt(key, nonce, sealed, "wrong-aad".encodeToByteArray()))
    }

    @Test
    fun deflateRoundTripsEmptyRepetitiveAndRandomPayloads() {
        val empty = ByteArray(0)
        assertContentEquals(empty, DeflateCodec.decompress(DeflateCodec.compress(empty)))

        val jsonLike = """{"tracks":[""" +
            (1..80).joinToString(",") { """{"id":"t$it","title":"Rainy Night $it","host":"soundgasm"}""" } +
            "]}"
        val raw = jsonLike.encodeToByteArray()
        val deflated = DeflateCodec.compress(raw)
        assertTrue(deflated.size < raw.size, "Repetitive JSON should compress (${deflated.size} vs ${raw.size})")
        assertContentEquals(raw, DeflateCodec.decompress(deflated))

        // Incompressible pseudo-random bytes should fall back cleanly to stored blocks
        val incompressible = ByteArray(512) { ((it * 131 + 17) xor (it ushr 2)).toByte() }
        assertContentEquals(incompressible, DeflateCodec.decompress(DeflateCodec.compress(incompressible)))
    }

    @Test
    fun backupCryptoProducesPureAsciiAndVerifiesIntegrity() {
        val params = BackupKdfParams(memoryKiB = 64, iterations = 1, parallelism = 1)
        val json = """{"saved":[{"id":"1","title":"Test Track"}],"sort":{"value":"top","updatedAtMs":100}}"""
        val encrypted = BackupCrypto.encrypt(json, "hunter2-passphrase", params = params)

        assertTrue(encrypted.startsWith(BackupCrypto.PREFIX))
        val body = encrypted.removePrefix(BackupCrypto.PREFIX)
        assertTrue(body.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' })

        val decrypted = BackupCrypto.decrypt(encrypted, "hunter2-passphrase")
        assertEquals(json, decrypted.plaintextJson)

        // Fresh nonce produces distinct ciphertext for same key & salt
        val encrypted2 = BackupCrypto.encryptWithKey(json, decrypted.derivedKey, decrypted.header.salt, params)
        assertNotEquals(encrypted, encrypted2)
        assertEquals(json, BackupCrypto.decryptWithKey(encrypted2, decrypted.derivedKey).plaintextJson)

        // Wrong password fails
        assertFailsWith<BackupCryptoException> {
            BackupCrypto.decrypt(encrypted, "wrong-password")
        }

        // Corrupted payload fails
        val corrupted = encrypted.dropLast(4) + "AAAA"
        assertFailsWith<BackupCryptoException> {
            BackupCrypto.decrypt(corrupted, "hunter2-passphrase")
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") {
        ((it.toInt() and 0xFF) or 0x100).toString(16).substring(1)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }
}
