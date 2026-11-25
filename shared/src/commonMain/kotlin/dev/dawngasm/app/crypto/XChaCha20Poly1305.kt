package dev.dawngasm.app.crypto

/**
 * Pure Kotlin RFC 8439 ChaCha20/Poly1305 + draft-irtf-cfrg-xchacha HChaCha20
 * XChaCha20-Poly1305 AEAD implementation.
 */
internal object XChaCha20Poly1305 {
    const val KEY_SIZE = 32
    const val NONCE_SIZE = 24
    const val TAG_SIZE = 16

    private val CONSTANTS = intArrayOf(
        0x61707865,
        0x3320646e,
        0x79622d32,
        0x6b206574,
    )

    fun encrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): ByteArray {
        require(key.size == KEY_SIZE) { "key must be 32 bytes" }
        require(nonce.size == NONCE_SIZE) { "nonce must be 24 bytes" }

        val subkey = hChaCha20(key, nonce.copyOfRange(0, 16))
        val subNonce = ByteArray(12)
        nonce.copyInto(subNonce, destinationOffset = 4, startIndex = 16, endIndex = 24)

        val polyKey = chaCha20Block(subkey, 0, subNonce).copyOfRange(0, 32)
        val ciphertext = chaCha20Xor(subkey, 1, subNonce, plaintext)
        val tag = computeAeadTag(polyKey, aad, ciphertext)

        return ciphertext + tag
    }

    fun decrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertextWithTag: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): ByteArray? {
        require(key.size == KEY_SIZE) { "key must be 32 bytes" }
        require(nonce.size == NONCE_SIZE) { "nonce must be 24 bytes" }
        if (ciphertextWithTag.size < TAG_SIZE) return null

        val ctLen = ciphertextWithTag.size - TAG_SIZE
        val ciphertext = ciphertextWithTag.copyOfRange(0, ctLen)
        val tag = ciphertextWithTag.copyOfRange(ctLen, ciphertextWithTag.size)

        val subkey = hChaCha20(key, nonce.copyOfRange(0, 16))
        val subNonce = ByteArray(12)
        nonce.copyInto(subNonce, destinationOffset = 4, startIndex = 16, endIndex = 24)

        val polyKey = chaCha20Block(subkey, 0, subNonce).copyOfRange(0, 32)
        val expectedTag = computeAeadTag(polyKey, aad, ciphertext)
        if (!constantTimeEquals(tag, expectedTag)) return null

        return chaCha20Xor(subkey, 1, subNonce, ciphertext)
    }

    internal fun hChaCha20(key: ByteArray, nonce16: ByteArray): ByteArray {
        require(key.size == 32 && nonce16.size == 16)
        val state = IntArray(16)
        CONSTANTS.copyInto(state)
        for (i in 0 until 8) state[4 + i] = loadLe32(key, i * 4)
        for (i in 0 until 4) state[12 + i] = loadLe32(nonce16, i * 4)

        repeat(10) {
            quarterRound(state, 0, 4, 8, 12)
            quarterRound(state, 1, 5, 9, 13)
            quarterRound(state, 2, 6, 10, 14)
            quarterRound(state, 3, 7, 11, 15)
            quarterRound(state, 0, 5, 10, 15)
            quarterRound(state, 1, 6, 11, 12)
            quarterRound(state, 2, 7, 8, 13)
            quarterRound(state, 3, 4, 9, 14)
        }

        val out = ByteArray(32)
        for (i in 0 until 4) writeLe32(out, i * 4, state[i])
        for (i in 0 until 4) writeLe32(out, (4 + i) * 4, state[12 + i])
        return out
    }

    private fun chaCha20Block(key: ByteArray, counter: Int, nonce12: ByteArray): ByteArray {
        val initial = IntArray(16)
        CONSTANTS.copyInto(initial)
        for (i in 0 until 8) initial[4 + i] = loadLe32(key, i * 4)
        initial[12] = counter
        for (i in 0 until 3) initial[13 + i] = loadLe32(nonce12, i * 4)

        val working = initial.copyOf()
        repeat(10) {
            quarterRound(working, 0, 4, 8, 12)
            quarterRound(working, 1, 5, 9, 13)
            quarterRound(working, 2, 6, 10, 14)
            quarterRound(working, 3, 7, 11, 15)
            quarterRound(working, 0, 5, 10, 15)
            quarterRound(working, 1, 6, 11, 12)
            quarterRound(working, 2, 7, 8, 13)
            quarterRound(working, 3, 4, 9, 14)
        }

        val out = ByteArray(64)
        for (i in 0 until 16) {
            writeLe32(out, i * 4, working[i] + initial[i])
        }
        return out
    }

    private fun chaCha20Xor(
        key: ByteArray,
        initialCounter: Int,
        nonce12: ByteArray,
        input: ByteArray,
    ): ByteArray {
        val out = ByteArray(input.size)
        var counter = initialCounter
        var offset = 0
        while (offset < input.size) {
            val block = chaCha20Block(key, counter++, nonce12)
            val n = minOf(64, input.size - offset)
            for (i in 0 until n) {
                out[offset + i] = (input[offset + i].toInt() xor block[i].toInt()).toByte()
            }
            offset += n
        }
        return out
    }

    private fun quarterRound(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = (s[d] xor s[a]).rotateLeft(16)
        s[c] += s[d]; s[b] = (s[b] xor s[c]).rotateLeft(12)
        s[a] += s[b]; s[d] = (s[d] xor s[a]).rotateLeft(8)
        s[c] += s[d]; s[b] = (s[b] xor s[c]).rotateLeft(7)
    }

    private fun computeAeadTag(polyKey: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        val mac = Poly1305(polyKey)
        if (aad.isNotEmpty()) {
            mac.update(aad)
            val aadPad = (16 - (aad.size % 16)) % 16
            if (aadPad > 0) mac.update(ByteArray(aadPad))
        }
        if (ciphertext.isNotEmpty()) {
            mac.update(ciphertext)
            val ctPad = (16 - (ciphertext.size % 16)) % 16
            if (ctPad > 0) mac.update(ByteArray(ctPad))
        }
        val lengths = ByteArray(16)
        writeLe64(lengths, 0, aad.size.toLong())
        writeLe64(lengths, 8, ciphertext.size.toLong())
        mac.update(lengths)
        return mac.finish()
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].toInt() xor b[i].toInt())
        }
        return diff == 0
    }

    private fun loadLe32(src: ByteArray, off: Int): Int {
        return (src[off].toInt() and 0xFF) or
            ((src[off + 1].toInt() and 0xFF) shl 8) or
            ((src[off + 2].toInt() and 0xFF) shl 16) or
            ((src[off + 3].toInt() and 0xFF) shl 24)
    }

    private fun writeLe32(dst: ByteArray, off: Int, value: Int) {
        dst[off] = (value and 0xFF).toByte()
        dst[off + 1] = ((value ushr 8) and 0xFF).toByte()
        dst[off + 2] = ((value ushr 16) and 0xFF).toByte()
        dst[off + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun writeLe64(dst: ByteArray, off: Int, value: Long) {
        for (i in 0 until 8) {
            dst[off + i] = ((value ushr (i * 8)) and 0xFFL).toByte()
        }
    }

    /**
     * Pure Kotlin Poly1305 MAC (RFC 8439) using 26-bit limbs (Donna-style).
     * Maximum intermediate product fits well within signed 64-bit Long.
     */
    internal class Poly1305(key: ByteArray) {
        private val r0: Long
        private val r1: Long
        private val r2: Long
        private val r3: Long
        private val r4: Long
        private val s1: Long
        private val s2: Long
        private val s3: Long
        private val s4: Long
        private val pad0: Long
        private val pad1: Long
        private val pad2: Long
        private val pad3: Long

        private var h0 = 0L
        private var h1 = 0L
        private var h2 = 0L
        private var h3 = 0L
        private var h4 = 0L

        private val buffer = ByteArray(16)
        private var bufferPos = 0

        init {
            require(key.size == 32) { "Poly1305 key must be 32 bytes" }
            val rBytes = key.copyOfRange(0, 16)
            rBytes[3] = (rBytes[3].toInt() and 15).toByte()
            rBytes[7] = (rBytes[7].toInt() and 15).toByte()
            rBytes[11] = (rBytes[11].toInt() and 15).toByte()
            rBytes[15] = (rBytes[15].toInt() and 15).toByte()
            rBytes[4] = (rBytes[4].toInt() and 252).toByte()
            rBytes[8] = (rBytes[8].toInt() and 252).toByte()
            rBytes[12] = (rBytes[12].toInt() and 252).toByte()

            val k0 = loadLe32(rBytes, 0).toLong() and 0xFFFF_FFFFL
            val k1 = loadLe32(rBytes, 4).toLong() and 0xFFFF_FFFFL
            val k2 = loadLe32(rBytes, 8).toLong() and 0xFFFF_FFFFL
            val k3 = loadLe32(rBytes, 12).toLong() and 0xFFFF_FFFFL

            r0 = k0 and 0x3FF_FFFFL
            r1 = ((k0 ushr 26) or (k1 shl 6)) and 0x3FF_FFFFL
            r2 = ((k1 ushr 20) or (k2 shl 12)) and 0x3FF_FFFFL
            r3 = ((k2 ushr 14) or (k3 shl 18)) and 0x3FF_FFFFL
            r4 = (k3 ushr 8) and 0x3FF_FFFFL

            s1 = r1 * 5L
            s2 = r2 * 5L
            s3 = r3 * 5L
            s4 = r4 * 5L

            pad0 = loadLe32(key, 16).toLong() and 0xFFFF_FFFFL
            pad1 = loadLe32(key, 20).toLong() and 0xFFFF_FFFFL
            pad2 = loadLe32(key, 24).toLong() and 0xFFFF_FFFFL
            pad3 = loadLe32(key, 28).toLong() and 0xFFFF_FFFFL
            // Suppress unused warning on t0..t3 if any by not having them; wait - let's remove t0..t3!
        }

        fun update(data: ByteArray) {
            var pos = 0
            var remaining = data.size
            while (remaining > 0) {
                val take = minOf(remaining, 16 - bufferPos)
                data.copyInto(buffer, bufferPos, pos, pos + take)
                bufferPos += take
                pos += take
                remaining -= take
                if (bufferPos == 16) {
                    processBlock(buffer, 16, hibit = 1L shl 24)
                    bufferPos = 0
                }
            }
        }

        fun finish(): ByteArray {
            if (bufferPos > 0) {
                buffer[bufferPos] = 1
                for (i in bufferPos + 1 until 16) {
                    buffer[i] = 0
                }
                processBlock(buffer, bufferPos, hibit = 0L)
                bufferPos = 0
            }

            // Full carry
            var c = h1 ushr 26; h1 = h1 and 0x3FF_FFFFL
            h2 += c; c = h2 ushr 26; h2 = h2 and 0x3FF_FFFFL
            h3 += c; c = h3 ushr 26; h3 = h3 and 0x3FF_FFFFL
            h4 += c; c = h4 ushr 26; h4 = h4 and 0x3FF_FFFFL
            h0 += c * 5L; c = h0 ushr 26; h0 = h0 and 0x3FF_FFFFL
            h1 += c

            // Compute h - p = h + 5 - 2^130
            var g0 = h0 + 5L; c = g0 ushr 26; g0 = g0 and 0x3FF_FFFFL
            var g1 = h1 + c; c = g1 ushr 26; g1 = g1 and 0x3FF_FFFFL
            var g2 = h2 + c; c = g2 ushr 26; g2 = g2 and 0x3FF_FFFFL
            var g3 = h3 + c; c = g3 ushr 26; g3 = g3 and 0x3FF_FFFFL
            var g4 = h4 + c - (1L shl 26)

            val mask = (g4 ushr 63) - 1L
            g0 = g0 and mask; g1 = g1 and mask; g2 = g2 and mask; g3 = g3 and mask; g4 = g4 and mask
            val invMask = mask.inv()
            h0 = (h0 and invMask) or g0
            h1 = (h1 and invMask) or g1
            h2 = (h2 and invMask) or g2
            h3 = (h3 and invMask) or g3
            h4 = (h4 and invMask) or g4

            // Reassemble 128-bit result and add pad
            val f0 = ((h0) or (h1 shl 26)) and 0xFFFF_FFFFL
            val f1 = ((h1 ushr 6) or (h2 shl 20)) and 0xFFFF_FFFFL
            val f2 = ((h2 ushr 12) or (h3 shl 14)) and 0xFFFF_FFFFL
            val f3 = ((h3 ushr 18) or (h4 shl 8)) and 0xFFFF_FFFFL

            var w0 = f0 + pad0
            var w1 = f1 + pad1 + (w0 ushr 32)
            var w2 = f2 + pad2 + (w1 ushr 32)
            val w3 = f3 + pad3 + (w2 ushr 32)

            val out = ByteArray(16)
            writeLe32(out, 0, w0.toInt())
            writeLe32(out, 4, w1.toInt())
            writeLe32(out, 8, w2.toInt())
            writeLe32(out, 12, w3.toInt())
            return out
        }

        private fun processBlock(block: ByteArray, @Suppress("UNUSED_PARAMETER") len: Int, hibit: Long) {
            val b0 = loadLe32(block, 0).toLong() and 0xFFFF_FFFFL
            val b1 = loadLe32(block, 4).toLong() and 0xFFFF_FFFFL
            val b2 = loadLe32(block, 8).toLong() and 0xFFFF_FFFFL
            val b3 = loadLe32(block, 12).toLong() and 0xFFFF_FFFFL

            h0 += b0 and 0x3FF_FFFFL
            h1 += ((b0 ushr 26) or (b1 shl 6)) and 0x3FF_FFFFL
            h2 += ((b1 ushr 20) or (b2 shl 12)) and 0x3FF_FFFFL
            h3 += ((b2 ushr 14) or (b3 shl 18)) and 0x3FF_FFFFL
            h4 += ((b3 ushr 8) and 0x3FF_FFFFL) or hibit

            val d0 = h0 * r0 + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1
            val d1 = h0 * r1 + h1 * r0 + h2 * s4 + h3 * s3 + h4 * s2
            val d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * s4 + h4 * s3
            val d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * s4
            val d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0

            var c = d0 ushr 26; h0 = d0 and 0x3FF_FFFFL
            val t1 = d1 + c; c = t1 ushr 26; h1 = t1 and 0x3FF_FFFFL
            val t2 = d2 + c; c = t2 ushr 26; h2 = t2 and 0x3FF_FFFFL
            val t3 = d3 + c; c = t3 ushr 26; h3 = t3 and 0x3FF_FFFFL
            val t4 = d4 + c; c = t4 ushr 26; h4 = t4 and 0x3FF_FFFFL
            h0 += c * 5L; c = h0 ushr 26; h0 = h0 and 0x3FF_FFFFL
            h1 += c
        }
    }
}
