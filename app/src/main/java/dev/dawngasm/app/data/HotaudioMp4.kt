package dev.dawngasm.app.data

import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentSkipListMap

/**
 * ISO BMFF (fMP4) box parser and fragment assembler, translating decrypted
 * fragmented MP4 chunks captured from MediaSource/SourceBuffer into a standard
 * playable .m4a file.
 */
object HotaudioMp4 {

    data class Box(
        val type: String,
        val offset: Int,
        val headerSize: Int,
        val totalSize: Int,
    ) {
        val payloadOffset: Int get() = offset + headerSize
        val payloadSize: Int get() = totalSize - headerSize
    }

    sealed class FragmentKind {
        data object Init : FragmentKind()
        data class Media(val sequenceNumber: Long) : FragmentKind()
    }

    data class AssemblyResult(
        val success: Boolean,
        val fragmentCount: Int,
        val minSequence: Long?,
        val maxSequence: Long?,
        val gaps: List<Pair<Long, Long>>,
        val error: String? = null,
    )

    fun parseBoxes(data: ByteArray, startOffset: Int = 0, length: Int = data.size - startOffset): List<Box> {
        val result = mutableListOf<Box>()
        var offset = startOffset
        val end = startOffset + length
        while (offset < end) {
            if (end - offset < 8) break
            val b0 = data[offset].toLong() and 0xFF
            val b1 = data[offset + 1].toLong() and 0xFF
            val b2 = data[offset + 2].toLong() and 0xFF
            val b3 = data[offset + 3].toLong() and 0xFF
            var size = (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
            val type = String(data, offset + 4, 4, Charsets.US_ASCII)
            var header = 8
            if (size == 1L) {
                if (end - offset < 16) break
                var extSize = 0L
                for (i in 0 until 8) {
                    extSize = (extSize shl 8) or (data[offset + 8 + i].toLong() and 0xFF)
                }
                size = extSize
                header = 16
            } else if (size == 0L) {
                size = (end - offset).toLong()
            }
            if (size < header || offset + size > end) break
            result.add(Box(type, offset, header, size.toInt()))
            offset += size.toInt()
        }
        return result
    }

    fun extractMfhdSequence(data: ByteArray): Long? {
        val topBoxes = parseBoxes(data)
        val moof = topBoxes.firstOrNull { it.type == "moof" } ?: return null
        val subBoxes = parseBoxes(data, moof.payloadOffset, moof.payloadSize)
        val mfhd = subBoxes.firstOrNull { it.type == "mfhd" } ?: return null
        if (mfhd.payloadSize < 8) return null
        val p = mfhd.payloadOffset
        val s0 = data[p + 4].toLong() and 0xFF
        val s1 = data[p + 5].toLong() and 0xFF
        val s2 = data[p + 6].toLong() and 0xFF
        val s3 = data[p + 7].toLong() and 0xFF
        return (s0 shl 24) or (s1 shl 16) or (s2 shl 8) or s3
    }

    fun classifyFragment(data: ByteArray): FragmentKind? {
        if (data.size < 8) return null
        val headType = String(data, 4, 4, Charsets.US_ASCII)
        if (headType == "ftyp") {
            return FragmentKind.Init
        }
        if (headType in setOf("moof", "styp", "sidx", "emsg")) {
            val seq = extractMfhdSequence(data)
            if (seq != null) {
                return FragmentKind.Media(seq)
            }
        }
        return null
    }

    class PartStore {
        @Volatile
        var initSegment: ByteArray? = null
            private set

        val fragments = ConcurrentSkipListMap<Long, ByteArray>()

        fun add(data: ByteArray): Boolean {
            return when (val kind = classifyFragment(data)) {
                is FragmentKind.Init -> {
                    if (initSegment == null) {
                        initSegment = data
                        true
                    } else false
                }
                is FragmentKind.Media -> {
                    if (!fragments.containsKey(kind.sequenceNumber)) {
                        fragments[kind.sequenceNumber] = data
                        true
                    } else false
                }
                null -> false
            }
        }

        fun hasInit(): Boolean = initSegment != null

        fun fragmentCount(): Int = fragments.size

        fun checkGaps(): List<Pair<Long, Long>> {
            val seqs = fragments.keys.toList()
            if (seqs.size <= 1) return emptyList()
            val gaps = mutableListOf<Pair<Long, Long>>()
            for (i in 0 until seqs.size - 1) {
                val left = seqs[i]
                val right = seqs[i + 1]
                if (right > left + 1) {
                    gaps.add((left + 1) to (right - 1))
                }
            }
            return gaps
        }

        fun assemble(outFile: File): AssemblyResult {
            val init = initSegment ?: return AssemblyResult(
                success = false,
                fragmentCount = 0,
                minSequence = null,
                maxSequence = null,
                gaps = emptyList(),
                error = "Missing initialization segment (ftyp/moov)",
            )
            val seqs = fragments.keys.toList()
            if (seqs.isEmpty()) {
                return AssemblyResult(
                    success = false,
                    fragmentCount = 0,
                    minSequence = null,
                    maxSequence = null,
                    gaps = emptyList(),
                    error = "No media fragments captured",
                )
            }
            val gaps = checkGaps()
            val tempFile = File(outFile.parentFile, ".${outFile.name}.tmp")
            try {
                outFile.parentFile?.mkdirs()
                FileOutputStream(tempFile).use { out ->
                    out.write(init)
                    for (seq in seqs) {
                        fragments[seq]?.let { out.write(it) }
                    }
                    out.flush()
                    out.fd.sync()
                }
                if (outFile.exists()) outFile.delete()
                if (!tempFile.renameTo(outFile)) {
                    tempFile.copyTo(outFile, overwrite = true)
                    tempFile.delete()
                }
                return AssemblyResult(
                    success = true,
                    fragmentCount = seqs.size,
                    minSequence = seqs.first(),
                    maxSequence = seqs.last(),
                    gaps = gaps,
                )
            } catch (e: Exception) {
                tempFile.delete()
                return AssemblyResult(
                    success = false,
                    fragmentCount = seqs.size,
                    minSequence = seqs.firstOrNull(),
                    maxSequence = seqs.lastOrNull(),
                    gaps = gaps,
                    error = e.message ?: e.toString(),
                )
            }
        }
    }
}
