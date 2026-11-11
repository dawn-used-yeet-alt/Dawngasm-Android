package dev.dawngasm.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CookieUtilsTest {

    @Test
    fun handlesEmptyAndBlankInputs() {
        assertEquals("", CookieUtils.mergeCookies())
        assertEquals("", CookieUtils.mergeCookies("", null, "   "))
        assertFalse(CookieUtils.hasCookie("", "foo"))
        assertFalse(CookieUtils.hasCookie(null, "foo"))
        assertNull(CookieUtils.extractCookieValue("", "foo"))
        assertNull(CookieUtils.extractCookieValue(null, "foo"))
    }

    @Test
    fun mergesMultipleCookieHeadersAndDeduplicates() {
        val apex = "session=123; theme=dark"
        val api = "theme=light; __Secure-audiochan_playback=jwt_token_val"
        val merged = CookieUtils.mergeCookies(apex, api)

        assertEquals("session=123; theme=light; __Secure-audiochan_playback=jwt_token_val", merged)
    }

    @Test
    fun detectsRequiredClearanceCookie() {
        val cookie = "foo=bar; __Secure-audiochan_playback=valid_token; baz=qux"
        assertTrue(CookieUtils.hasCookie(cookie, "__Secure-audiochan_playback"))
        assertTrue(CookieUtils.hasCookie(cookie, "__secure-audiochan_playback"))
        assertFalse(CookieUtils.hasCookie(cookie, "nonexistent"))
        assertFalse(CookieUtils.hasCookie("foo=bar; __Secure-audiochan_playback=", "__Secure-audiochan_playback"))
    }

    @Test
    fun extractsCookieValue() {
        val cookie = "foo=bar; __Secure-audiochan_playback=my_secret_token; extra=123"
        assertEquals("my_secret_token", CookieUtils.extractCookieValue(cookie, "__Secure-audiochan_playback"))
        assertEquals("bar", CookieUtils.extractCookieValue(cookie, "foo"))
        assertNull(CookieUtils.extractCookieValue(cookie, "other"))
    }
}
