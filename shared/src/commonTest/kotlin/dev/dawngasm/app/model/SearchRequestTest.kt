package dev.dawngasm.app.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchRequestTest {
    @Test
    fun summaryBuildsFromTextAndTags() {
        val request = SearchRequest(
            text = "rain",
            includeTags = listOf("f4m", "script fill"),
            excludeTags = listOf("m4f"),
        )
        assertEquals("rain [f4m] [script fill] -[m4f]", request.summary())
    }

    @Test
    fun summaryIsEmptyWithoutFilters() {
        assertEquals("", SearchRequest().summary())
        assertFalse(SearchRequest().hasFilters())
    }

    @Test
    fun changedSortOrWindowCountsAsFilter() {
        assertTrue(SearchRequest(sort = "new").hasFilters())
        assertTrue(SearchRequest(window = "month").hasFilters())
    }

    @Test
    fun defaultWindowIsPastWeek() {
        assertEquals("week", SearchSyntax.DEFAULT_WINDOW)
        assertEquals("Top · Past week", SearchRequest().filtersLabel())
        assertEquals("week", SearchRequest().window)
    }

    @Test
    fun filterLabelMatchesCurrentSelection() {
        assertEquals(
            "New · Past week",
            SearchRequest(sort = "new", window = "week").filtersLabel(),
        )
    }

    @Test
    fun tagsAreNormalisedForSelection() {
        assertEquals("f4m", SearchSyntax.normalizeTag("[F4M]"))
        assertEquals("m4f", SearchSyntax.normalizeTag("-M4F"))
        assertEquals("script fill", SearchSyntax.normalizeTag("  Script   Fill  "))
        assertNull(SearchSyntax.normalizeTag("   "))
        assertNull(SearchSyntax.normalizeTag("[]"))
    }

    @Test
    fun tagFieldTextParsesIntoCleanTags() {
        assertEquals(
            listOf("m4f", "m4m", "m4a"),
            SearchSyntax.parseTagList("m4f, m4m, m4a, "),
        )
        assertEquals(listOf("script fill"), SearchSyntax.parseTagList("[Script Fill], ,"))
        assertEquals(listOf("f4m"), SearchSyntax.parseTagList("F4M, f4m"))
        assertTrue(SearchSyntax.parseTagList(" , ").isEmpty())
    }

    @Test
    fun summaryIncludesHostsAndExcludeHosts() {
        val request = SearchRequest(
            text = "rain",
            hosts = listOf("soundgasm", "whyp"),
            excludeHosts = listOf("audiochan"),
        )
        assertEquals("rain host:soundgasm host:whyp -host:audiochan", request.summary())
        assertTrue(request.hasFilters())
    }
}
