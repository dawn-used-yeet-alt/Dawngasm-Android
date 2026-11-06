package dev.dawngasm.app.data

import org.junit.Assert.assertEquals
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
}
