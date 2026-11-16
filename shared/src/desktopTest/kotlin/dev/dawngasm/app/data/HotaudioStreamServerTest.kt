package dev.dawngasm.app.data

import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class HotaudioStreamServerTest {
    private val trackId = "test-stream-server-${System.nanoTime()}"

    @AfterTest
    fun cleanup() {
        HotaudioStreamServer.stop()
        HotaudioStreamer.cancel(trackId)
        val spec = HotaudioStreamer.spec(trackId, "")
        PlatformFileOps.deleteFile(spec.filePath)
        PlatformFileOps.deleteFile("${spec.filePath.substringBeforeLast('/')}/$trackId.done")
    }

    @Test
    fun servesCompletedStreamAndByteRanges() {
        val bytes = "abcdefghij".toByteArray()
        val spec = HotaudioStreamer.spec(trackId, "https://hotaudio.net/u/test/$trackId")
        PlatformFileOps.writeBytes(spec.filePath, bytes)
        PlatformFileOps.writeText(
            "${spec.filePath.substringBeforeLast('/')}/$trackId.done",
            "${spec.filePath}\n${bytes.size}",
        )

        val url = HotaudioStreamServer.urlFor(trackId)

        val full = get(url)
        assertEquals(200, full.first)
        assertContentEquals(bytes, full.second)

        val ranged = get(url, range = "bytes=4-")
        assertEquals(206, ranged.first)
        assertContentEquals(bytes.copyOfRange(4, bytes.size), ranged.second)

        val bounded = get(url, range = "bytes=2-5")
        assertEquals(206, bounded.first)
        assertContentEquals(bytes.copyOfRange(2, 6), bounded.second)
    }

    @Test
    fun reportsUnknownStreams() {
        val response = get(HotaudioStreamServer.urlFor("missing-$trackId"))
        assertEquals(404, response.first)
    }

    private fun get(url: String, range: String? = null): Pair<Int, ByteArray> {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        range?.let { connection.setRequestProperty("Range", it) }
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            status to (stream?.use { it.readBytes() } ?: ByteArray(0))
        } finally {
            connection.disconnect()
        }
    }
}
