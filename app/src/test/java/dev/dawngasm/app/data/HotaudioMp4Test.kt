package dev.dawngasm.app.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HotaudioMp4Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun makeBox(type: String, payload: ByteArray = ByteArray(0)): ByteArray {
        val totalSize = 8 + payload.size
        val data = ByteArray(totalSize)
        data[0] = ((totalSize shr 24) and 0xFF).toByte()
        data[1] = ((totalSize shr 16) and 0xFF).toByte()
        data[2] = ((totalSize shr 8) and 0xFF).toByte()
        data[3] = (totalSize and 0xFF).toByte()
        type.toByteArray(Charsets.US_ASCII).copyInto(data, 4, 0, 4)
        payload.copyInto(data, 8, 0, payload.size)
        return data
    }

    private fun makeMfhdBox(sequence: Long): ByteArray {
        val payload = ByteArray(8)
        // version 0 (1 byte), flags 0 (3 bytes)
        payload[0] = 0
        payload[1] = 0
        payload[2] = 0
        payload[3] = 0
        // sequence_number (4 bytes)
        payload[4] = ((sequence shr 24) and 0xFF).toByte()
        payload[5] = ((sequence shr 16) and 0xFF).toByte()
        payload[6] = ((sequence shr 8) and 0xFF).toByte()
        payload[7] = (sequence and 0xFF).toByte()
        return makeBox("mfhd", payload)
    }

    @Test
    fun parsesBoxesAndReadsTypes() {
        val ftyp = makeBox("ftyp", "mp42".toByteArray(Charsets.US_ASCII))
        val moov = makeBox("moov", ByteArray(10))
        val combined = ftyp + moov

        val boxes = HotaudioMp4.parseBoxes(combined)
        assertEquals(2, boxes.size)
        assertEquals("ftyp", boxes[0].type)
        assertEquals(ftyp.size, boxes[0].totalSize)
        assertEquals("moov", boxes[1].type)
        assertEquals(moov.size, boxes[1].totalSize)
    }

    @Test
    fun extractsMfhdSequenceNumber() {
        val mfhd = makeMfhdBox(42L)
        val traf = makeBox("traf", ByteArray(4))
        val moof = makeBox("moof", mfhd + traf)

        val seq = HotaudioMp4.extractMfhdSequence(moof)
        assertEquals(42L, seq)
    }

    @Test
    fun classifiesInitAndMediaFragments() {
        val init = makeBox("ftyp", ByteArray(16))
        val kindInit = HotaudioMp4.classifyFragment(init)
        assertTrue(kindInit is HotaudioMp4.FragmentKind.Init)

        val mfhd = makeMfhdBox(7L)
        val moof = makeBox("moof", mfhd)
        val kindMedia = HotaudioMp4.classifyFragment(moof)
        assertTrue(kindMedia is HotaudioMp4.FragmentKind.Media)
        assertEquals(7L, (kindMedia as HotaudioMp4.FragmentKind.Media).sequenceNumber)

        val unknown = makeBox("free", ByteArray(8))
        assertNull(HotaudioMp4.classifyFragment(unknown))
    }

    @Test
    fun partStoreDetectsGapsAndAssemblesFile() {
        val store = HotaudioMp4.PartStore()
        val init = makeBox("ftyp", ByteArray(8))
        val frag1 = makeBox("moof", makeMfhdBox(1L)) + makeBox("mdat", byteArrayOf(1, 2, 3))
        val frag3 = makeBox("moof", makeMfhdBox(3L)) + makeBox("mdat", byteArrayOf(7, 8, 9))

        store.add(init)
        store.add(frag1)
        store.add(frag3)

        assertEquals(2, store.fragmentCount())
        val gaps = store.checkGaps()
        assertEquals(1, gaps.size)
        assertEquals(2L to 2L, gaps[0])

        // Refill gap
        val frag2 = makeBox("moof", makeMfhdBox(2L)) + makeBox("mdat", byteArrayOf(4, 5, 6))
        store.add(frag2)
        assertTrue(store.checkGaps().isEmpty())

        val outFile = File(tempFolder.root, "output.m4a")
        val result = store.assemble(outFile)

        assertTrue(result.success)
        assertEquals(3, result.fragmentCount)
        assertEquals(1L, result.minSequence)
        assertEquals(3L, result.maxSequence)
        assertTrue(outFile.exists())

        val expected = init + frag1 + frag2 + frag3
        assertArrayEquals(expected, outFile.readBytes())
    }

    @Test
    fun partStoreStreamsProgressively() = kotlinx.coroutines.test.runTest {
        val store = HotaudioMp4.PartStore()
        val init = makeBox("ftyp", ByteArray(8))
        val frag1 = makeBox("moof", makeMfhdBox(1L))
        val frag2 = makeBox("moof", makeMfhdBox(2L))

        store.add(init)
        val waitedInit = store.waitForInit(500)
        assertArrayEquals(init, waitedInit)

        store.add(frag1)
        val firstSeq = store.firstSequence(500)
        assertEquals(1L, firstSeq)

        val pair1 = store.getOrWaitFragment(1L, 500)
        assertNotNull(pair1)
        assertEquals(1L, pair1!!.first)
        assertArrayEquals(frag1, pair1.second)

        store.add(frag2)
        val pair2 = store.getOrWaitFragment(2L, 500)
        assertNotNull(pair2)
        assertEquals(2L, pair2!!.first)
        assertArrayEquals(frag2, pair2.second)
    }
}
