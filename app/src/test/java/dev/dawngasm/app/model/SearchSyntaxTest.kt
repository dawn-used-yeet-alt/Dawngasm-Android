package dev.dawngasm.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSyntaxTest {
    @Test
    fun parsesWindowAndLeavesSearchText() {
        val parsed = SearchSyntax.parse("rain top:week")
        assertEquals("rain", parsed.text)
        assertEquals("week", parsed.window)
        assertNull(parsed.sort)
    }

    @Test
    fun parsesCustomDayWindow() {
        assertEquals("7d", SearchSyntax.parse("top:7d").window)
        assertEquals("30d", SearchSyntax.parse("rain top:30d").window)
        assertEquals("365d", SearchSyntax.parse("top:365d").window)
    }

    @Test
    fun rejectsInvalidWindowWithoutDroppingText() {
        val parsed = SearchSyntax.parse("top:banana")
        assertEquals("top:banana", parsed.text)
        assertNull(parsed.window)
    }

    @Test
    fun parsesSortAndWindowTogether() {
        val parsed = SearchSyntax.parse("sort:new top:all quiet")
        assertEquals("new", parsed.sort)
        assertEquals("all", parsed.window)
        assertEquals("quiet", parsed.text)
    }

    @Test
    fun normalisesWindowCaseAndBounds() {
        assertEquals("30d", SearchSyntax.normalizeWindow("30D"))
        assertEquals("day", SearchSyntax.normalizeWindow(" Day "))
        assertNull(SearchSyntax.normalizeWindow("0d"))
        assertNull(SearchSyntax.normalizeWindow("3651d"))
        assertNull(SearchSyntax.normalizeWindow("weekdays"))
    }

    @Test
    fun windowLabelsMatchDiscordStyle() {
        assertEquals("Past day", SearchSyntax.windowLabel("day"))
        assertEquals("Past week", SearchSyntax.windowLabel("7d"))
        assertEquals("All time", SearchSyntax.windowLabel("all"))
        assertEquals("Last 14 days", SearchSyntax.windowLabel("14d"))
    }

    @Test
    fun topSuggestionsAppearWhileTyping() {
        val suggestions = SearchSyntax.suggestions("rain top:")
        assertTrue(suggestions.isNotEmpty())
        assertEquals("top:day", suggestions.first().token)

        val week = SearchSyntax.suggestions("rain top:w")
        assertEquals(listOf("top:week"), week.map { it.token })
    }

    @Test
    fun sortSuggestionsAppearWhileTyping() {
        val suggestions = SearchSyntax.suggestions("sort:r")
        assertEquals(listOf("sort:relevance"), suggestions.map { it.token })
    }

    @Test
    fun replaceActiveTokenKeepsEarlierWords() {
        assertEquals("rain top:week ", SearchSyntax.replaceActiveToken("rain top:w", "top:week"))
        assertEquals("top:week ", SearchSyntax.replaceActiveToken("top:w", "top:week"))
        assertEquals("rain top:day ", SearchSyntax.replaceActiveToken("rain ", "top:day"))
    }

    @Test
    fun withoutTokenRemovesOnlyResolvedFilters() {
        assertEquals(
            "rain sort:new",
            SearchSyntax.withoutToken("rain top:7d sort:new", "top:"),
        )
        assertEquals(
            "rain top:7d",
            SearchSyntax.withoutToken("rain top:7d sort:new", "sort:"),
        )
        assertEquals(
            "rain top:banana sort:new",
            SearchSyntax.withoutToken("rain top:banana sort:new", "top:"),
        )
        assertEquals(
            "rain top:7d sort:banana",
            SearchSyntax.withoutToken("rain top:7d sort:banana", "sort:"),
        )
    }

    @Test
    fun emptyInputProducesNoSuggestions() {
        assertTrue(SearchSyntax.suggestions("").isEmpty())
        assertTrue(SearchSyntax.suggestions("rain").isEmpty())
    }
}
