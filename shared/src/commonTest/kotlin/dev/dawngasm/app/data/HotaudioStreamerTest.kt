package dev.dawngasm.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HotaudioStreamerTest {

    @Test
    fun normalizesHotaudioPageUrl() {
        assertEquals(
            "https://hotaudio.net/u/alice/track-1",
            HotaudioStreamer.normalizePageUrl("http://hotaudio.net/u/alice/track-1"),
        )
        assertEquals(
            "https://hotaudio.net/u/alice/track-1",
            HotaudioStreamer.normalizePageUrl("https://www.hotaudio.net/u/alice/track-1"),
        )
        assertEquals(
            "https://hotaudio.net/u/alice/track-1",
            HotaudioStreamer.normalizePageUrl("   https://hotaudio.net/u/alice/track-1  "),
        )
    }

    @Test
    fun formatsAndParsesStreamUri() {
        val uri = HotaudioStreamer.streamUri("trk_999")
        assertEquals("hotaudio://stream/trk_999", uri)
        assertEquals("trk_999", HotaudioStreamer.trackIdFrom(uri))
        assertNull(HotaudioStreamer.trackIdFrom("https://example.com/trk_999"))
    }

    @Test
    fun classifiesHotaudioErrorsCorrectly() {
        val challengeErr = Exception("Failed(code: challenge, detail: Cloudflare turnstile required)")
        assertTrue(HotaudioErrors.isChallenge(challengeErr))
        assertFalse(HotaudioErrors.isRateLimit(challengeErr))

        val rateLimitErr = Exception("HTTP 429 Too Many Requests")
        assertTrue(HotaudioErrors.isRateLimit(rateLimitErr))
        assertFalse(HotaudioErrors.isTimeout(rateLimitErr))

        val timeoutErr = Exception("Connection timed out after 30s")
        assertTrue(HotaudioErrors.isTimeout(timeoutErr))

        val dnsErr = Exception("failed to lookup address information: Name or service not known")
        assertTrue(HotaudioErrors.isDnsOrTransient(dnsErr))

        val userMsg = HotaudioErrors.userMessage(challengeErr)
        assertTrue(userMsg.contains("Cloudflare clearance expired"))
    }

    @Test
    fun fakeBridgeRecordsClearanceAndDispatches() {
        var recordedClearance: Pair<String, String>? = null
        var recordedFamily: String? = null

        val fakeBridge = object : HotaudioBridge {
            override fun setClearance(cookie: String, userAgent: String) {
                recordedClearance = cookie to userAgent
            }

            override fun clearClearance() {
                recordedClearance = null
            }

            override fun setIpFamily(family: String) {
                recordedFamily = family
            }

            override fun downloadToFile(
                pageUrl: String,
                destinationPath: String,
                initialKeysJson: String?,
                onProgress: (phase: String, loaded: Long, total: Long) -> Unit,
                isCancelled: () -> Boolean,
            ): HotaudioDownloadResult {
                onProgress("segment", 100L, 500L)
                return HotaudioDownloadResult(keysJson = "{\"keys\":[]}", keyPages = 1)
            }
        }

        HotaudioBridgeProvider.bridge = fakeBridge

        fakeBridge.setClearance("cf_clearance=test123", "TestAgent/1.0")
        assertEquals("cf_clearance=test123" to "TestAgent/1.0", recordedClearance)

        fakeBridge.setIpFamily("ipv4")
        assertEquals("ipv4", recordedFamily)

        fakeBridge.clearClearance()
        assertNull(recordedClearance)
    }
}
