package dev.dawngasm.app.data

import dev.dawngasm.app.model.AudioLinks
import dev.dawngasm.app.model.Track
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
 * when a deployment omits `url` and `selftext`, the same page is completed with
 * one `/query` request, exactly like the desktop client.
 */
class DawngasmApi(
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    suspend fun search(
        sort: String,
        window: String,
        query: String,
        blockedTags: List<String>,
        page: Int,
        limit: Int = PAGE_LIMIT,
    ): SearchPage = withContext(Dispatchers.IO) {
        if (page > MAX_PAGE_INDEX) return@withContext SearchPage(emptyList(), false, page)

        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("page", (page + 1).toString())
            .addQueryParameter("sort", apiSort(sort))
            .addQueryParameter("window", apiWindow(window))
            .apply {
                if (query.isNotBlank()) addQueryParameter("text", query.trim())
                val blocked = blockedTags.filter { it.isNotBlank() }.joinToString(",")
                if (blocked.isNotEmpty()) addQueryParameter("excludeTags", blocked)
            }
            .build()

        val parsed = parseSearch(get(url))
        val bodyMap = if (parsed.needsBodies && parsed.posts.isNotEmpty()) {
            fetchDetails(parsed.posts.map { it.id })
        } else {
            emptyMap()
        }

        val tracks = LinkedHashMap<String, Track>()
        for (post in parsed.posts) {
            if (tracks.containsKey(post.id)) continue
            val details = bodyMap[post.id]
            val postUrl = details?.first ?: post.url
            val selftext = details?.second ?: post.selftext
            val (host, audioUrl) = AudioLinks.find(postUrl, selftext) ?: continue
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

    private fun parseSearch(body: String): ParsedSearch {
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

        val posts = ArrayList<RawPost>(rows.length())
        for (index in 0 until rows.length()) {
            val row = rows.optJSONArray(index) ?: continue
            val id = row.optString(idCol)
            if (id.isBlank()) continue
            posts += RawPost(
                id = id,
                title = row.optString(titleCol),
                author = if (authorCol >= 0) row.optString(authorCol) else "",
                subreddit = if (subredditCol >= 0) row.optString(subredditCol) else "",
                score = if (scoreCol >= 0) row.optLong(scoreCol, 0L) else 0L,
                createdUtc = if (createdCol >= 0) row.optLong(createdCol, 0L) else 0L,
                url = if (urlCol >= 0) row.optString(urlCol) else "",
                selftext = if (textCol >= 0) row.optString(textCol) else "",
            )
        }
        return ParsedSearch(
            posts = posts,
            hasMore = root.optBoolean("hasMore", false),
            needsBodies = urlCol < 0 || textCol < 0,
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

    private data class RawPost(
        val id: String,
        val title: String,
        val author: String,
        val subreddit: String,
        val score: Long,
        val createdUtc: Long,
        val url: String,
        val selftext: String,
    )

    private data class ParsedSearch(
        val posts: List<RawPost>,
        val hasMore: Boolean,
        val needsBodies: Boolean,
    )

    companion object {
        const val DEFAULT_BASE_URL = "https://dawngasm.dawnusedyeet.workers.dev"
        const val PAGE_LIMIT = 100
        const val MAX_PAGE_INDEX = 99
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

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
