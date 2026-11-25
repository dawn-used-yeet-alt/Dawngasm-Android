package dev.dawngasm.app.crypto

/**
 * Pure Kotlin RFC 9106 Argon2id key derivation function.
 */
internal object Argon2id {
    private const val VERSION = 0x13
    private const val TYPE_ARGON2ID = 2
    private const val BLOCK_QWORDS = 128
    private const val BLOCK_BYTES = 1024
    private const val SYNC_POINTS = 4

    const val DEFAULT_MEMORY_KIB = 16 * 1024 // 16 MiB
    const val DEFAULT_ITERATIONS = 2
    const val DEFAULT_PARALLELISM = 1

    fun deriveKey(
        password: ByteArray,
        salt: ByteArray,
        outputLength: Int = 32,
        memoryKiB: Int = DEFAULT_MEMORY_KIB,
        iterations: Int = DEFAULT_ITERATIONS,
        parallelism: Int = DEFAULT_PARALLELISM,
        secret: ByteArray = ByteArray(0),
        associatedData: ByteArray = ByteArray(0),
    ): ByteArray {
        require(parallelism >= 1) { "parallelism must be >= 1" }
        require(iterations >= 1) { "iterations must be >= 1" }
        require(outputLength >= 4) { "outputLength must be >= 4" }
        require(salt.size >= 8) { "salt must be >= 8 bytes" }
        require(memoryKiB >= 8 * parallelism) { "memoryKiB must be >= 8 * parallelism" }

        val memoryBlocks = (memoryKiB / (SYNC_POINTS * parallelism)) * (SYNC_POINTS * parallelism)
        val laneLength = memoryBlocks / parallelism
        val segmentLength = laneLength / SYNC_POINTS

        // H0 = BLAKE2b-64(p || T || m || t || v || y || len(P) || P || len(S) || S || len(K) || K || len(X) || X)
        val h0Digest = Blake2b.Digest(64).apply {
            updateInt32LE(parallelism)
            updateInt32LE(outputLength)
            updateInt32LE(memoryKiB)
            updateInt32LE(iterations)
            updateInt32LE(VERSION)
            updateInt32LE(TYPE_ARGON2ID)
            updateBytesWithLen(password)
            updateBytesWithLen(salt)
            updateBytesWithLen(secret)
            updateBytesWithLen(associatedData)
        }
        val h0 = h0Digest.finish()

        val memory = Array(memoryBlocks) { LongArray(BLOCK_QWORDS) }
        val h0WithIndices = ByteArray(h0.size + 8)
        h0.copyInto(h0WithIndices)

        for (lane in 0 until parallelism) {
            writeInt32LE(h0WithIndices, h0.size + 4, lane)
            writeInt32LE(h0WithIndices, h0.size, 0)
            bytesToBlock(blake2bLong(BLOCK_BYTES, h0WithIndices), memory[lane * laneLength])
            writeInt32LE(h0WithIndices, h0.size, 1)
            bytesToBlock(blake2bLong(BLOCK_BYTES, h0WithIndices), memory[lane * laneLength + 1])
        }

        val tmpBlock = LongArray(BLOCK_QWORDS)
        val rBlock = LongArray(BLOCK_QWORDS)
        val addrInput = LongArray(BLOCK_QWORDS)
        val addrZero = LongArray(BLOCK_QWORDS)
        val addrBlock = LongArray(BLOCK_QWORDS)
        val addrMid = LongArray(BLOCK_QWORDS)

        for (pass in 0 until iterations) {
            for (slice in 0 until SYNC_POINTS) {
                val dataIndependent = pass == 0 && slice < 2
                for (lane in 0 until parallelism) {
                    val startIndex = if (pass == 0 && slice == 0) 2 else 0
                    var addrCounter = 0L
                    if (dataIndependent) {
                        addrInput.fill(0L)
                        addrInput[0] = pass.toLong()
                        addrInput[1] = lane.toLong()
                        addrInput[2] = slice.toLong()
                        addrInput[3] = memoryBlocks.toLong()
                        addrInput[4] = iterations.toLong()
                        addrInput[5] = TYPE_ARGON2ID.toLong()
                    }

                    for (index in startIndex until segmentLength) {
                        val currOffset = lane * laneLength + slice * segmentLength + index
                        val prevOffset = if (slice == 0 && index == 0) {
                            lane * laneLength + laneLength - 1
                        } else {
                            currOffset - 1
                        }

                        val pseudoRand: Long = if (dataIndependent) {
                            val addrIdx = index % BLOCK_QWORDS
                            if (addrIdx == 0 || index == startIndex) {
                                // Note: for index == 2 in slice 0 pass 0, addrCounter starts at 1
                                val neededCounter = (index / BLOCK_QWORDS) + 1L
                                while (addrCounter < neededCounter) {
                                    addrCounter++
                                    addrInput[6] = addrCounter
                                    compressG(addrZero, addrInput, addrMid, rBlock)
                                    compressG(addrZero, addrMid, addrBlock, rBlock)
                                }
                            }
                            addrBlock[addrIdx]
                        } else {
                            memory[prevOffset][0]
                        }

                        val j1 = pseudoRand and 0xFFFF_FFFFL
                        val j2 = (pseudoRand ushr 32) and 0xFFFF_FFFFL

                        val refLane = if (pass == 0 && slice == 0) {
                            lane
                        } else {
                            (j2.toULong() % parallelism.toULong()).toInt()
                        }

                        val refAreaSize = referenceAreaSize(
                            pass = pass,
                            slice = slice,
                            segmentLength = segmentLength,
                            laneLength = laneLength,
                            index = index,
                            sameLane = refLane == lane,
                        )
                        val relPos = mapIndex(j1, refAreaSize)
                        val startPos = if (pass == 0 || slice == SYNC_POINTS - 1) {
                            0
                        } else {
                            (slice + 1) * segmentLength
                        }
                        val absPos = ((startPos.toLong() + relPos.toLong()) % laneLength.toLong()).toInt()
                        val refOffset = refLane * laneLength + absPos

                        compressG(memory[prevOffset], memory[refOffset], tmpBlock, rBlock)
                        val dst = memory[currOffset]
                        if (pass == 0) {
                            tmpBlock.copyInto(dst)
                        } else {
                            for (q in 0 until BLOCK_QWORDS) {
                                dst[q] = dst[q] xor tmpBlock[q]
                            }
                        }
                    }
                }
            }
        }

        val finalBlock = LongArray(BLOCK_QWORDS)
        for (lane in 0 until parallelism) {
            val last = memory[lane * laneLength + laneLength - 1]
            for (q in 0 until BLOCK_QWORDS) {
                finalBlock[q] = finalBlock[q] xor last[q]
            }
        }

        val finalBytes = ByteArray(BLOCK_BYTES)
        blockToBytes(finalBlock, finalBytes)
        return blake2bLong(outputLength, finalBytes)
    }

    private fun referenceAreaSize(
        pass: Int,
        slice: Int,
        segmentLength: Int,
        laneLength: Int,
        index: Int,
        sameLane: Boolean,
    ): Int {
        return if (pass == 0) {
            if (slice == 0) {
                index - 1
            } else if (sameLane) {
                slice * segmentLength + index - 1
            } else {
                slice * segmentLength - if (index == 0) 1 else 0
            }
        } else {
            if (sameLane) {
                laneLength - segmentLength + index - 1
            } else {
                laneLength - segmentLength - if (index == 0) 1 else 0
            }
        }
    }

    private fun mapIndex(j1: Long, areaSize: Int): Int {
        val x = (j1 * j1) ushr 32
        val y = (areaSize.toLong() * x) ushr 32
        return (areaSize.toLong() - 1L - y).toInt()
    }

    private fun compressG(x: LongArray, y: LongArray, out: LongArray, r: LongArray) {
        for (i in 0 until BLOCK_QWORDS) {
            val v = x[i] xor y[i]
            r[i] = v
            out[i] = v
        }

        // Row-wise P
        for (i in 0 until 8) {
            val b = i * 16
            permP(
                out,
                b + 0, b + 1, b + 2, b + 3,
                b + 4, b + 5, b + 6, b + 7,
                b + 8, b + 9, b + 10, b + 11,
                b + 12, b + 13, b + 14, b + 15,
            )
        }

        // Column-wise P
        for (i in 0 until 8) {
            val b = i * 2
            permP(
                out,
                b + 0, b + 1, b + 16, b + 17,
                b + 32, b + 33, b + 48, b + 49,
                b + 64, b + 65, b + 80, b + 81,
                b + 96, b + 97, b + 112, b + 113,
            )
        }

        for (i in 0 until BLOCK_QWORDS) {
            out[i] = out[i] xor r[i]
        }
    }

    private fun permP(
        v: LongArray,
        i0: Int, i1: Int, i2: Int, i3: Int,
        i4: Int, i5: Int, i6: Int, i7: Int,
        i8: Int, i9: Int, i10: Int, i11: Int,
        i12: Int, i13: Int, i14: Int, i15: Int,
    ) {
        gb(v, i0, i4, i8, i12)
        gb(v, i1, i5, i9, i13)
        gb(v, i2, i6, i10, i14)
        gb(v, i3, i7, i11, i15)

        gb(v, i0, i5, i10, i15)
        gb(v, i1, i6, i11, i12)
        gb(v, i2, i7, i8, i13)
        gb(v, i3, i4, i9, i14)
    }

    private fun gb(v: LongArray, a: Int, b: Int, c: Int, d: Int) {
        v[a] = fBlaMka(v[a], v[b])
        v[d] = (v[d] xor v[a]).rotateRight(32)
        v[c] = fBlaMka(v[c], v[d])
        v[b] = (v[b] xor v[c]).rotateRight(24)
        v[a] = fBlaMka(v[a], v[b])
        v[d] = (v[d] xor v[a]).rotateRight(16)
        v[c] = fBlaMka(v[c], v[d])
        v[b] = (v[b] xor v[c]).rotateRight(63)
    }

    private fun fBlaMka(x: Long, y: Long): Long {
        val m = (x and 0xFFFF_FFFFL) * (y and 0xFFFF_FFFFL)
        return x + y + (m shl 1)
    }

    /** Variable-length hash H'(tau, input) per RFC 9106 Section 3.2. */
    private fun blake2bLong(outLen: Int, input: ByteArray): ByteArray {
        val prefixed = ByteArray(4 + input.size)
        writeInt32LE(prefixed, 0, outLen)
        input.copyInto(prefixed, 4)

        if (outLen <= 64) {
            return Blake2b.hash(prefixed, outLen)
        }

        val out = ByteArray(outLen)
        var v = Blake2b.hash(prefixed, 64)
        v.copyInto(out, 0, 0, 32)
        var outPos = 32
        while (outLen - outPos > 64) {
            v = Blake2b.hash(v, 64)
            v.copyInto(out, outPos, 0, 32)
            outPos += 32
        }
        val tail = Blake2b.hash(v, outLen - outPos)
        tail.copyInto(out, outPos)
        return out
    }

    private fun Blake2b.Digest.updateBytesWithLen(bytes: ByteArray) {
        updateInt32LE(bytes.size)
        if (bytes.isNotEmpty()) update(bytes)
    }

    private fun writeInt32LE(dst: ByteArray, off: Int, value: Int) {
        dst[off] = (value and 0xFF).toByte()
        dst[off + 1] = ((value ushr 8) and 0xFF).toByte()
        dst[off + 2] = ((value ushr 16) and 0xFF).toByte()
        dst[off + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun bytesToBlock(src: ByteArray, dst: LongArray) {
        for (i in 0 until BLOCK_QWORDS) {
            val off = i shl 3
            dst[i] = (src[off].toLong() and 0xFFL) or
                ((src[off + 1].toLong() and 0xFFL) shl 8) or
                ((src[off + 2].toLong() and 0xFFL) shl 16) or
                ((src[off + 3].toLong() and 0xFFL) shl 24) or
                ((src[off + 4].toLong() and 0xFFL) shl 32) or
                ((src[off + 5].toLong() and 0xFFL) shl 40) or
                ((src[off + 6].toLong() and 0xFFL) shl 48) or
                ((src[off + 7].toLong() and 0xFFL) shl 56)
        }
    }

    private fun blockToBytes(src: LongArray, dst: ByteArray) {
        for (i in 0 until BLOCK_QWORDS) {
            val v = src[i]
            val off = i shl 3
            dst[off] = (v and 0xFFL).toByte()
            dst[off + 1] = ((v ushr 8) and 0xFFL).toByte()
            dst[off + 2] = ((v ushr 16) and 0xFFL).toByte()
            dst[off + 3] = ((v ushr 24) and 0xFFL).toByte()
            dst[off + 4] = ((v ushr 32) and 0xFFL).toByte()
            dst[off + 5] = ((v ushr 40) and 0xFFL).toByte()
            dst[off + 6] = ((v ushr 48) and 0xFFL).toByte()
            dst[off + 7] = ((v ushr 56) and 0xFFL).toByte()
        }
    }
}
