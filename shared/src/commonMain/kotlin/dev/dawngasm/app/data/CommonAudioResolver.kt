package dev.dawngasm.app.data

import dev.dawngasm.app.model.AudioHost
import dev.dawngasm.app.model.Track
import dev.dawngasm.app.util.Clock
import dev.dawngasm.app.util.PlatformLog
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ResolveException(message: String) : Exception(message)

data class ResolvedAudio(
    val url: String,
    val headers: Map<String, String>,
    val extension: String,
)

/**
 * Turns a post page into a directly playable URL.
 * Soundgasm and Whyp scrape stream links from the page via Ktor.
 * Audiochan resolves via the audiochan API.
 * Hotaudio delegates to the platform handler (Android native streamer).
 */
open class CommonAudioResolver(
    private val prefs: AppPreferences? = null,
    private val hotaudioHandler: (suspend (Track) -> ResolvedAudio)? = null,
) {
    private data class CacheEntry(val audio: ResolvedAudio, val createdAt: Long)

    private val cache = mutableMapOf<String, CacheEntry>()
    private val cacheMutex = Mutex()

    open suspend fun resolve(track: Track): ResolvedAudio {
        if (track.host != AudioHost.HOTAUDIO) {
            cacheMutex.withLock {
                val cached = cache[track.id]
                if (cached != null && Clock.nowEpochMillis() - cached.createdAt < CACHE_TTL_MS) {
                    PlatformLog.d("AudioResolver", "resolve cache hit track=${track.id} host=${track.host.label}")
                    return cached.audio
                }
            }
        }

        PlatformLog.i("AudioResolver", "resolve start track=${track.id} host=${track.host.label} page=${track.pageUrl}")
        val startedAt = Clock.nowEpochMillis()
        val resolved = when (track.host) {
            AudioHost.SOUNDGASM -> soundgasm(track)
            AudioHost.WHYP -> whyp(track)
            AudioHost.AUDIOCHAN -> audiochan(track)
            AudioHost.HOTAUDIO -> {
                hotaudioHandler?.invoke(track)
                    ?: throw ResolveException("hotaudio is not supported on this platform")
            }
            AudioHost.UNKNOWN -> throw ResolveException("Unsupported audio host")
        }
        PlatformLog.i(
            "AudioResolver",
            "resolve done track=${track.id} host=${track.host.label} " +
                "elapsed=${Clock.nowEpochMillis() - startedAt}ms url=${resolved.url}",
        )

        if (track.host != AudioHost.HOTAUDIO) {
            cacheMutex.withLock {
                cache[track.id] = CacheEntry(resolved, Clock.nowEpochMillis())
            }
        }
        return resolved
    }

    suspend fun clearCache() {
        cacheMutex.withLock {
            cache.clear()
        }
    }

    protected open suspend fun soundgasm(track: Track): ResolvedAudio =
        resolveSoundgasm(track.pageUrl)

    protected open suspend fun whyp(track: Track): ResolvedAudio =
        resolveWhyp(track.pageUrl)

    protected open suspend fun audiochan(track: Track): ResolvedAudio {
        val slug = parseAudiochanSlug(track.pageUrl)
            ?: throw ResolveException("audiochan slug not found")
        val clearance = prefs?.audiochanClearance()
        val userAgent = clearance?.second?.ifBlank { Network.BROWSER_USER_AGENT } ?: Network.BROWSER_USER_AGENT
        val clearanceCookie = clearance?.first.orEmpty()

        if (clearanceCookie.isNotBlank()) {
            Network.syncCookie(AUDIOCHAN_ORIGIN, clearanceCookie)
            Network.syncCookie(AUDIOCHAN_API, clearanceCookie)
        }

        val apiHeaders = mutableMapOf(
            "Origin" to AUDIOCHAN_ORIGIN,
            "Referer" to AUDIOCHAN_REFERER,
            "Accept" to "application/json",
        )
        if (clearanceCookie.isNotBlank()) {
            apiHeaders["Cookie"] = clearanceCookie
        }

        var (status, body) = apiCall("$AUDIOCHAN_API/audios/slug/$slug", "GET", null, apiHeaders, userAgent)
        if (status == 404 || status == 405) {
            val fallback = apiCall("$AUDIOCHAN_API/audios/slug/$slug", "POST", "", apiHeaders, userAgent)
            status = fallback.first
            body = fallback.second
        }
        if (status != 200) {
            throw ResolveException("audiochan lookup returned HTTP $status")
        }

        parseAudiochanFileUrl(body)?.let { fileUrl ->
            return ResolvedAudio(
                url = fileUrl,
                headers = browserHeaders(track.pageUrl),
                extension = extensionFromUrl(fileUrl, "m4a"),
            )
        }

        val audioId = parseJsonField(body, "id")
            ?: throw ResolveException("audiochan audio id not found")
        val fileId = parseAudiochanFileId(body)

        val verificationMessage = if (clearanceCookie.isNotBlank()) {
            "audiochan verification expired — pass the check again in Settings"
        } else {
            audiochanVerificationMessage
        }

        val intentBody = fileId?.let { "{\"file_id\":\"$it\"}" } ?: ""
        val intentResult = apiCall("$AUDIOCHAN_API/audios/$audioId/play-intent", "POST", intentBody, apiHeaders, userAgent)
        val playIntent = when (intentResult.first) {
            200 -> parseJsonField(intentResult.second, "play_intent")
            403 -> throw ResolveException(verificationMessage)
            404 -> null
            else -> throw ResolveException("audiochan play intent returned HTTP ${intentResult.first}")
        }

        val streamPayload = buildString {
            append("{")
            var first = true
            if (playIntent != null) {
                append("\"play_intent\":\"$playIntent\"")
                first = false
            }
            if (fileId != null) {
                if (!first) append(",")
                append("\"file_id\":\"$fileId\"")
            }
            append("}")
        }
        val streamResult = apiCall(
            "$AUDIOCHAN_API/audios/$audioId/stream-url",
            "POST",
            streamPayload,
            apiHeaders,
            userAgent,
        )
        if (streamResult.first == 403) throw ResolveException(verificationMessage)
        if (streamResult.first != 200) {
            throw ResolveException("audiochan stream url returned HTTP ${streamResult.first}")
        }
        val streamUrl = parseJsonField(streamResult.second, "url")
            ?: throw ResolveException("audiochan stream url not found")

        val mediaHeaders = mutableMapOf(
            "User-Agent" to userAgent,
            "Origin" to AUDIOCHAN_ORIGIN,
            "Referer" to AUDIOCHAN_REFERER,
        )
        if (clearanceCookie.isNotBlank()) {
            mediaHeaders["Cookie"] = clearanceCookie
        }

        return ResolvedAudio(
            url = streamUrl,
            headers = mediaHeaders,
            extension = extensionFromUrl(streamUrl, "m4a"),
        )
    }

    companion object {
        private const val CACHE_TTL_MS = 5 * 60 * 1000L
        private const val AUDIOCHAN_ORIGIN = "https://audiochan.com"
        private const val AUDIOCHAN_REFERER = "https://audiochan.com/"
        private const val AUDIOCHAN_API = "https://api.audiochan.com"
        const val audiochanVerificationMessage =
            "audiochan needs browser verification — pass the check in Settings"

        private val soundgasmPattern = Regex(
            """(?:m4a|mp3|ogg)\s*:\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        )
        private val soundgasmDirect = Regex(
            """https?://(?:media\.)?soundgasm\.net/sounds/[^"'\s<>]+""",
            RegexOption.IGNORE_CASE,
        )
        private val whypMp3 = Regex(
            """https://cdn\.whyp\.it/[0-9a-fA-F\-]+\.mp3(?:\?[^"'\s,<>]*)?""",
        )
        private val whypFlac = Regex(
            """https://cdn\.whyp\.it/[0-9a-fA-F\-]+\.flac(?:\?[^"'\s,<>]*)?""",
        )
        private val audiochanSlug = Regex(
            """(?i)audiochan\.com/(?:embed/)?(?:a|s)/([a-zA-Z0-9_-]+)""",
        )

        suspend fun resolveSoundgasm(pageUrl: String): ResolvedAudio {
            val html = fetchHtml(pageUrl)
            val stream = parseSoundgasm(html)
                ?: throw ResolveException("soundgasm did not expose a stream link")
            return ResolvedAudio(
                url = stream,
                headers = browserHeaders(pageUrl),
                extension = extensionFromUrl(stream, "m4a"),
            )
        }

        suspend fun resolveWhyp(pageUrl: String): ResolvedAudio {
            val html = fetchHtml(pageUrl)
            val stream = parseWhyp(html)
                ?: throw ResolveException("whyp did not expose a stream link")
            return ResolvedAudio(
                url = stream,
                headers = browserHeaders(pageUrl),
                extension = extensionFromUrl(stream, "mp3"),
            )
        }

        fun parseSoundgasm(html: String): String? =
            soundgasmPattern.find(html)?.groupValues?.getOrNull(1)
                ?: soundgasmDirect.find(html)?.value

        fun parseWhyp(html: String): String? {
            val unescaped = html
                .replace("\\u002F", "/")
                .replace("\\u002f", "/")
                .replace("\\u0026", "&")
                .replace("\\/", "/")
            return whypMp3.find(unescaped)?.value ?: whypFlac.find(unescaped)?.value
        }

        fun parseAudiochanSlug(text: String): String? =
            audiochanSlug.find(text)?.groupValues?.getOrNull(1)

        /** Reads `audioFile.id` from the audiochan slug response when present. */
        fun parseAudiochanFileId(json: String): String? {
            val element = runCatching { Json.parseToJsonElement(json) }.getOrNull() ?: return null
            fun from(obj: JsonObject?): String? {
                val file = obj?.get("audioFile")?.let { runCatching { it.jsonObject }.getOrNull() }
                    ?: obj?.get("audio_file")?.let { runCatching { it.jsonObject }.getOrNull() }
                    ?: return null
                val id = file["id"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                return id?.takeIf { it.isNotBlank() && it != "null" }
            }
            val rootObj = runCatching { element.jsonObject }.getOrNull() ?: return null
            return from(rootObj) ?: runCatching { from(rootObj["data"]?.jsonObject) }.getOrNull()
        }

        /** Reads `audioFile.url` from the audiochan slug response when present. */
        fun parseAudiochanFileUrl(json: String): String? {
            val element = runCatching { Json.parseToJsonElement(json) }.getOrNull() ?: return null
            fun from(obj: JsonObject?): String? {
                val file = obj?.get("audioFile")?.let { runCatching { it.jsonObject }.getOrNull() }
                    ?: obj?.get("audio_file")?.let { runCatching { it.jsonObject }.getOrNull() }
                    ?: return null
                val url = file["url"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                return url?.takeIf { it.isNotBlank() && it != "null" }
            }
            val rootObj = runCatching { element.jsonObject }.getOrNull() ?: return null
            return from(rootObj) ?: runCatching { from(rootObj["data"]?.jsonObject) }.getOrNull()
        }

        fun parseJsonField(json: String, field: String): String? {
            val element = runCatching { Json.parseToJsonElement(json) }.getOrNull() ?: return null
            fun value(obj: JsonObject): String? {
                val prim = obj[field]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                return prim?.takeIf { it.isNotBlank() && it != "null" }
            }
            val rootObj = runCatching { element.jsonObject }.getOrNull() ?: return null
            return value(rootObj) ?: runCatching { rootObj["data"]?.jsonObject?.let(::value) }.getOrNull()
        }

        fun extensionFromUrl(url: String, fallback: String): String {
            val path = url.substringBefore('?').substringBefore('#').lowercase()
            val candidate = path.substringAfterLast('.', "")
            return if (candidate in setOf("mp3", "m4a", "flac", "ogg", "opus", "wav", "aac")) {
                candidate
            } else {
                fallback
            }
        }

        private suspend fun fetchHtml(url: String): String {
            val response = Network.httpClient.get(url) {
                header("User-Agent", Network.BROWSER_USER_AGENT)
                header("Accept", "text/html,application/xhtml+xml,*/*")
            }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw ResolveException("HTTP ${response.status.value} from $url")
            }
            return body
        }

        private suspend fun apiCall(
            url: String,
            method: String,
            body: String?,
            headers: Map<String, String>,
            userAgent: String = Network.BROWSER_USER_AGENT,
        ): Pair<Int, String> {
            val response = if (method == "POST") {
                Network.httpClient.post(url) {
                    header("User-Agent", userAgent)
                    headers.forEach { (name, value) -> header(name, value) }
                    if (body != null) {
                        setBody(body)
                        contentType(ContentType.Application.Json)
                    }
                }
            } else {
                Network.httpClient.get(url) {
                    header("User-Agent", userAgent)
                    headers.forEach { (name, value) -> header(name, value) }
                }
            }
            return response.status.value to response.bodyAsText()
        }

        private fun browserHeaders(referer: String): Map<String, String> = mapOf(
            "User-Agent" to Network.BROWSER_USER_AGENT,
            "Referer" to referer,
            "Accept" to "*/*",
        )
    }
}
