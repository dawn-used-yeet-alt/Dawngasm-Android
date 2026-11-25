package dev.dawngasm.app.crypto

/**
 * Pure Kotlin RFC 7693 BLAKE2b hash function (unkeyed or keyed, 1..64 byte digest).
 */
internal object Blake2b {
    private val IV = longArrayOf(
        0x6a09e667f3bcc908L,
        -0x4498517a7b3558c5L, // 0xbb67ae8584caa73b
        0x3c6ef372fe94f82bL,
        -0x5ab00ac5a0e2c90fL, // 0xa54ff53a5f1d36f1
        0x510e527fade682d1L,
        -0x64fa9773d4c193e1L, // 0x9b05688c2b3e6c1f
        0x1f83d9abfb41bd6bL,
        0x5be0cd19137e2179L,
    )

    private val SIGMA = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
        intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
        intArrayOf(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
        intArrayOf(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
        intArrayOf(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
        intArrayOf(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
        intArrayOf(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
        intArrayOf(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
        intArrayOf(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
        intArrayOf(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0),
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
        intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
    )

    fun hash(
        input: ByteArray,
        outLen: Int = 64,
        key: ByteArray = ByteArray(0),
    ): ByteArray {
        require(outLen in 1..64) { "BLAKE2b outLen must be 1..64" }
        require(key.size <= 64) { "BLAKE2b key length must be <= 64" }

        val state = Digest(outLen, key)
        if (input.isNotEmpty()) {
            state.update(input)
        }
        return state.finish()
    }

    class Digest(
        private val outLen: Int = 64,
        key: ByteArray = ByteArray(0),
    ) {
        private val h = IV.copyOf()
        private val buffer = ByteArray(128)
        private var bufferPos = 0
        private var totalLow = 0L
        private var totalHigh = 0L
        private val m = LongArray(16)
        private val v = LongArray(16)

        init {
            require(outLen in 1..64) { "BLAKE2b outLen must be 1..64" }
            require(key.size <= 64) { "BLAKE2b key length must be <= 64" }
            val param0 = (outLen.toLong() and 0xFFL) or
                ((key.size.toLong() and 0xFFL) shl 8) or
                (1L shl 16) or
                (1L shl 24)
            h[0] = h[0] xor param0

            if (key.isNotEmpty()) {
                val keyBlock = ByteArray(128)
                key.copyInto(keyBlock)
                update(keyBlock)
            }
        }

        fun update(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
            var pos = offset
            var remaining = length
            while (remaining > 0) {
                if (bufferPos == 128) {
                    addCounter(128L)
                    compress(buffer, 0, isLast = false)
                    bufferPos = 0
                }
                val take = minOf(remaining, 128 - bufferPos)
                data.copyInto(buffer, bufferPos, pos, pos + take)
                bufferPos += take
                pos += take
                remaining -= take
            }
        }

        fun updateInt32LE(value: Int) {
            val b = ByteArray(4)
            b[0] = (value and 0xFF).toByte()
            b[1] = ((value ushr 8) and 0xFF).toByte()
            b[2] = ((value ushr 16) and 0xFF).toByte()
            b[3] = ((value ushr 24) and 0xFF).toByte()
            update(b)
        }

        fun finish(): ByteArray {
            addCounter(bufferPos.toLong())
            for (i in bufferPos until 128) {
                buffer[i] = 0
            }
            compress(buffer, 0, isLast = true)

            val out = ByteArray(outLen)
            for (i in 0 until outLen) {
                val word = h[i ushr 3]
                out[i] = ((word ushr ((i and 7) shl 3)) and 0xFFL).toByte()
            }
            return out
        }

        private fun addCounter(inc: Long) {
            val prev = totalLow
            totalLow += inc
            if (totalLow.toULong() < prev.toULong()) {
                totalHigh += 1L
            }
        }

        private fun compress(block: ByteArray, offset: Int, isLast: Boolean) {
            for (i in 0 until 8) {
                v[i] = h[i]
                v[i + 8] = IV[i]
            }
            v[12] = v[12] xor totalLow
            v[13] = v[13] xor totalHigh
            if (isLast) {
                v[14] = v[14].inv()
            }

            for (i in 0 until 16) {
                m[i] = loadLe64(block, offset + (i shl 3))
            }

            for (r in 0 until 12) {
                val s = SIGMA[r]
                g(0, 4, 8, 12, m[s[0]], m[s[1]])
                g(1, 5, 9, 13, m[s[2]], m[s[3]])
                g(2, 6, 10, 14, m[s[4]], m[s[5]])
                g(3, 7, 11, 15, m[s[6]], m[s[7]])
                g(0, 5, 10, 15, m[s[8]], m[s[9]])
                g(1, 6, 11, 12, m[s[10]], m[s[11]])
                g(2, 7, 8, 13, m[s[12]], m[s[13]])
                g(3, 4, 9, 14, m[s[14]], m[s[15]])
            }

            for (i in 0 until 8) {
                h[i] = h[i] xor v[i] xor v[i + 8]
            }
        }

        private fun g(a: Int, b: Int, c: Int, d: Int, x: Long, y: Long) {
            v[a] = v[a] + v[b] + x
            v[d] = (v[d] xor v[a]).rotateRight(32)
            v[c] = v[c] + v[d]
            v[b] = (v[b] xor v[c]).rotateRight(24)
            v[a] = v[a] + v[b] + y
            v[d] = (v[d] xor v[a]).rotateRight(16)
            v[c] = v[c] + v[d]
            v[b] = (v[b] xor v[c]).rotateRight(63)
        }

        private fun loadLe64(src: ByteArray, off: Int): Long {
            return (src[off].toLong() and 0xFFL) or
                ((src[off + 1].toLong() and 0xFFL) shl 8) or
                ((src[off + 2].toLong() and 0xFFL) shl 16) or
                ((src[off + 3].toLong() and 0xFFL) shl 24) or
                ((src[off + 4].toLong() and 0xFFL) shl 32) or
                ((src[off + 5].toLong() and 0xFFL) shl 40) or
                ((src[off + 6].toLong() and 0xFFL) shl 48) or
                ((src[off + 7].toLong() and 0xFFL) shl 56)
        }
    }
}
