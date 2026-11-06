package dev.dawngasm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DawngasmApiTest {
    @Test
    fun mapsNamedWindowsToApiValues() {
        assertEquals("1d", DawngasmApi.apiWindow("day"))
        assertEquals("7d", DawngasmApi.apiWindow("week"))
        assertEquals("month", DawngasmApi.apiWindow("month"))
        assertEquals("year", DawngasmApi.apiWindow("year"))
        assertEquals("all", DawngasmApi.apiWindow("all"))
    }

    @Test
    fun passesCustomDayWindowsThrough() {
        assertEquals("3d", DawngasmApi.apiWindow("3d"))
        assertEquals("90d", DawngasmApi.apiWindow("90d"))
        assertEquals("365d", DawngasmApi.apiWindow("365d"))
    }

    @Test
    fun fallsBackToAllTimeForUnknownWindows() {
        assertEquals("all", DawngasmApi.apiWindow("fortnight"))
        assertEquals("all", DawngasmApi.apiWindow("0d"))
    }

    @Test
    fun clampsSortToSupportedValues() {
        assertEquals("top", DawngasmApi.apiSort("top"))
        assertEquals("new", DawngasmApi.apiSort("new"))
        assertEquals("relevance", DawngasmApi.apiSort("relevance"))
        assertEquals("top", DawngasmApi.apiSort("random"))
    }

    @Test
    fun pageLimitMatchesIndexMaximumUsefully() {
        assertEquals(100, DawngasmApi.PAGE_LIMIT)
        assertEquals(99, DawngasmApi.MAX_PAGE_INDEX)
    }

    @Test
    fun parsesTagAutocompleteRows() {
        val body = """
            {"tags":[{"tag":"f4m","count":94946},{"tag":"script fill","count":104656}]}
        """.trimIndent()
        val tags = DawngasmApi.parseTags(body)
        assertEquals(2, tags.size)
        assertEquals("f4m", tags[0].tag)
        assertEquals(94946L, tags[0].count)
        assertEquals("script fill", tags[1].tag)
    }

    @Test
    fun ignoresBrokenTagResponses() {
        assertTrue(DawngasmApi.parseTags("not json").isEmpty())
        assertTrue(DawngasmApi.parseTags("""{"tags":[]}""").isEmpty())
    }
}
