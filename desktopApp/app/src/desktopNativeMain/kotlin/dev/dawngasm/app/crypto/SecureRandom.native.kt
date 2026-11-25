package dev.dawngasm.app.crypto

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned

@OptIn(ExperimentalForeignApi::class, ExperimentalUuidApi::class)
actual fun secureRandomBytes(size: Int): ByteArray {
    require(size >= 0) { "size must be >= 0" }
    if (size == 0) return ByteArray(0)

    // On POSIX (Linux / macOS), read directly from /dev/urandom.
    val urandom = platform.posix.fopen("/dev/urandom", "rb")
    if (urandom != null) {
        try {
            val out = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val read = out.usePinned { pinned ->
                    platform.posix.fread(
                        pinned.addressOf(offset),
                        1.convert(),
                        (size - offset).convert(),
                        urandom,
                    )
                }.toInt()
                if (read <= 0) break
                offset += read
            }
            if (offset == size) return out
        } finally {
            platform.posix.fclose(urandom)
        }
    }

    // Fallback (Windows mingwX64): Kotlin's Uuid.random() uses BCryptGenRandom under the hood.
    // Hash pairs of random UUIDs with BLAKE2b to remove fixed version/variant bits.
    val out = ByteArray(size)
    var pos = 0
    while (pos < size) {
        val seed = Uuid.random().toByteArray() + Uuid.random().toByteArray()
        val chunk = Blake2b.hash(seed, outLen = minOf(64, size - pos))
        chunk.copyInto(out, pos)
        pos += chunk.size
    }
    return out
}
