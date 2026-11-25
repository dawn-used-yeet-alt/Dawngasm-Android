package dev.dawngasm.app.crypto

import java.security.SecureRandom

private val jvmSecureRandom = SecureRandom()

actual fun secureRandomBytes(size: Int): ByteArray {
    require(size >= 0) { "size must be >= 0" }
    return ByteArray(size).also { jvmSecureRandom.nextBytes(it) }
}
