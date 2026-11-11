package dev.dawngasm.app.data

import dev.dawngasm.app.model.AudioHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun parsesSearchResponseWithAudioColumn() {
        val body = """
            {
                "columns": ["id", "title", "subreddit", "author", "score", "created", "created_unix", "audio"],
                "rows": [
                    ["post1", "Title 1", "gonewildaudio", "author1", 100, "2026-11-01", 1730419200, [
                        {"host": "soundgasm", "url": "https://soundgasm.net/u/author1/title1"}
                    ]],
                    ["post2", "Script Only", "gonewildaudio", "author2", 50, "2026-11-01", 1730419200, []],
                    ["post3", "Title 3", "gonewildaudio", "author3", 200, "2026-11-01", 1730419200, [
                        {"host": "whyp", "url": "https://whyp.it/tracks/12345"}
                    ]]
                ],
                "hasMore": true
            }
        """.trimIndent()

        val parsed = DawngasmApi().parseSearch(body)
        assertFalse(parsed.needsBodies)
        assertTrue(parsed.hasMore)
        assertEquals(2, parsed.posts.size)

        assertEquals("post1", parsed.posts[0].id)
        assertEquals(AudioHost.SOUNDGASM, parsed.posts[0].resolvedHost)
        assertEquals("https://soundgasm.net/u/author1/title1", parsed.posts[0].resolvedAudioUrl)

        assertEquals("post3", parsed.posts[1].id)
        assertEquals(AudioHost.WHYP, parsed.posts[1].resolvedHost)
        assertEquals("https://whyp.it/tracks/12345", parsed.posts[1].resolvedAudioUrl)
    }

    @Test
    fun parseSearchWithoutAudioColumnNeedsBodies() {
        val body = """
            {
                "columns": ["id", "title", "subreddit", "author", "score", "created", "created_unix"],
                "rows": [
                    ["post1", "Title 1", "gonewildaudio", "author1", 100, "2026-11-01", 1730419200]
                ],
                "hasMore": false
            }
        """.trimIndent()

        val parsed = DawngasmApi().parseSearch(body)
        assertTrue(parsed.needsBodies)
        assertFalse(parsed.hasMore)
        assertEquals(1, parsed.posts.size)
        assertEquals("post1", parsed.posts[0].id)
    }

    @Test
    fun buildSearchUrlIncludesHostsAndExcludeHosts() {
        val url = DawngasmApi.buildSearchUrl(
            baseUrl = "https://example.com",
            hosts = listOf("soundgasm", "whyp"),
            excludeHosts = listOf("audiochan"),
        )
        assertEquals("soundgasm,whyp", url.parameters["hosts"])
        assertEquals("audiochan", url.parameters["excludeHosts"])
    }

    @Test
    fun buildSimilarUrlConstructsValidRequest() {
        val url = DawngasmApi.buildSimilarUrl(
            baseUrl = "https://example.com",
            postId = "post123",
            limit = 50,
        )
        assertEquals("https://example.com/similar?post_id=post123&limit=50", url.toString())
    }

    @Test
    fun parsesSimilarResponse() {
        val body = """
            {
                "posts": [
                    {
                        "id": "sim1",
                        "title": "Similar Track 1",
                        "subreddit": "gonewildaudio",
                        "author": "author_sim",
                        "score": 150,
                        "created": "2026-11-01 12:00:00",
                        "created_unix": 1730462400,
                        "distance": 0.25
                    }
                ]
            }
        """.trimIndent()

        val posts = DawngasmApi().parseSimilar(body)
        assertEquals(1, posts.size)
        assertEquals("sim1", posts[0].id)
        assertEquals("Similar Track 1", posts[0].title)
        assertEquals(150L, posts[0].score)
    }
}
