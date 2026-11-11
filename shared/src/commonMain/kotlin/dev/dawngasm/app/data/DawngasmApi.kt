package dev.dawngasm.app.data

import dev.dawngasm.app.model.AudioHost
import dev.dawngasm.app.model.AudioLinks
import dev.dawngasm.app.model.SearchRequest
import dev.dawngasm.app.model.TagSuggestion
import dev.dawngasm.app.model.Track
import dev.dawngasm.app.util.Clock
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.path
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

class ApiException(message: String) : Exception(message)

data class SearchPage(
    val tracks: List<Track>,
    val hasMore: Boolean,
    val page: Int,
)

/**
 * Read-only client for the Dawngasm index. `/search` carries the post metadata;
 * when a deployment includes the `audio` column, playable links and hosts are extracted
 * directly without secondary requests. When `audio` or `url`/`selftext` are absent,
 * the page is completed with one `/query` request, exactly like the desktop client.
 *
 * For raw queries sent to `/query`, the schema includes:
 *   post(id, subreddit, author, title, score, created_unix, url, selftext)
 *   post_audio(post_id, host, url, position) where position is the play priority (0 first).
 */
class DawngasmApi(
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    private val tagCache = mutableMapOf<String, Pair<Long, List<TagSuggestion>>>()
    private val tagCacheMutex = Mutex()

    suspend fun search(
        request: SearchRequest,
        page: Int,
        limit: Int = PAGE_LIMIT,
    ): SearchPage = search(
        sort = request.sort,
        window = request.window,
        query = request.text,
        includeTags = request.includeTags,
        excludeTags = request.excludeTags,
        page = page,
        limit = limit,
        hosts = request.hosts,
        excludeHosts = request.excludeHosts,
    )

    suspend fun search(
        sort: String,
        window: String,
        query: String,
        includeTags: List<String>,
        excludeTags: List<String>,
        page: Int,
        limit: Int = PAGE_LIMIT,
        hosts: List<String> = emptyList(),
        excludeHosts: List<String> = emptyList(),
    ): SearchPage {
        if (page > MAX_PAGE_INDEX) return SearchPage(emptyList(), false, page)

        val url = buildSearchUrl(
            baseUrl = baseUrl,
            sort = sort,
            window = window,
            query = query,
            includeTags = includeTags,
            excludeTags = excludeTags,
            page = page,
            limit = limit,
            hosts = hosts,
            excludeHosts = excludeHosts,
        )

        val parsed = parseSearch(get(url))
        val bodyMap = if (parsed.needsBodies && parsed.posts.isNotEmpty()) {
            fetchDetails(parsed.posts.map { it.id })
        } else {
            emptyMap()
        }

        val tracks = LinkedHashMap<String, Track>()
        for (post in parsed.posts) {
            if (tracks.containsKey(post.id)) continue
            val (host, audioUrl) = if (post.resolvedHost != null && post.resolvedAudioUrl != null) {
                post.resolvedHost to post.resolvedAudioUrl
            } else {
                val details = bodyMap[post.id]
                val postUrl = details?.first ?: post.url
                val selftext = details?.second ?: post.selftext
                AudioLinks.find(postUrl, selftext) ?: continue
            }
            tracks[post.id] = Track(
                id = post.id,
                title = post.title.ifBlank { "Untitled post" },
                author = post.author,
                host = host,
                pageUrl = audioUrl,
                subreddit = post.subreddit,
                score = post.score,
                createdUtc = post.createdUtc,
            )
        }
        return SearchPage(tracks.values.toList(), parsed.hasMore, page)
    }

    /**
     * Tag autocomplete backed by `/tags?prefix=`. Results are cached briefly so
     * typing and backspacing does not hammer the index.
     */
    suspend fun tagSuggestions(prefix: String, limit: Int = TAG_LIMIT): List<TagSuggestion> {
        val key = "${prefix.trim().lowercase()}|$limit"
        val now = Clock.nowEpochMillis()
        tagCacheMutex.withLock {
            val cached = tagCache[key]
            if (cached != null && now - cached.first < TAG_CACHE_TTL_MS) {
                return cached.second
            }
        }

        val url = URLBuilder(baseUrl).apply {
            path("tags")
            parameters.append("limit", limit.toString())
            val clean = prefix.trim().lowercase()
            if (clean.isNotEmpty()) parameters.append("prefix", clean)
        }.build()

        val tags = parseTags(get(url))
        tagCacheMutex.withLock {
            tagCache[key] = now to tags
        }
        return tags
    }

    /**
     * Semantically similar posts backed by `/similar?post_id=`.
     */
    suspend fun similar(
        postId: String,
        limit: Int = PAGE_LIMIT,
    ): List<Track> {
        val url = buildSimilarUrl(
            baseUrl = baseUrl,
            postId = postId,
            limit = limit,
        )

        val parsed = parseSimilar(get(url))
        if (parsed.isEmpty()) return emptyList()

        val audioMap = fetchAudioForPosts(parsed.map { it.id })

        val tracks = LinkedHashMap<String, Track>()
        for (post in parsed) {
            if (tracks.containsKey(post.id)) continue
            val (host, audioUrl) = audioMap[post.id] ?: continue
            tracks[post.id] = Track(
                id = post.id,
                title = post.title.ifBlank { "Untitled post" },
                author = post.author,
                host = host,
                pageUrl = audioUrl,
                subreddit = post.subreddit,
                score = post.score,
                createdUtc = post.createdUtc,
            )
        }
        return tracks.values.toList()
    }

    private suspend fun get(url: Url): String {
        val response = Network.httpClient.get(url) {
            header("User-Agent", Network.APP_USER_AGENT)
            header("Accept", "application/json")
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) throw ApiException(apiError(response.status.value, body))
        return body
    }

    private suspend fun postJson(url: Url, payload: String): String {
        val response = Network.httpClient.post(url) {
            header("User-Agent", Network.APP_USER_AGENT)
            header("Accept", "application/json")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) throw ApiException(apiError(response.status.value, body))
        return body
    }

    private suspend fun fetchDetails(ids: List<String>): Map<String, Pair<String, String>> {
        if (ids.isEmpty()) return emptyMap()
        val placeholders = ids.joinToString(",") { "?" }
        val sql = "SELECT id, url, selftext FROM post WHERE id IN ($placeholders)"
        val payload = buildJsonObject {
            put("sql", sql)
            put("args", buildJsonArray {
                for (id in ids) add(JsonPrimitive(id))
            })
        }.toString()
        val root = parseRoot(postJson(Url("$baseUrl/query"), payload))
        val columns = root["columns"] as? JsonArray ?: return emptyMap()
        val rows = root["rows"] as? JsonArray ?: return emptyMap()
        val names = columns.map { runCatching { it.jsonPrimitive.content }.getOrDefault("") }
        val idCol = names.indexOf("id").takeIf { it >= 0 } ?: return emptyMap()
        val urlCol = names.indexOf("url").takeIf { it >= 0 } ?: return emptyMap()
        val textCol = names.indexOf("selftext").takeIf { it >= 0 } ?: return emptyMap()

        val details = HashMap<String, Pair<String, String>>(rows.size)
        for (rowElement in rows) {
            val row = rowElement as? JsonArray ?: continue
            val id = runCatching { row.getOrNull(idCol)?.jsonPrimitive?.content }.getOrNull().orEmpty()
            val url = runCatching { row.getOrNull(urlCol)?.jsonPrimitive?.content }.getOrNull().orEmpty()
            val text = runCatching { row.getOrNull(textCol)?.jsonPrimitive?.content }.getOrNull().orEmpty()
            if (id.isNotBlank()) details[id] = url to text
        }
        return details
    }

    private suspend fun fetchAudioForPosts(ids: List<String>): Map<String, Pair<AudioHost, String>> {
        if (ids.isEmpty()) return emptyMap()
        val result = HashMap<String, Pair<AudioHost, String>>(ids.size)

        val placeholders = ids.joinToString(",") { "?" }
        val sql = "SELECT post_id, host, url FROM post_audio WHERE post_id IN ($placeholders) ORDER BY position ASC"
        val payload = buildJsonObject {
            put("sql", sql)
            put("args", buildJsonArray {
                for (id in ids) add(JsonPrimitive(id))
            })
        }.toString()
        runCatching {
            val root = parseRoot(postJson(Url("$baseUrl/query"), payload))
            val columns = root["columns"] as? JsonArray ?: return@runCatching
            val rows = root["rows"] as? JsonArray ?: return@runCatching
            val names = columns.map { runCatching { it.jsonPrimitive.content }.getOrDefault("") }
            val idCol = names.indexOf("post_id").takeIf { it >= 0 } ?: return@runCatching
            val hostCol = names.indexOf("host").takeIf { it >= 0 } ?: return@runCatching
            val urlCol = names.indexOf("url").takeIf { it >= 0 } ?: return@runCatching

            for (rowElement in rows) {
                val row = rowElement as? JsonArray ?: continue
                val id = runCatching { row.getOrNull(idCol)?.jsonPrimitive?.content }.getOrNull().orEmpty()
                if (id.isBlank() || result.containsKey(id)) continue
                val hostStr = runCatching { row.getOrNull(hostCol)?.jsonPrimitive?.content }.getOrNull().orEmpty()
                val urlStr = runCatching { row.getOrNull(urlCol)?.jsonPrimitive?.content }.getOrNull().orEmpty()
                val host = AudioHost.fromLabel(hostStr).takeIf { it != AudioHost.UNKNOWN }
                    ?: AudioLinks.hostOf(urlStr)
                if (host != AudioHost.UNKNOWN && urlStr.isNotBlank()) {
                    result[id] = host to AudioLinks.cleanUrl(urlStr)
                }
            }
        }

        val missing = ids.filterNot { result.containsKey(it) }
        if (missing.isNotEmpty()) {
            val details = fetchDetails(missing)
            for (id in missing) {
                val pair = details[id] ?: continue
                val resolved = AudioLinks.find(pair.first, pair.second) ?: continue
                result[id] = resolved
            }
        }

        return result
    }

    internal fun parseSearch(body: String): ParsedSearch {
        val root = parseRoot(body)
        val columns = root["columns"] as? JsonArray
            ?: throw ApiException("The index returned no columns")
        val rows = root["rows"] as? JsonArray
            ?: throw ApiException("The index returned no rows")
        val names = columns.map { runCatching { it.jsonPrimitive.content }.getOrDefault("") }
        val idCol = names.indexOf("id").takeIf { it >= 0 }
            ?: throw ApiException("The index returned no id column")
        val titleCol = names.indexOf("title").takeIf { it >= 0 }
            ?: throw ApiException("The index returned no title column")
        val authorCol = names.indexOf("author")
        val subredditCol = names.indexOf("subreddit")
        val scoreCol = names.indexOf("score")
        val createdCol = names.indexOf("created_unix")
        val urlCol = names.indexOf("url")
        val textCol = names.indexOf("selftext")
        val audioCol = names.indexOf("audio")

        val hasAudioCol = audioCol >= 0
        val posts = ArrayList<RawPost>(rows.size)
        for (rowElement in rows) {
            val row = rowElement as? JsonArray ?: continue
            fun strAt(col: Int): String {
                if (col < 0 || col >= row.size) return ""
                return runCatching { row[col].jsonPrimitive.content }.getOrDefault("")
            }
            fun longAt(col: Int, default: Long = 0L): Long {
                if (col < 0 || col >= row.size) return default
                return runCatching { row[col].jsonPrimitive.longOrNull }.getOrNull() ?: default
            }

            val id = strAt(idCol)
            if (id.isBlank()) continue

            var resolvedHost: AudioHost? = null
            var resolvedAudioUrl: String? = null

            if (hasAudioCol) {
                val audioArr = row.getOrNull(audioCol) as? JsonArray
                if (audioArr == null || audioArr.isEmpty()) {
                    continue
                }
                for (item in audioArr) {
                    val audioObj = item as? JsonObject ?: continue
                    val hStr = runCatching { audioObj["host"]?.jsonPrimitive?.content }.getOrNull().orEmpty()
                    val uStr = runCatching { audioObj["url"]?.jsonPrimitive?.content }.getOrNull().orEmpty()
                    val host = AudioHost.fromLabel(hStr).takeIf { it != AudioHost.UNKNOWN }
                        ?: AudioLinks.hostOf(uStr)
                    if (host != AudioHost.UNKNOWN && uStr.isNotBlank()) {
                        resolvedHost = host
                        resolvedAudioUrl = AudioLinks.cleanUrl(uStr)
                        break
                    }
                }
                if (resolvedAudioUrl == null) {
                    continue
                }
            }

            posts += RawPost(
                id = id,
                title = strAt(titleCol),
                author = if (authorCol >= 0) strAt(authorCol) else "",
                subreddit = if (subredditCol >= 0) strAt(subredditCol) else "",
                score = if (scoreCol >= 0) longAt(scoreCol, 0L) else 0L,
                createdUtc = if (createdCol >= 0) longAt(createdCol, 0L) else 0L,
                url = if (urlCol >= 0) strAt(urlCol) else "",
                selftext = if (textCol >= 0) strAt(textCol) else "",
                resolvedHost = resolvedHost,
                resolvedAudioUrl = resolvedAudioUrl,
            )
        }
        val hasMore = root["hasMore"]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() } ?: false
        return ParsedSearch(
            posts = posts,
            hasMore = hasMore,
            needsBodies = !hasAudioCol && (urlCol < 0 || textCol < 0),
        )
    }

    internal fun parseSimilar(body: String): List<RawPost> {
        val root = parseRoot(body)
        val postsArray = root["posts"] as? JsonArray ?: return emptyList()
        val posts = ArrayList<RawPost>(postsArray.size)
        for (item in postsArray) {
            val obj = item as? JsonObject ?: continue
            val id = runCatching { obj["id"]?.jsonPrimitive?.content }.getOrNull().orEmpty()
            if (id.isBlank()) continue
            posts += RawPost(
                id = id,
                title = runCatching { obj["title"]?.jsonPrimitive?.content }.getOrNull().orEmpty(),
                author = runCatching { obj["author"]?.jsonPrimitive?.content }.getOrNull().orEmpty(),
                subreddit = runCatching { obj["subreddit"]?.jsonPrimitive?.content }.getOrNull().orEmpty(),
                score = runCatching { obj["score"]?.jsonPrimitive?.longOrNull }.getOrNull() ?: 0L,
                createdUtc = runCatching { obj["created_unix"]?.jsonPrimitive?.longOrNull }.getOrNull() ?: 0L,
                url = "",
                selftext = "",
            )
        }
        return posts
    }

    private fun parseRoot(body: String): JsonObject {
        val element = runCatching { Json.parseToJsonElement(body) }
            .getOrElse { throw ApiException("The index returned invalid JSON") }
        val root = element as? JsonObject
            ?: throw ApiException("The index returned invalid JSON")
        val error = root["error"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
        if (!error.isNullOrBlank() && error != "null") throw ApiException(error)
        return root
    }

    private fun apiError(status: Int, body: String): String {
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        val message = root?.get("error")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            ?.takeIf { it.isNotBlank() } ?: "unexpected response"
        val retry = root?.get("retryAfterSeconds")?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() } ?: 0
        return when {
            status == 429 && retry > 0 -> "Rate limited — retry in ${retry}s ($message)"
            status == 404 -> "No similar posts found for this track"
            else -> "Index returned HTTP $status: $message"
        }
    }

    internal data class RawPost(
        val id: String,
        val title: String,
        val author: String,
        val subreddit: String,
        val score: Long,
        val createdUtc: Long,
        val url: String,
        val selftext: String,
        val resolvedHost: AudioHost? = null,
        val resolvedAudioUrl: String? = null,
    )

    internal data class ParsedSearch(
        val posts: List<RawPost>,
        val hasMore: Boolean,
        val needsBodies: Boolean,
    )

    companion object {
        const val DEFAULT_BASE_URL = "https://dawngasm.dawnusedyeet.workers.dev"
        const val PAGE_LIMIT = 100
        const val MAX_PAGE_INDEX = 99
        const val TAG_LIMIT = 12
        private const val TAG_CACHE_TTL_MS = 10 * 60 * 1000L

        fun buildSearchUrl(
            baseUrl: String = DEFAULT_BASE_URL,
            sort: String = "top",
            window: String = "all",
            query: String = "",
            includeTags: List<String> = emptyList(),
            excludeTags: List<String> = emptyList(),
            page: Int = 0,
            limit: Int = PAGE_LIMIT,
            hosts: List<String> = emptyList(),
            excludeHosts: List<String> = emptyList(),
        ): Url = URLBuilder(baseUrl).apply {
            path("search")
            parameters.append("limit", limit.toString())
            parameters.append("page", (page + 1).toString())
            parameters.append("sort", apiSort(sort))
            parameters.append("window", apiWindow(window))
            if (query.isNotBlank()) parameters.append("text", query.trim())
            val included = includeTags.filter { it.isNotBlank() }.joinToString(",")
            if (included.isNotEmpty()) parameters.append("tags", included)
            val excluded = excludeTags.filter { it.isNotBlank() }.joinToString(",")
            if (excluded.isNotEmpty()) parameters.append("excludeTags", excluded)
            val includedHosts = hosts.filter { it.isNotBlank() }.joinToString(",")
            if (includedHosts.isNotEmpty()) parameters.append("hosts", includedHosts)
            val excludedHostsList = excludeHosts.filter { it.isNotBlank() }.joinToString(",")
            if (excludedHostsList.isNotEmpty()) parameters.append("excludeHosts", excludedHostsList)
        }.build()

        fun buildSimilarUrl(
            baseUrl: String = DEFAULT_BASE_URL,
            postId: String,
            limit: Int = PAGE_LIMIT,
        ): Url = URLBuilder(baseUrl).apply {
            path("similar")
            parameters.append("post_id", postId)
            parameters.append("limit", limit.coerceIn(1, 200).toString())
        }.build()

        fun parseTags(body: String): List<TagSuggestion> {
            val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
            val array = root["tags"] as? JsonArray ?: return emptyList()
            return buildList {
                for (item in array) {
                    val obj = item as? JsonObject ?: continue
                    val tag = runCatching { obj["tag"]?.jsonPrimitive?.content?.trim() }.getOrNull().orEmpty()
                    val count = runCatching { obj["count"]?.jsonPrimitive?.longOrNull }.getOrNull() ?: 0L
                    if (tag.isNotEmpty()) add(TagSuggestion(tag, count))
                }
            }
        }

        fun apiSort(sort: String): String = when (sort) {
            "new", "relevance" -> sort
            else -> "top"
        }

        fun apiWindow(window: String): String = when (window) {
            "day", "1d" -> "1d"
            "week", "7d" -> "7d"
            "month", "year", "all" -> window
            else -> {
                val days = Regex("""^(\d{1,5})d$""")
                    .find(window)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                if (days != null && days in 1..36500) "${days}d" else "all"
            }
        }
    }
}
