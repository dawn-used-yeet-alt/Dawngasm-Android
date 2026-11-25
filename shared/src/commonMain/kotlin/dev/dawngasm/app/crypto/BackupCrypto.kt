package dev.dawngasm.app.crypto

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class BackupCryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class BackupKdfParams(
    val memoryKiB: Int = Argon2id.DEFAULT_MEMORY_KIB,
    val iterations: Int = Argon2id.DEFAULT_ITERATIONS,
    val parallelism: Int = Argon2id.DEFAULT_PARALLELISM,
) {
    init {
        require(parallelism in 1..255) { "parallelism must be 1..255" }
        require(iterations in 1..65535) { "iterations must be 1..65535" }
        require(memoryKiB >= 8 * parallelism) { "memoryKiB must be >= 8 * parallelism" }
    }
}

data class BackupEnvelopeHeader(
    val flags: Byte,
    val kdfParams: BackupKdfParams,
    val salt: ByteArray,
    val nonce: ByteArray,
    val headerBytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BackupEnvelopeHeader) return false
        return flags == other.flags &&
            kdfParams == other.kdfParams &&
            salt.contentEquals(other.salt) &&
            nonce.contentEquals(other.nonce) &&
            headerBytes.contentEquals(other.headerBytes)
    }

    override fun hashCode(): Int {
        var result = flags.toInt()
        result = 31 * result + kdfParams.hashCode()
        result = 31 * result + salt.contentHashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + headerBytes.contentHashCode()
        return result
    }
}

data class DecryptedBackupPayload(
    val plaintextJson: String,
    val header: BackupEnvelopeHeader,
    val derivedKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DecryptedBackupPayload) return false
        return plaintextJson == other.plaintextJson &&
            header == other.header &&
            derivedKey.contentEquals(other.derivedKey)
    }

    override fun hashCode(): Int {
        var result = plaintextJson.hashCode()
        result = 31 * result + header.hashCode()
        result = 31 * result + derivedKey.contentHashCode()
        return result
    }
}

/**
 * End-to-end backup encryption pipeline:
 *   JSON -> DEFLATE -> Argon2id KDF (16B salt) -> XChaCha20-Poly1305 AEAD (24B nonce, header as AAD)
 *   -> URL-safe Base64 ASCII text (`DAWNGASM-ENC-V1:...`).
 */
@OptIn(ExperimentalEncodingApi::class)
object BackupCrypto {
    const val PREFIX = "DAWNGASM-ENC-V1:"
    const val SALT_SIZE = 16
    const val NONCE_SIZE = 24
    const val KEY_SIZE = 32

    private const val HEADER_SIZE = 52
    private val MAGIC = byteArrayOf(0x44, 0x47, 0x45, 0x31) // "DGE1"
    private const val FLAG_DEFLATE: Byte = 0x01
    private const val FLAG_RAW: Byte = 0x00

    fun isEncryptedBackup(text: String): Boolean =
        text.trim().startsWith(PREFIX)

    fun deriveKey(
        password: String,
        salt: ByteArray,
        params: BackupKdfParams = BackupKdfParams(),
    ): ByteArray {
        if (password.isEmpty()) throw BackupCryptoException("Password cannot be empty")
        require(salt.size == SALT_SIZE) { "Salt must be $SALT_SIZE bytes" }
        return Argon2id.deriveKey(
            password = password.encodeToByteArray(),
            salt = salt,
            outputLength = KEY_SIZE,
            memoryKiB = params.memoryKiB,
            iterations = params.iterations,
            parallelism = params.parallelism,
        )
    }

    fun encrypt(
        jsonText: String,
        password: String,
        params: BackupKdfParams = BackupKdfParams(),
        salt: ByteArray = secureRandomBytes(SALT_SIZE),
    ): String {
        val key = deriveKey(password, salt, params)
        return encryptWithKey(jsonText, key, salt, params)
    }

    /**
     * Encrypts [jsonText] using a previously derived 32-byte key and its [salt].
     * Always generates a fresh 24-byte random nonce on every call.
     */
    fun encryptWithKey(
        jsonText: String,
        derivedKey: ByteArray,
        salt: ByteArray,
        params: BackupKdfParams = BackupKdfParams(),
    ): String {
        require(derivedKey.size == KEY_SIZE) { "Key must be $KEY_SIZE bytes" }
        require(salt.size == SALT_SIZE) { "Salt must be $SALT_SIZE bytes" }

        val compressed = DeflateCodec.compress(jsonText.encodeToByteArray())
        val nonce = secureRandomBytes(NONCE_SIZE)
        val header = buildHeader(FLAG_DEFLATE, params, salt, nonce)
        val ciphertextWithTag = XChaCha20Poly1305.encrypt(
            key = derivedKey,
            nonce = nonce,
            plaintext = compressed,
            aad = header,
        )
        val envelope = ByteArray(header.size + ciphertextWithTag.size)
        header.copyInto(envelope)
        ciphertextWithTag.copyInto(envelope, header.size)
        return PREFIX + encodeUrlSafeBase64(envelope)
    }

    fun inspectHeader(encryptedText: String): BackupEnvelopeHeader {
        val (header, _) = decodeEnvelope(encryptedText)
        return header
    }

    fun decrypt(
        encryptedText: String,
        password: String,
    ): DecryptedBackupPayload {
        if (password.isEmpty()) throw BackupCryptoException("Password cannot be empty")
        val (header, ciphertextWithTag) = decodeEnvelope(encryptedText)
        val key = deriveKey(password, header.salt, header.kdfParams)
        val json = decryptWithKeyInternal(header, ciphertextWithTag, key)
        return DecryptedBackupPayload(
            plaintextJson = json,
            header = header,
            derivedKey = key,
        )
    }

    fun decryptWithKey(
        encryptedText: String,
        derivedKey: ByteArray,
    ): DecryptedBackupPayload {
        require(derivedKey.size == KEY_SIZE) { "Key must be $KEY_SIZE bytes" }
        val (header, ciphertextWithTag) = decodeEnvelope(encryptedText)
        val json = decryptWithKeyInternal(header, ciphertextWithTag, derivedKey)
        return DecryptedBackupPayload(
            plaintextJson = json,
            header = header,
            derivedKey = derivedKey.copyOf(),
        )
    }

    private fun decryptWithKeyInternal(
        header: BackupEnvelopeHeader,
        ciphertextWithTag: ByteArray,
        key: ByteArray,
    ): String {
        val compressed = XChaCha20Poly1305.decrypt(
            key = key,
            nonce = header.nonce,
            ciphertextWithTag = ciphertextWithTag,
            aad = header.headerBytes,
        ) ?: throw BackupCryptoException("Incorrect password or corrupted encrypted backup")

        val rawBytes = try {
            when (header.flags) {
                FLAG_DEFLATE -> DeflateCodec.decompress(compressed)
                FLAG_RAW -> compressed
                else -> throw BackupCryptoException("Unsupported compression flag: ${header.flags}")
            }
        } catch (e: BackupCryptoException) {
            throw e
        } catch (e: Exception) {
            throw BackupCryptoException("Corrupted backup payload", e)
        }
        return rawBytes.decodeToString()
    }

    private fun buildHeader(
        flags: Byte,
        params: BackupKdfParams,
        salt: ByteArray,
        nonce: ByteArray,
    ): ByteArray {
        val header = ByteArray(HEADER_SIZE)
        MAGIC.copyInto(header, 0)
        header[4] = flags
        // memoryKiB (4 bytes BE)
        header[5] = ((params.memoryKiB ushr 24) and 0xFF).toByte()
        header[6] = ((params.memoryKiB ushr 16) and 0xFF).toByte()
        header[7] = ((params.memoryKiB ushr 8) and 0xFF).toByte()
        header[8] = (params.memoryKiB and 0xFF).toByte()
        // iterations (2 bytes BE)
        header[9] = ((params.iterations ushr 8) and 0xFF).toByte()
        header[10] = (params.iterations and 0xFF).toByte()
        // parallelism (1 byte)
        header[11] = (params.parallelism and 0xFF).toByte()
        salt.copyInto(header, 12)
        nonce.copyInto(header, 28)
        return header
    }

    private fun decodeEnvelope(encryptedText: String): Pair<BackupEnvelopeHeader, ByteArray> {
        val trimmed = encryptedText.trim()
        if (!trimmed.startsWith(PREFIX)) {
            throw BackupCryptoException("Invalid backup format: missing $PREFIX prefix")
        }
        val body = trimmed.removePrefix(PREFIX).filterNot { it.isWhitespace() }
        if (body.isEmpty()) {
            throw BackupCryptoException("Empty encrypted backup payload")
        }
        val raw = runCatching { decodeUrlSafeBase64(body) }
            .getOrElse { throw BackupCryptoException("Invalid Base64 in encrypted backup", it) }

        if (raw.size < HEADER_SIZE + XChaCha20Poly1305.TAG_SIZE) {
            throw BackupCryptoException("Encrypted backup is truncated")
        }
        for (i in MAGIC.indices) {
            if (raw[i] != MAGIC[i]) {
                throw BackupCryptoException("Unrecognized encrypted backup header")
            }
        }
        val flags = raw[4]
        if (flags != FLAG_DEFLATE && flags != FLAG_RAW) {
            throw BackupCryptoException("Unsupported backup flags: $flags")
        }
        val memoryKiB = ((raw[5].toInt() and 0xFF) shl 24) or
            ((raw[6].toInt() and 0xFF) shl 16) or
            ((raw[7].toInt() and 0xFF) shl 8) or
            (raw[8].toInt() and 0xFF)
        val iterations = ((raw[9].toInt() and 0xFF) shl 8) or
            (raw[10].toInt() and 0xFF)
        val parallelism = raw[11].toInt() and 0xFF
        // Cap memoryKiB to 256 MiB to guard against crafted DoS headers
        if (parallelism !in 1..64 || iterations !in 1..1000 || memoryKiB !in (8 * parallelism)..(256 * 1024)) {
            throw BackupCryptoException("Unsupported or unsafe KDF parameters in backup header")
        }

        val headerBytes = raw.copyOfRange(0, HEADER_SIZE)
        val salt = raw.copyOfRange(12, 28)
        val nonce = raw.copyOfRange(28, 52)
        val ciphertext = raw.copyOfRange(HEADER_SIZE, raw.size)

        return BackupEnvelopeHeader(
            flags = flags,
            kdfParams = BackupKdfParams(
                memoryKiB = memoryKiB,
                iterations = iterations,
                parallelism = parallelism,
            ),
            salt = salt,
            nonce = nonce,
            headerBytes = headerBytes,
        ) to ciphertext
    }

    internal fun encodeUrlSafeBase64(bytes: ByteArray): String =
        Base64.UrlSafe.encode(bytes).trimEnd('=')

    internal fun decodeUrlSafeBase64(encoded: String): ByteArray {
        val clean = encoded.trimEnd('=')
        val pad = (4 - (clean.length % 4)) % 4
        return Base64.UrlSafe.decode(clean + "=".repeat(pad))
    }
}
