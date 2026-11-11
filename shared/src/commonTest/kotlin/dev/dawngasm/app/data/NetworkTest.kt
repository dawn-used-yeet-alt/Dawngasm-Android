package dev.dawngasm.app.data

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NetworkTest {
    @Test
    fun userAgentsAreConfigured() {
        assertTrue(Network.APP_USER_AGENT.isNotBlank())
        assertTrue(Network.BROWSER_USER_AGENT.isNotBlank())
    }

    @Test
    fun httpClientIsAvailable() {
        assertNotNull(Network.httpClient)
    }

    @Test
    fun syncCookieHandlesEmptyAndValidCookies() {
        Network.syncCookie("https://example.com", "")
        Network.syncCookie("https://example.com", "session_id=12345; Path=/; Secure")
    }
}
