package dev.dawngasm.app.crypto

/**
 * Pure Kotlin RFC 1951 raw DEFLATE compressor and decompressor.
 *
 * - Compressor uses LZ77 hash-chain matching + Fixed Huffman (`BTYPE = 01`),
 *   falling back to Stored (`BTYPE = 00`) if output would expand beyond raw size.
 * - Decompressor supports all RFC 1951 block types (`00` stored, `01` fixed Huffman,
 *   `10` dynamic Huffman) with a bounded output cap to prevent decompression bombs.
 */
internal object DeflateCodec {
    private const val MAX_BITS = 15
    private const val WINDOW_SIZE = 32_768
    private const val WINDOW_MASK = WINDOW_SIZE - 1
    private const val HASH_BITS = 15
    private const val HASH_SIZE = 1 shl HASH_BITS
    private const val HASH_MASK = HASH_SIZE - 1
    private const val MIN_MATCH = 3
    private const val MAX_MATCH = 258
    private const val MAX_CHAIN_STEPS = 32
    const val DEFAULT_MAX_DECOMPRESSED_BYTES = 64 * 1024 * 1024 // 64 MiB

    private val LENGTH_BASE = intArrayOf(
        3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31,
        35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258,
    )
    private val LENGTH_EXTRA = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2,
        3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0,
    )
    private val DIST_BASE = intArrayOf(
        1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193,
        257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577,
    )
    private val DIST_EXTRA = intArrayOf(
        0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6,
        7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13,
    )
    private val CODE_LENGTH_ORDER = intArrayOf(
        16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15,
    )

    private val FIXED_LIT_TABLE: HuffmanTable by lazy {
        val lengths = IntArray(288) { idx ->
            when {
                idx <= 143 -> 8
                idx <= 255 -> 9
                idx <= 279 -> 7
                else -> 8
            }
        }
        HuffmanTable.fromLengths(lengths)
    }

    private val FIXED_DIST_TABLE: HuffmanTable by lazy {
        HuffmanTable.fromLengths(IntArray(32) { 5 })
    }

    fun compress(input: ByteArray): ByteArray {
        if (input.isEmpty()) {
            return byteArrayOf(0x03, 0x00)
        }

        val compressed = compressFixedHuffman(input)
        return if (compressed.size <= input.size) {
            compressed
        } else {
            compressStored(input)
        }
    }

    fun decompress(
        input: ByteArray,
        maxOutputBytes: Int = DEFAULT_MAX_DECOMPRESSED_BYTES,
    ): ByteArray {
        val reader = BitReader(input)
        val out = ByteBuilder()

        var isFinal: Boolean
        do {
            isFinal = reader.readBits(1) == 1
            when (val blockType = reader.readBits(2)) {
                0 -> {
                    reader.alignToByte()
                    val len = reader.readUint16LE()
                    val nlen = reader.readUint16LE()
                    if ((len xor nlen) != 0xFFFF) {
                        throw IllegalArgumentException("Corrupt DEFLATE stored block length")
                    }
                    if (out.size + len > maxOutputBytes) {
                        throw IllegalArgumentException("Decompressed payload exceeds size limit")
                    }
                    repeat(len) {
                        out.append(reader.readByte().toByte())
                    }
                }
                1 -> decodeHuffmanBlock(reader, FIXED_LIT_TABLE, FIXED_DIST_TABLE, out, maxOutputBytes)
                2 -> {
                    val (litTable, distTable) = readDynamicTables(reader)
                    decodeHuffmanBlock(reader, litTable, distTable, out, maxOutputBytes)
                }
                else -> throw IllegalArgumentException("Invalid DEFLATE block type: $blockType")
            }
        } while (!isFinal)

        return out.toByteArray()
    }

    private fun compressStored(input: ByteArray): ByteArray {
        val out = ByteBuilder(input.size + 16)
        var offset = 0
        while (offset < input.size) {
            val chunk = minOf(65_535, input.size - offset)
            val isFinal = offset + chunk >= input.size
            out.append(if (isFinal) 0x01 else 0x00)
            out.append((chunk and 0xFF).toByte())
            out.append(((chunk ushr 8) and 0xFF).toByte())
            val nlen = chunk xor 0xFFFF
            out.append((nlen and 0xFF).toByte())
            out.append(((nlen ushr 8) and 0xFF).toByte())
            out.appendRange(input, offset, offset + chunk)
            offset += chunk
        }
        return out.toByteArray()
    }

    private fun compressFixedHuffman(input: ByteArray): ByteArray {
        val writer = BitWriter(input.size)
        writer.writeBits(1, 1) // BFINAL = 1
        writer.writeBits(1, 2) // BTYPE = 01 (Fixed Huffman)

        val head = IntArray(HASH_SIZE) { -1 }
        val prev = IntArray(WINDOW_SIZE) { -1 }
        val n = input.size
        var pos = 0

        fun insertHash(index: Int) {
            if (index + 2 >= n) return
            val h = hash3(input, index)
            prev[index and WINDOW_MASK] = head[h]
            head[h] = index
        }

        while (pos < n) {
            var bestLen = 0
            var bestDist = 0

            if (pos + MIN_MATCH <= n) {
                val h = hash3(input, pos)
                var candidate = head[h]
                var steps = 0
                val minPos = maxOf(0, pos - WINDOW_SIZE)
                while (candidate >= minPos && steps < MAX_CHAIN_STEPS) {
                    val dist = pos - candidate
                    if (dist in 1..WINDOW_SIZE) {
                        val maxPossible = minOf(MAX_MATCH, n - pos)
                        if (bestLen < maxPossible &&
                            input[candidate + bestLen] == input[pos + bestLen] &&
                            input[candidate] == input[pos]
                        ) {
                            var matchLen = 0
                            while (matchLen < maxPossible && input[candidate + matchLen] == input[pos + matchLen]) {
                                matchLen++
                            }
                            if (matchLen > bestLen) {
                                bestLen = matchLen
                                bestDist = dist
                                if (bestLen == MAX_MATCH) break
                            }
                        }
                    }
                    candidate = prev[candidate and WINDOW_MASK]
                    steps++
                }
            }

            if (bestLen >= MIN_MATCH) {
                emitFixedLength(writer, bestLen)
                emitFixedDistance(writer, bestDist)
                val end = pos + bestLen
                while (pos < end) {
                    insertHash(pos)
                    pos++
                }
            } else {
                emitFixedLiteral(writer, input[pos].toInt() and 0xFF)
                insertHash(pos)
                pos++
            }
        }

        emitFixedLiteral(writer, 256) // End of block
        return writer.toByteArray()
    }

    private fun hash3(data: ByteArray, index: Int): Int {
        val b0 = data[index].toInt() and 0xFF
        val b1 = data[index + 1].toInt() and 0xFF
        val b2 = data[index + 2].toInt() and 0xFF
        return ((b0 shl 10) xor (b1 shl 5) xor b2) and HASH_MASK
    }

    private fun emitFixedLiteral(writer: BitWriter, symbol: Int) {
        when {
            symbol <= 143 -> writer.writeBitsReversed(symbol + 0x30, 8)
            symbol <= 255 -> writer.writeBitsReversed((symbol - 144) + 0x190, 9)
            symbol <= 279 -> writer.writeBitsReversed(symbol - 256, 7)
            else -> writer.writeBitsReversed((symbol - 280) + 0xC0, 8)
        }
    }

    private fun emitFixedLength(writer: BitWriter, length: Int) {
        for (i in LENGTH_BASE.indices) {
            val base = LENGTH_BASE[i]
            val extraBits = LENGTH_EXTRA[i]
            val max = base + (1 shl extraBits) - 1
            if (length <= max) {
                emitFixedLiteral(writer, 257 + i)
                if (extraBits > 0) {
                    writer.writeBits(length - base, extraBits)
                }
                return
            }
        }
        error("Invalid DEFLATE length: $length")
    }

    private fun emitFixedDistance(writer: BitWriter, distance: Int) {
        for (i in DIST_BASE.indices) {
            val base = DIST_BASE[i]
            val extraBits = DIST_EXTRA[i]
            val max = base + (1 shl extraBits) - 1
            if (distance <= max) {
                writer.writeBitsReversed(i, 5)
                if (extraBits > 0) {
                    writer.writeBits(distance - base, extraBits)
                }
                return
            }
        }
        error("Invalid DEFLATE distance: $distance")
    }

    private fun readDynamicTables(reader: BitReader): Pair<HuffmanTable, HuffmanTable> {
        val hlit = reader.readBits(5) + 257
        val hdist = reader.readBits(5) + 1
        val hclen = reader.readBits(4) + 4
        if (hlit > 286 || hdist > 30) {
            throw IllegalArgumentException("Invalid dynamic Huffman header counts")
        }

        val codeLenLengths = IntArray(19)
        for (i in 0 until hclen) {
            codeLenLengths[CODE_LENGTH_ORDER[i]] = reader.readBits(3)
        }
        val codeLenTable = HuffmanTable.fromLengths(codeLenLengths)

        val total = hlit + hdist
        val lengths = IntArray(total)
        var i = 0
        while (i < total) {
            val sym = codeLenTable.decode(reader)
            when {
                sym < 16 -> {
                    lengths[i++] = sym
                }
                sym == 16 -> {
                    if (i == 0) throw IllegalArgumentException("Invalid repeat code 16 at start")
                    val repeat = 3 + reader.readBits(2)
                    val prev = lengths[i - 1]
                    repeat(repeat) {
                        if (i >= total) throw IllegalArgumentException("Code length overflow")
                        lengths[i++] = prev
                    }
                }
                sym == 17 -> {
                    val repeat = 3 + reader.readBits(3)
                    repeat(repeat) {
                        if (i >= total) throw IllegalArgumentException("Code length overflow")
                        lengths[i++] = 0
                    }
                }
                sym == 18 -> {
                    val repeat = 11 + reader.readBits(7)
                    repeat(repeat) {
                        if (i >= total) throw IllegalArgumentException("Code length overflow")
                        lengths[i++] = 0
                    }
                }
                else -> throw IllegalArgumentException("Invalid code-length symbol: $sym")
            }
        }

        if (lengths[256] == 0) {
            throw IllegalArgumentException("Missing end-of-block symbol in dynamic Huffman table")
        }
        return HuffmanTable.fromLengths(lengths.copyOfRange(0, hlit)) to
            HuffmanTable.fromLengths(lengths.copyOfRange(hlit, total))
    }

    private fun decodeHuffmanBlock(
        reader: BitReader,
        litTable: HuffmanTable,
        distTable: HuffmanTable,
        out: ByteBuilder,
        maxOutputBytes: Int,
    ) {
        while (true) {
            val sym = litTable.decode(reader)
            when {
                sym < 256 -> {
                    if (out.size >= maxOutputBytes) {
                        throw IllegalArgumentException("Decompressed payload exceeds size limit")
                    }
                    out.append(sym.toByte())
                }
                sym == 256 -> return
                else -> {
                    val lenIdx = sym - 257
                    if (lenIdx !in LENGTH_BASE.indices) {
                        throw IllegalArgumentException("Invalid length symbol: $sym")
                    }
                    val length = LENGTH_BASE[lenIdx] + reader.readBits(LENGTH_EXTRA[lenIdx])
                    val distSym = distTable.decode(reader)
                    if (distSym !in DIST_BASE.indices) {
                        throw IllegalArgumentException("Invalid distance symbol: $distSym")
                    }
                    val distance = DIST_BASE[distSym] + reader.readBits(DIST_EXTRA[distSym])
                    if (distance <= 0 || distance > out.size) {
                        throw IllegalArgumentException("Invalid back-reference distance: $distance")
                    }
                    if (out.size + length > maxOutputBytes) {
                        throw IllegalArgumentException("Decompressed payload exceeds size limit")
                    }
                    out.copyBackReference(distance, length)
                }
            }
        }
    }

    private class HuffmanTable(
        private val counts: IntArray,
        private val symbols: IntArray,
    ) {
        fun decode(reader: BitReader): Int {
            var code = 0
            var first = 0
            var index = 0
            for (len in 1..MAX_BITS) {
                code = code or reader.readBit()
                val count = counts[len]
                if (code - count < first) {
                    return symbols[index + (code - first)]
                }
                index += count
                first += count
                first = first shl 1
                code = code shl 1
            }
            throw IllegalArgumentException("Invalid Huffman code in DEFLATE stream")
        }

        companion object {
            fun fromLengths(lengths: IntArray): HuffmanTable {
                val counts = IntArray(MAX_BITS + 1)
                for (len in lengths) {
                    if (len > 0) {
                        if (len > MAX_BITS) throw IllegalArgumentException("Huffman code too long: $len")
                        counts[len]++
                    }
                }
                val offsets = IntArray(MAX_BITS + 1)
                var total = 0
                for (len in 1..MAX_BITS) {
                    offsets[len] = total
                    total += counts[len]
                }
                val symbols = IntArray(total)
                for (sym in lengths.indices) {
                    val len = lengths[sym]
                    if (len != 0) {
                        symbols[offsets[len]++] = sym
                    }
                }
                return HuffmanTable(counts, symbols)
            }
        }
    }

    private class BitReader(private val data: ByteArray) {
        private var bytePos = 0
        private var bitBuf = 0
        private var bitCount = 0

        fun readBit(): Int {
            if (bitCount == 0) {
                if (bytePos >= data.size) throw IllegalArgumentException("Unexpected end of DEFLATE stream")
                bitBuf = data[bytePos++].toInt() and 0xFF
                bitCount = 8
            }
            val bit = bitBuf and 1
            bitBuf = bitBuf ushr 1
            bitCount--
            return bit
        }

        fun readBits(n: Int): Int {
            var value = 0
            for (i in 0 until n) {
                value = value or (readBit() shl i)
            }
            return value
        }

        fun alignToByte() {
            bitBuf = 0
            bitCount = 0
        }

        fun readByte(): Int {
            alignToByte()
            if (bytePos >= data.size) throw IllegalArgumentException("Unexpected end of DEFLATE stream")
            return data[bytePos++].toInt() and 0xFF
        }

        fun readUint16LE(): Int {
            val lo = readByte()
            val hi = readByte()
            return lo or (hi shl 8)
        }
    }

    private class BitWriter(initialCapacity: Int) {
        private var bytes = ByteArray(maxOf(64, initialCapacity / 2))
        private var size = 0
        private var bitBuf = 0
        private var bitCount = 0

        fun writeBits(value: Int, count: Int) {
            bitBuf = bitBuf or ((value and ((1 shl count) - 1)) shl bitCount)
            bitCount += count
            while (bitCount >= 8) {
                appendByte((bitBuf and 0xFF).toByte())
                bitBuf = bitBuf ushr 8
                bitCount -= 8
            }
        }

        fun writeBitsReversed(value: Int, count: Int) {
            for (i in count - 1 downTo 0) {
                writeBits((value ushr i) and 1, 1)
            }
        }

        fun toByteArray(): ByteArray {
            if (bitCount > 0) {
                appendByte((bitBuf and 0xFF).toByte())
                bitBuf = 0
                bitCount = 0
            }
            return bytes.copyOf(size)
        }

        private fun appendByte(b: Byte) {
            if (size == bytes.size) {
                bytes = bytes.copyOf(bytes.size * 2)
            }
            bytes[size++] = b
        }
    }

    private class ByteBuilder(initialCapacity: Int = 256) {
        private var data = ByteArray(initialCapacity)
        var size: Int = 0
            private set

        fun append(b: Byte) {
            ensureCapacity(1)
            data[size++] = b
        }

        fun appendRange(src: ByteArray, start: Int, end: Int) {
            val len = end - start
            if (len <= 0) return
            ensureCapacity(len)
            src.copyInto(data, size, start, end)
            size += len
        }

        fun copyBackReference(distance: Int, length: Int) {
            ensureCapacity(length)
            var srcPos = size - distance
            repeat(length) {
                data[size++] = data[srcPos++]
            }
        }

        fun toByteArray(): ByteArray = data.copyOf(size)

        private fun ensureCapacity(extra: Int) {
            val needed = size + extra
            if (needed <= data.size) return
            var next = data.size * 2
            while (next < needed) next *= 2
            data = data.copyOf(next)
        }
    }
}
