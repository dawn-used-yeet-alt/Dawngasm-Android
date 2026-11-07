package dev.dawngasm.app.data

import android.content.Context
import android.net.Uri
import dev.dawngasm.app.model.AudioHost
import dev.dawngasm.app.model.Track
import java.io.File
import java.net.InetAddress
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

    private data class HotaudioEndpoint(val pageUrl: String, val apiBase: String)

    private fun isDnsOrTransientNetworkError(error: Throwable): Boolean {
        val detail = when (error) {
            is net.hotaudio.extractor.FfiException.Failed -> error.detail
            is net.hotaudio.extractor.FfiException.Panic -> error.detail
            else -> error.message.orEmpty()
        }
        val text = "${error.message.orEmpty()} $detail"
        return text.contains("failed to lookup address information", ignoreCase = true) ||
            text.contains("No address associated with hostname", ignoreCase = true) ||
            text.contains("Name or service not known", ignoreCase = true) ||
            text.contains("connection refused", ignoreCase = true) ||
            text.contains("connection reset", ignoreCase = true) ||
            text.contains("timeout", ignoreCase = true) ||
            text.contains("timed out", ignoreCase = true) ||
            text.contains("network is unreachable", ignoreCase = true)
    }

    private fun isRateLimitError(error: Throwable): Boolean {
        val detail = when (error) {
            is net.hotaudio.extractor.FfiException.Failed -> error.detail
            is net.hotaudio.extractor.FfiException.Panic -> error.detail
            else -> error.message.orEmpty()
        }
        val text = "${error.message.orEmpty()} $detail"
        return text.contains("429", ignoreCase = true) ||
            text.contains("too many requests", ignoreCase = true)
    }

    private fun warmDns(host: String): Boolean {
        return try {
            val addrs = InetAddress.getAllByName(host)
            addrs != null && addrs.isNotEmpty()
        } catch (_: Throwable) {
            false
        }
    }

    private fun hotaudio(track: Track): ResolvedAudio {
        val prefs = context?.let { AppPreferences(it) }
        // Ensure clearance from preferences is passed down to native FFI if available
        prefs?.hotaudioClearance()?.let { (cookie, ua) ->
            runCatching { net.hotaudio.extractor.setClearance(cookie, ua) }
        }

        val savedFamily = prefs?.hotaudioIpFamily()?.lowercase()?.takeIf { it == "ipv4" || it == "ipv6" }

        val cacheRoot = context?.cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        val hotaudioDir = File(cacheRoot, "hotaudio").apply { mkdirs() }
        val targetFile = File(hotaudioDir, "${track.id}.m4a")
        if (!targetFile.exists() || targetFile.length() == 0L) {
            val tempFile = File(hotaudioDir, "${track.id}.${System.currentTimeMillis()}.tmp")

            var normalizedUrl = track.pageUrl.trim()
            if (normalizedUrl.startsWith("http://", ignoreCase = true)) {
                normalizedUrl = "https" + normalizedUrl.substring(4)
            }

            // Warm up Android DNS resolution via JVM for apex and www domains
            val canResolveApex = warmDns("hotaudio.net")
            val canResolveWww = warmDns("www.hotaudio.net")

            val candidates = mutableListOf<HotaudioEndpoint>()
            if (normalizedUrl.contains("www.hotaudio.net", ignoreCase = true)) {
                candidates.add(HotaudioEndpoint(normalizedUrl, "https://www.hotaudio.net"))
                candidates.add(HotaudioEndpoint(normalizedUrl.replace("://www.hotaudio.net", "://hotaudio.net"), "https://hotaudio.net"))
            } else if (canResolveWww && !canResolveApex) {
                // Apex failed DNS resolution in JVM, but www succeeded: prioritize www
                candidates.add(HotaudioEndpoint(normalizedUrl.replace("://hotaudio.net", "://www.hotaudio.net"), "https://www.hotaudio.net"))
                candidates.add(HotaudioEndpoint(normalizedUrl, "https://hotaudio.net"))
            } else {
                candidates.add(HotaudioEndpoint(normalizedUrl, "https://hotaudio.net"))
                candidates.add(HotaudioEndpoint(normalizedUrl.replace("://hotaudio.net", "://www.hotaudio.net"), "https://www.hotaudio.net"))
            }

            // Dual-stack IP family matching: try preferred family first, then fallback
            val ipFamilies = listOfNotNull(
                savedFamily,
                if (savedFamily == "ipv4") "ipv6" else "ipv4",
                "any",
            ).distinct()

            var lastError: Throwable? = null
            var succeeded = false

            familyLoop@ for (family in ipFamilies) {
                runCatching { net.hotaudio.extractor.setIpFamily(family) }
                runCatching { android.system.Os.setenv("HOTAUDIO_IP_FAMILY", family, true) }

                for (endpoint in candidates) {
                    for (attempt in 1..2) {
                        tempFile.delete()
                        try {
                            val options = net.hotaudio.extractor.DownloadOptions(
                                trackId = null,
                                apiBase = endpoint.apiBase,
                                delayMs = 400u,
                                initialKeysJson = null,
                            )
                            net.hotaudio.extractor.downloadToFile(
                                pageUrl = endpoint.pageUrl,
                                path = tempFile.absolutePath,
                                options = options,
                                progress = null,
                                cancel = null,
                            )
                            if (!tempFile.renameTo(targetFile)) {
                                tempFile.copyTo(targetFile, overwrite = true)
                                tempFile.delete()
                            }
                            succeeded = true
                            if (family != "any") {
                                prefs?.saveHotaudioIpFamily(family)
                            }
                            break@familyLoop
                        } catch (error: Throwable) {
                            tempFile.delete()
                            lastError = error
                            val isDnsOrNetwork = isDnsOrTransientNetworkError(error)
                            val isChallenge = error is net.hotaudio.extractor.FfiException.Failed && error.code == "challenge"
                            val isRateLimit = isRateLimitError(error)

                            if (isChallenge) {
                                // Clearance rejected by Cloudflare — could be IP mismatch, try next IP family
                                break
                            }

                            if (isRateLimit) {
                                if (attempt < 2) {
                                    try {
                                        Thread.sleep(2500)
                                    } catch (_: InterruptedException) {}
                                    continue
                                }
                                try {
                                    Thread.sleep(1500)
                                } catch (_: InterruptedException) {}
                                break
                            }

                            if (isDnsOrNetwork) {
                                if (attempt < 2) {
                                    try {
                                        Thread.sleep(500)
                                    } catch (_: InterruptedException) {}
                                    continue
                                }
                            } else {
                                // Non-network / non-challenge / non-rate-limit fatal error
                                break@familyLoop
                            }
                        }
                    }
                }
            }

            if (!succeeded) {
                val err = lastError
                val message = when {
                    err is net.hotaudio.extractor.FfiException.Failed && err.code == "challenge" -> {
                        "hotaudio Cloudflare clearance expired or invalid — refresh in Settings"
                    }
                    err != null && isRateLimitError(err) -> {
                        "hotaudio rate limit reached (too many requests) — please wait a few seconds and try again"
                    }
                    err != null && isDnsOrTransientNetworkError(err) -> {
                        val detail = (err as? net.hotaudio.extractor.FfiException.Failed)?.detail
                            ?: err.message ?: err.toString()
                        "hotaudio DNS resolution failed ($detail). If your mobile carrier/ISP blocks hotaudio, enable Private DNS in Android Settings (e.g. set Private DNS to 'dns.google' or 'one.one.one.one')."
                    }
                    err is net.hotaudio.extractor.FfiException.Failed -> "hotaudio error (${err.code}): ${err.detail}"
                    err is net.hotaudio.extractor.FfiException.Panic -> "hotaudio error: ${err.detail}"
                    err != null -> err.message ?: err.toString()
                    else -> "hotaudio extraction failed"
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
