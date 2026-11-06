package dev.dawngasm.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertTrue(SearchRequest(window = "week").hasFilters())
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
}
