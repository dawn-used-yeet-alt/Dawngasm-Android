package dev.dawngasm.app.data

import android.content.Context
import android.net.Uri
import dev.dawngasm.app.model.AudioHost
import dev.dawngasm.app.model.Track
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class ResolveException(message: String) : Exception(message)

data class ResolvedAudio(
    val url: String,
    val headers: Map<String, String>,
    val extension: String,
)

/**
 * Turns a post page into a directly playable URL.
 *
 * soundgasm and whyp scrape a signed file URL from the page. audiochan needs a
 * cookie-gated play intent followed by a signed stream URL; the shared
 * [Network] cookie jar carries the cookie to the media requests.
 */
class AudioResolver(private val context: Context? = null) {
    private data class CacheEntry(val audio: ResolvedAudio, val createdAt: Long)

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    suspend fun resolve(track: Track): ResolvedAudio = withContext(Dispatchers.IO) {
        cache[track.id]?.takeIf { System.currentTimeMillis() - it.createdAt < CACHE_TTL_MS }
            ?.audio
            ?.let { return@withContext it }

        val resolved = when (track.host) {
            AudioHost.SOUNDGASM -> soundgasm(track)
            AudioHost.WHYP -> whyp(track)
            AudioHost.AUDIOCHAN -> audiochan(track)
            AudioHost.HOTAUDIO -> hotaudio(track)
            AudioHost.UNKNOWN -> throw ResolveException("Unsupported audio host")
        }
        cache[track.id] = CacheEntry(resolved, System.currentTimeMillis())
        resolved
    }

    fun clearCache() = cache.clear()

    private fun soundgasm(track: Track): ResolvedAudio {
        val html = get(track.pageUrl)
        val stream = parseSoundgasm(html)
            ?: throw ResolveException("soundgasm did not expose a stream link")
        return ResolvedAudio(
            url = stream,
            headers = browserHeaders(track.pageUrl),
            extension = extensionFromUrl(stream, "m4a"),
        )
    }

    private fun whyp(track: Track): ResolvedAudio {
        val html = get(track.pageUrl)
        val stream = parseWhyp(html)
            ?: throw ResolveException("whyp did not expose a stream link")
        return ResolvedAudio(
            url = stream,
            headers = browserHeaders(track.pageUrl),
            extension = extensionFromUrl(stream, "mp3"),
        )
    }

    private fun audiochan(track: Track): ResolvedAudio {
        val slug = parseAudiochanSlug(track.pageUrl)
            ?: throw ResolveException("audiochan slug not found")
        val apiHeaders = mapOf(
            "Origin" to AUDIOCHAN_ORIGIN,
            "Referer" to AUDIOCHAN_REFERER,
            "Accept" to "application/json",
        )

        var (status, body) = apiCall("$AUDIOCHAN_API/audios/slug/$slug", "GET", null, apiHeaders)
        if (status == 404 || status == 405) {
            val fallback = apiCall("$AUDIOCHAN_API/audios/slug/$slug", "POST", "", apiHeaders)
            status = fallback.first
            body = fallback.second
        }
        if (status != 200) {
            throw ResolveException("audiochan lookup returned HTTP $status")
        }

        // Some public uploads expose a ready-made CDN URL; use it when the
        // gated play-intent flow is unavailable.
        parseAudiochanFileUrl(body)?.let { fileUrl ->
            return ResolvedAudio(
                url = fileUrl,
                headers = browserHeaders(track.pageUrl),
                extension = extensionFromUrl(fileUrl, "m4a"),
            )
        }

        val audioId = parseJsonField(body, "id")
            ?: throw ResolveException("audiochan audio id not found")

        val intentResult = apiCall("$AUDIOCHAN_API/audios/$audioId/play-intent", "POST", "", apiHeaders)
        val playIntent = when (intentResult.first) {
            200 -> parseJsonField(intentResult.second, "play_intent")
            403 -> throw ResolveException(audiochanVerificationMessage)
            404 -> null
            else -> throw ResolveException("audiochan play intent returned HTTP ${intentResult.first}")
        }

        val streamPayload = playIntent?.let {
            JSONObject().put("play_intent", it).toString()
        }.orEmpty()
        val streamResult = apiCall(
            "$AUDIOCHAN_API/audios/$audioId/stream-url",
            "POST",
            streamPayload,
            apiHeaders,
        )
        if (streamResult.first == 403) throw ResolveException(audiochanVerificationMessage)
        if (streamResult.first != 200) {
            throw ResolveException("audiochan stream url returned HTTP ${streamResult.first}")
        }
        val streamUrl = parseJsonField(streamResult.second, "url")
            ?: throw ResolveException("audiochan stream url not found")

        return ResolvedAudio(
            url = streamUrl,
            headers = mapOf(
                "User-Agent" to Network.BROWSER_USER_AGENT,
                "Origin" to AUDIOCHAN_ORIGIN,
                "Referer" to AUDIOCHAN_REFERER,
            ),
            extension = extensionFromUrl(streamUrl, "m4a"),
        )
    }

    private fun hotaudio(track: Track): ResolvedAudio {
        val cacheRoot = context?.cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        val hotaudioDir = File(cacheRoot, "hotaudio").apply { mkdirs() }
        val targetFile = File(hotaudioDir, "${track.id}.m4a")
        if (!targetFile.exists() || targetFile.length() == 0L) {
            val tempFile = File(hotaudioDir, "${track.id}.${System.currentTimeMillis()}.tmp")
            try {
                net.hotaudio.extractor.downloadToFile(
                    pageUrl = track.pageUrl,
                    path = tempFile.absolutePath,
                    options = null,
                    progress = null,
                    cancel = null,
                )
                if (!tempFile.renameTo(targetFile)) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }
            } catch (error: Throwable) {
                tempFile.delete()
                val message = when (error) {
                    is net.hotaudio.extractor.FfiException.Failed -> {
                        if (error.code == "challenge") {
                            "hotaudio Cloudflare clearance expired or invalid — refresh in Settings"
                        } else {
                            "hotaudio error (${error.code}): ${error.detail}"
                        }
                    }
                    is net.hotaudio.extractor.FfiException.Panic -> "hotaudio error: ${error.detail}"
                    else -> error.message ?: "hotaudio extraction failed"
                }
                throw ResolveException(message)
            }
        }
        return ResolvedAudio(
            url = Uri.fromFile(targetFile).toString(),
            headers = emptyMap(),
            extension = "m4a",
        )
    }

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", Network.BROWSER_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,*/*")
            .build()
        Network.client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ResolveException("HTTP ${response.code} from ${request.url.host}")
            }
            return body
        }
    }

    private fun apiCall(
        url: String,
        method: String,
        body: String?,
        headers: Map<String, String>,
    ): Pair<Int, String> {
        val builder = Request.Builder().url(url).header("User-Agent", Network.BROWSER_USER_AGENT)
        headers.forEach { (name, value) -> builder.header(name, value) }
        if (method == "POST") {
            builder.post(
                (body ?: "").toRequestBody("application/json; charset=utf-8".toMediaType()),
            )
        }
        Network.client.newCall(builder.build()).execute().use { response ->
            return response.code to response.body?.string().orEmpty()
        }
    }

    private fun browserHeaders(referer: String): Map<String, String> = mapOf(
        "User-Agent" to Network.BROWSER_USER_AGENT,
        "Referer" to referer,
        "Accept" to "*/*",
    )

    companion object {
        private const val CACHE_TTL_MS = 5 * 60 * 1000L
        private const val AUDIOCHAN_ORIGIN = "https://audiochan.com"
        private const val AUDIOCHAN_REFERER = "https://audiochan.com/"
        private const val AUDIOCHAN_API = "https://api.audiochan.com"
        private const val audiochanVerificationMessage =
            "audiochan needs browser verification for this track"

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

        /** Reads `audioFile.url` from the audiochan slug response when present. */
        fun parseAudiochanFileUrl(json: String): String? {
            val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
            fun from(obj: JSONObject?): String? {
                val file = obj?.optJSONObject("audioFile")
                    ?: obj?.optJSONObject("audio_file")
                    ?: return null
                return file.optString("url").takeIf { it.isNotBlank() && it != "null" }
            }
            return from(root) ?: from(root.optJSONObject("data"))
        }

        fun parseJsonField(json: String, field: String): String? {
            val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
            fun value(obj: JSONObject): String? =
                obj.optString(field).takeIf { it.isNotBlank() && it != "null" }
            return value(root) ?: root.optJSONObject("data")?.let(::value)
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
    }
}
