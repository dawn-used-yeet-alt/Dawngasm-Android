package dev.dawngasm.app.data

import dev.dawngasm.app.model.AudioHost
import dev.dawngasm.app.model.AudioLinks
import dev.dawngasm.app.model.SearchRequest
import dev.dawngasm.app.model.TagSuggestion
import dev.dawngasm.app.model.Track
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

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
    private val tagCache = ConcurrentHashMap<String, Pair<Long, List<TagSuggestion>>>()

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
    ): SearchPage = withContext(Dispatchers.IO) {
        if (page > MAX_PAGE_INDEX) return@withContext SearchPage(emptyList(), false, page)

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
        SearchPage(tracks.values.toList(), parsed.hasMore, page)
    }

    /**
     * Tag autocomplete backed by `/tags?prefix=`. Results are cached briefly so
     * typing and backspacing does not hammer the index.
     */
    suspend fun tagSuggestions(prefix: String, limit: Int = TAG_LIMIT): List<TagSuggestion> =
        withContext(Dispatchers.IO) {
            val key = "${prefix.trim().lowercase(Locale.ROOT)}|$limit"
            val now = System.currentTimeMillis()
            tagCache[key]?.takeIf { now - it.first < TAG_CACHE_TTL_MS }?.let {
                return@withContext it.second
            }

            val url = "$baseUrl/tags".toHttpUrl().newBuilder()
                .addQueryParameter("limit", limit.toString())
                .apply {
                    val clean = prefix.trim().lowercase(Locale.ROOT)
                    if (clean.isNotEmpty()) addQueryParameter("prefix", clean)
                }
                .build()
            val tags = parseTags(get(url))
            tagCache[key] = now to tags
            tags
        }

    private fun get(url: HttpUrl): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", Network.APP_USER_AGENT)
            .header("Accept", "application/json")
            .build()
        Network.client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException(apiError(response.code, body))
            return body
        }
    }

    private fun postJson(url: HttpUrl, payload: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", Network.APP_USER_AGENT)
            .header("Accept", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        Network.client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException(apiError(response.code, body))
            return body
        }
    }

    private fun fetchDetails(ids: List<String>): Map<String, Pair<String, String>> {
        if (ids.isEmpty()) return emptyMap()
        val placeholders = ids.joinToString(",") { "?" }
        val sql = "SELECT id, url, selftext FROM post WHERE id IN ($placeholders)"
        val payload = JSONObject()
            .put("sql", sql)
            .put("args", JSONArray(ids))
            .toString()
        val root = parseRoot(postJson("$baseUrl/query".toHttpUrl(), payload))
        val columns = root.optJSONArray("columns") ?: return emptyMap()
        val rows = root.optJSONArray("rows") ?: return emptyMap()
        val names = (0 until columns.length()).map { columns.optString(it) }
        val idCol = names.indexOf("id").takeIf { it >= 0 } ?: return emptyMap()
        val urlCol = names.indexOf("url").takeIf { it >= 0 } ?: return emptyMap()
        val textCol = names.indexOf("selftext").takeIf { it >= 0 } ?: return emptyMap()

        val details = HashMap<String, Pair<String, String>>(rows.length())
        for (index in 0 until rows.length()) {
            val row = rows.optJSONArray(index) ?: continue
            val id = row.optString(idCol)
            if (id.isNotBlank()) details[id] = row.optString(urlCol) to row.optString(textCol)
        }
        return details
    }

    internal fun parseSearch(body: String): ParsedSearch {
        val root = parseRoot(body)
        val columns = root.optJSONArray("columns")
            ?: throw ApiException("The index returned no columns")
        val rows = root.optJSONArray("rows")
            ?: throw ApiException("The index returned no rows")
        val names = (0 until columns.length()).map { columns.optString(it) }
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
        val posts = ArrayList<RawPost>(rows.length())
        for (index in 0 until rows.length()) {
            val row = rows.optJSONArray(index) ?: continue
            val id = row.optString(idCol)
            if (id.isBlank()) continue

            var resolvedHost: AudioHost? = null
            var resolvedAudioUrl: String? = null

            if (hasAudioCol) {
                val audioArr = row.optJSONArray(audioCol)
                if (audioArr == null || audioArr.length() == 0) {
                    // Posts where audio is empty ([]) are filtered out immediately
                    continue
                }
                for (aIdx in 0 until audioArr.length()) {
                    val audioObj = audioArr.optJSONObject(aIdx) ?: continue
                    val hStr = audioObj.optString("host")
                    val uStr = audioObj.optString("url")
                    val host = AudioHost.fromLabel(hStr).takeIf { it != AudioHost.UNKNOWN }
                        ?: AudioLinks.hostOf(uStr)
                    if (host != AudioHost.UNKNOWN && uStr.isNotBlank()) {
                        resolvedHost = host
                        resolvedAudioUrl = AudioLinks.cleanUrl(uStr)
                        break
                    }
                }
                if (resolvedAudioUrl == null) {
                    // No recognized/supported playable audio link in the audio array
                    continue
                }
            }

            posts += RawPost(
                id = id,
                title = row.optString(titleCol),
                author = if (authorCol >= 0) row.optString(authorCol) else "",
                subreddit = if (subredditCol >= 0) row.optString(subredditCol) else "",
                score = if (scoreCol >= 0) row.optLong(scoreCol, 0L) else 0L,
                createdUtc = if (createdCol >= 0) row.optLong(createdCol, 0L) else 0L,
                url = if (urlCol >= 0) row.optString(urlCol) else "",
                selftext = if (textCol >= 0) row.optString(textCol) else "",
                resolvedHost = resolvedHost,
                resolvedAudioUrl = resolvedAudioUrl,
            )
        }
        return ParsedSearch(
            posts = posts,
            hasMore = root.optBoolean("hasMore", false),
            needsBodies = !hasAudioCol && (urlCol < 0 || textCol < 0),
        )
    }

    private fun parseRoot(body: String): JSONObject {
        val root = runCatching { JSONObject(body) }
            .getOrElse { throw ApiException("The index returned invalid JSON") }
        val error = root.optString("error")
        if (error.isNotBlank() && error != "null") throw ApiException(error)
        return root
    }

    private fun apiError(status: Int, body: String): String {
        val root = runCatching { JSONObject(body) }.getOrNull()
        val message = root?.optString("error")?.takeIf { it.isNotBlank() } ?: "unexpected response"
        val retry = root?.optInt("retryAfterSeconds", 0) ?: 0
        return if (status == 429 && retry > 0) {
            "Rate limited — retry in ${retry}s ($message)"
        } else {
            "Index returned HTTP $status: $message"
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
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

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
        ): HttpUrl = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("page", (page + 1).toString())
            .addQueryParameter("sort", apiSort(sort))
            .addQueryParameter("window", apiWindow(window))
            .apply {
                if (query.isNotBlank()) addQueryParameter("text", query.trim())
                val included = includeTags.filter { it.isNotBlank() }.joinToString(",")
                if (included.isNotEmpty()) addQueryParameter("tags", included)
                val excluded = excludeTags.filter { it.isNotBlank() }.joinToString(",")
                if (excluded.isNotEmpty()) addQueryParameter("excludeTags", excluded)
                val includedHosts = hosts.filter { it.isNotBlank() }.joinToString(",")
                if (includedHosts.isNotEmpty()) addQueryParameter("hosts", includedHosts)
                val excludedHostsList = excludeHosts.filter { it.isNotBlank() }.joinToString(",")
                if (excludedHostsList.isNotEmpty()) addQueryParameter("excludeHosts", excludedHostsList)
            }
            .build()
        const val TAG_LIMIT = 12
        private const val TAG_CACHE_TTL_MS = 10 * 60 * 1000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun parseTags(body: String): List<TagSuggestion> {
            val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
            val array = root.optJSONArray("tags") ?: return emptyList()
            return buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val tag = item.optString("tag").trim()
                    if (tag.isNotEmpty()) add(TagSuggestion(tag, item.optLong("count", 0L)))
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
