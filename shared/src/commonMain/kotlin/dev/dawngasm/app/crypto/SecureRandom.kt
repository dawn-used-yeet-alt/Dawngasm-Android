package dev.dawngasm.app.crypto

/** Cryptographically secure random bytes backed by each platform's CSPRNG. */
expect fun secureRandomBytes(size: Int): ByteArray
