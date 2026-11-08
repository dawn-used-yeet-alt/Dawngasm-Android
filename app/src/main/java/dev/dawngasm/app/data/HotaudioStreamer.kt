package dev.dawngasm.app.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.hotaudio.extractor.CancellationToken
import net.hotaudio.extractor.DownloadOptions
import net.hotaudio.extractor.Progress
import net.hotaudio.extractor.ProgressListener
import net.hotaudio.extractor.downloadToFile

data class HotaudioStreamSpec(
    val trackId: String,
    val pageUrl: String,
    val file: File,
)

class HotaudioStreamState internal constructor(
    val spec: HotaudioStreamSpec,
) {
    @Volatile var bytesWritten: Long = 0L
    @Volatile var done: Boolean = false
    @Volatile var error: Throwable? = null
    @Volatile var family: String? = null
    @Volatile var startedAt: Long = System.currentTimeMillis()
    @Volatile var firstByteAt: Long = 0L
    @Volatile var progressPhase: String = ""
    @Volatile var progressLoaded: Long = 0L
    @Volatile var progressTotal: Long = 0L

    /** Set by the stall watchdog so the attempt is retried, not user-cancelled. */
    @Volatile var abortReason: String? = null

    /** Incremented at the start of every IP-family attempt. */
    @Volatile var generation: Int = 0
}

/**
 * Downloads a hotaudio track once and exposes the growing `.m4a` file.
 *
 * hotaudio returns concatenated fragmented MP4, so the first fragment is
 * already playable. [HotaudioDataSource] reads the file while this object keeps
 * appending to it, which means playback starts after the first segment instead
 * of after the whole track has downloaded.
 */
object HotaudioStreamer {
    const val SCHEME = "hotaudio"

    private const val POLL_MS = 50L
    private const val FIRST_BYTES_TIMEOUT_MS = 120_000L
    private const val COMPLETE_TIMEOUT_MS = 15 * 60_000L

    /** DNS/connection hiccups are usually short; retry the same family first. */
    private const val TRANSIENT_ATTEMPTS = 3
    private const val TRANSIENT_BACKOFF_START_MS = 1_500L
    private const val TRANSIENT_BACKOFF_MAX_MS = 9_000L

    /**
     * hotaudio rate-limits the listen/key-paging endpoint per network. Waiting
     * is the only fix, and switching IP families breaks a saved clearance, so
     * back off on the same family instead.
     */
    private const val RATE_LIMIT_ATTEMPTS = 3
    private const val RATE_LIMIT_BACKOFF_MS = 20_000L

    /**
     * Abort an attempt that stops writing for this long and retry it. A stall
     * usually means the server is tarpitting a key page, and the HTTP client
     * retries for up to a couple of minutes before giving up, so this has to be
     * long enough not to restart a download that is merely waiting.
     */
    private const val STALL_TIMEOUT_MS = 45_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val states = ConcurrentHashMap<String, HotaudioStreamState>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val tokens = ConcurrentHashMap<String, CancellationToken>()

    fun normalizePageUrl(raw: String): String {
        var url = raw.trim()
        if (url.startsWith("http://", ignoreCase = true)) {
            url = "https" + url.substring(4)
        }
        return url.replace("://www.hotaudio.net", "://hotaudio.net")
    }

    fun spec(context: Context?, trackId: String, pageUrl: String): HotaudioStreamSpec {
        val root = context?.cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        val dir = File(root, "hotaudio").apply { mkdirs() }
        return HotaudioStreamSpec(
            trackId = trackId,
            pageUrl = normalizePageUrl(pageUrl),
            file = File(dir, "$trackId.m4a.part"),
        )
    }

    fun streamUri(trackId: String): Uri = Uri.parse("$SCHEME://stream/${Uri.encode(trackId)}")

    fun trackIdFrom(uri: Uri): String? {
        if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null
        return uri.lastPathSegment?.takeIf { it.isNotBlank() }
    }

    /** The live state, or a completed state restored from the `.done` marker. */
    fun stateFor(trackId: String, context: Context?): HotaudioStreamState? {
        states[trackId]?.let { return it }

        val spec = spec(context, trackId, "")
        val marker = markerFor(spec)
        if (marker.exists() && spec.file.exists() && spec.file.length() > 0L) {
            val state = HotaudioStreamState(spec).apply {
                bytesWritten = spec.file.length()
                done = true
                firstByteAt = System.currentTimeMillis()
            }
            states[trackId] = state
            HotaudioLog.i("restored completed stream track=$trackId bytes=${state.bytesWritten}")
            return state
        }
        return null
    }

    fun ensureStarted(spec: HotaudioStreamSpec, prefs: AppPreferences?): HotaudioStreamState {
        synchronized(this) {
            states[spec.trackId]?.let { existing ->
                if (!existing.done || existing.error == null) {
                    HotaudioLog.d(
                        "stream already active track=${spec.trackId} " +
                            "bytes=${existing.bytesWritten} done=${existing.done}",
                    )
                    return existing
                }
                HotaudioLog.w(
                    "retrying previously failed stream track=${spec.trackId}: " +
                        "${existing.error?.message}",
                )
                states.remove(spec.trackId)
            }

            val marker = markerFor(spec)
            if (marker.exists() && spec.file.exists() && spec.file.length() > 0L) {
                val state = HotaudioStreamState(spec).apply {
                    bytesWritten = spec.file.length()
                    done = true
                    firstByteAt = System.currentTimeMillis()
                }
                states[spec.trackId] = state
                HotaudioLog.i(
                    "reusing completed stream track=${spec.trackId} bytes=${state.bytesWritten}",
                )
                return state
            }

            spec.file.delete()
            marker.delete()

            val state = HotaudioStreamState(spec)
            states[spec.trackId] = state
            HotaudioLog.i(
                "starting stream track=${spec.trackId} page=${spec.pageUrl} " +
                    "file=${spec.file.absolutePath}",
            )
            jobs[spec.trackId] = scope.launch { download(spec, state, prefs) }
            return state
        }
    }

    private suspend fun download(
        spec: HotaudioStreamSpec,
        state: HotaudioStreamState,
        prefs: AppPreferences?,
    ) {
        val families = familiesToTry(prefs)
        val cachedKeys = runCatching {
            keysFileFor(spec).takeIf { it.exists() && it.length() > 0L }?.readText()
        }.onFailure {
            HotaudioLog.w("could not read cached keys track=${spec.trackId}", it)
        }.getOrNull()
        HotaudioLog.i(
            "download begin track=${spec.trackId} families=$families " +
                "cachedKeys=${cachedKeys != null}",
        )
        var lastError: Throwable? = null

        for ((familyIndex, family) in families.withIndex()) {
            state.family = family
            var attempt = 1

            while (true) {
                if (!currentCoroutineContext().isActive) {
                    HotaudioLog.w("download cancelled before attempt track=${spec.trackId} family=$family")
                    return
                }

                state.generation += 1
                HotaudioLog.i(
                    "attempt ${familyIndex + 1}/${families.size} #$attempt " +
                        "track=${spec.trackId} family=$family generation=${state.generation}",
                )

                try {
                    net.hotaudio.extractor.setIpFamily(family)
                    HotaudioLog.d("setIpFamily($family) OK")
                } catch (error: Throwable) {
                    HotaudioLog.e("setIpFamily($family) failed", error)
                }

                // Keep the partial file: downloadToFile truncates it itself, and
                // leaving it in place lets a reader at an earlier position keep
                // reading instead of hitting a file-not-found gap.
                markerFor(spec).delete()
                state.abortReason = null

                val token = CancellationToken()
                tokens[spec.trackId] = token

                val poller = scope.launch {
                    var lastChangeAt = System.currentTimeMillis()
                    while (currentCoroutineContext().isActive) {
                        val length = if (spec.file.exists()) spec.file.length() else 0L
                        if (length != state.bytesWritten) {
                            if (state.firstByteAt == 0L && length > 0L) {
                                state.firstByteAt = System.currentTimeMillis()
                                HotaudioLog.i(
                                    "first bytes track=${spec.trackId} bytes=$length " +
                                        "after=${state.firstByteAt - state.startedAt}ms family=$family",
                                )
                            } else {
                                HotaudioLog.d(
                                    "bytes track=${spec.trackId} $length family=$family",
                                )
                            }
                            state.bytesWritten = length
                            lastChangeAt = System.currentTimeMillis()
                        }
                        if (state.done) break
                        val idleMs = System.currentTimeMillis() - lastChangeAt
                        if (idleMs >= STALL_TIMEOUT_MS) {
                            HotaudioLog.w(
                                "download stalled track=${spec.trackId} family=$family " +
                                    "for ${idleMs}ms at bytes=${state.bytesWritten}; aborting attempt",
                            )
                            state.abortReason = "stalled"
                            token.cancel()
                            break
                        }
                        delay(POLL_MS)
                    }
                }

                var retrySameFamily = false
                try {
                    val options = DownloadOptions(
                        trackId = null,
                        apiBase = "https://hotaudio.net",
                        // No artificial pause: playback is interactive and the
                        // per-segment path is already latency-bound.
                        delayMs = 0u,
                        initialKeysJson = cachedKeys,
                    )
                    val listener = object : ProgressListener {
                        override fun onProgress(progress: Progress) {
                            state.progressPhase = progress.phase
                            state.progressLoaded = progress.loaded.toLong()
                            state.progressTotal = progress.total.toLong()
                            HotaudioLog.d(
                                "progress track=${spec.trackId} phase=${progress.phase} " +
                                    "${progress.loaded}/${progress.total} file=${state.bytesWritten}",
                            )
                        }
                    }

                    HotaudioLog.i(
                        "calling downloadToFile track=${spec.trackId} page=${spec.pageUrl} " +
                            "family=$family attempt=$attempt",
                    )
                    val info = downloadToFile(
                        pageUrl = spec.pageUrl,
                        path = spec.file.absolutePath,
                        options = options,
                        progress = listener,
                        cancel = token,
                    )

                    state.bytesWritten = spec.file.length()
                    state.done = true
                    state.error = null
                    runCatching { markerFor(spec).writeText(state.bytesWritten.toString()) }
                        .onFailure { HotaudioLog.w("could not write done marker", it) }
                    runCatching { keysFileFor(spec).writeText(info.keysJson) }
                        .onFailure { HotaudioLog.w("could not cache keys", it) }
                    if (family != "any") prefs?.saveHotaudioIpFamily(family)
                    HotaudioLog.i(
                        "download complete track=${spec.trackId} bytes=${state.bytesWritten} " +
                            "family=$family keyPages=${info.keyPages} " +
                            "elapsed=${System.currentTimeMillis() - state.startedAt}ms",
                    )
                    return
                } catch (error: Throwable) {
                    lastError = error
                    state.bytesWritten = if (spec.file.exists()) spec.file.length() else 0L

                    val stalled = state.abortReason == "stalled"
                    if (!currentCoroutineContext().isActive ||
                        (token.isCancelled() && !stalled)
                    ) {
                        HotaudioLog.w(
                            "download cancelled track=${spec.trackId} family=$family: ${error.message}",
                        )
                        spec.file.delete()
                        markerFor(spec).delete()
                        return
                    }

                    HotaudioLog.e(
                        "attempt failed track=${spec.trackId} family=$family attempt=$attempt " +
                            "challenge=${HotaudioErrors.isChallenge(error)} " +
                            "rateLimit=${HotaudioErrors.isRateLimit(error)} " +
                            "dnsOrTransient=${HotaudioErrors.isDnsOrTransient(error)} " +
                            "stalled=$stalled bytes=${state.bytesWritten}",
                        error,
                    )

                    val transient = HotaudioErrors.isDnsOrTransient(error) || stalled
                    val rateLimited = HotaudioErrors.isRateLimit(error)
                    if (transient && attempt < TRANSIENT_ATTEMPTS) {
                        val backoff = (TRANSIENT_BACKOFF_START_MS shl (attempt - 1))
                            .coerceAtMost(TRANSIENT_BACKOFF_MAX_MS)
                        HotaudioLog.w(
                            "${if (stalled) "stalled attempt" else "transient/DNS failure"} " +
                                "on family=$family; retrying same family in ${backoff}ms " +
                                "(attempt ${attempt + 1}/$TRANSIENT_ATTEMPTS)",
                        )
                        try {
                            delay(backoff)
                        } catch (cancelled: CancellationException) {
                            HotaudioLog.w("cancelled while backing off")
                            return
                        }
                        attempt += 1
                        retrySameFamily = true
                    } else if (rateLimited && attempt < RATE_LIMIT_ATTEMPTS) {
                        HotaudioLog.w(
                            "rate limited on family=$family; retrying same family in " +
                                "${RATE_LIMIT_BACKOFF_MS}ms (attempt ${attempt + 1}/$RATE_LIMIT_ATTEMPTS)",
                        )
                        try {
                            delay(RATE_LIMIT_BACKOFF_MS)
                        } catch (cancelled: CancellationException) {
                            HotaudioLog.w("cancelled while backing off")
                            return
                        }
                        attempt += 1
                        retrySameFamily = true
                    }
                } finally {
                    poller.cancel()
                    tokens.remove(spec.trackId, token)
                }

                if (retrySameFamily) continue
                break
            }

            if (familyIndex < families.lastIndex) {
                val failure = lastError
                when {
                    failure == null -> Unit
                    HotaudioErrors.isRateLimit(failure) -> {
                        HotaudioLog.w("rate limited; waiting 2500ms before next family")
                        try {
                            delay(2500)
                        } catch (cancelled: CancellationException) {
                            HotaudioLog.w("cancelled while backing off")
                            return
                        }
                    }
                    HotaudioErrors.isChallenge(failure) -> {
                        HotaudioLog.w("challenge on family=$family; trying next family")
                    }
                    HotaudioErrors.isDnsOrTransient(failure) -> {
                        HotaudioLog.w(
                            "transient/DNS failure on family=$family; trying next family",
                        )
                    }
                    else -> {
                        HotaudioLog.w("fatal error on family=$family; stopping")
                        break
                    }
                }
            }
        }

        state.error = ResolveException(HotaudioErrors.userMessage(lastError))
        state.done = true
        HotaudioLog.e(
            "download failed track=${spec.trackId}: ${state.error?.message}",
            lastError,
        )
    }

    private fun familiesToTry(prefs: AppPreferences?): List<String> {
        val saved = prefs?.hotaudioIpFamily()
            ?.lowercase()
            ?.takeIf { it == "ipv4" || it == "ipv6" }

        // Cloudflare binds cf_clearance to the address that earned it. Switching
        // families would throw that clearance away and earn a fresh challenge,
        // so stay on the saved family when one is stored.
        if (saved != null && prefs?.hotaudioClearance() != null) {
            return listOf(saved)
        }

        return buildList {
            if (saved != null) add(saved)
            add("ipv4")
            add("ipv6")
            add("any")
        }.distinct()
    }

    private fun markerFor(spec: HotaudioStreamSpec): File =
        File(spec.file.parentFile, "${spec.trackId}.done")

    /** Full key envelope from a previous download; avoids rate-limited paging. */
    private fun keysFileFor(spec: HotaudioStreamSpec): File =
        File(spec.file.parentFile, "${spec.trackId}.keys.json")

    suspend fun awaitFirstBytes(
        spec: HotaudioStreamSpec,
        timeoutMs: Long = FIRST_BYTES_TIMEOUT_MS,
    ): HotaudioStreamState {
        val state = states[spec.trackId]
            ?: throw ResolveException("hotaudio stream was not started")
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastLog = 0L

        while (true) {
            state.error?.let { throw it }
            if (state.bytesWritten > 0L) return state
            if (state.done) {
                state.error?.let { throw it }
                throw ResolveException("hotaudio stream finished without producing audio")
            }

            val now = System.currentTimeMillis()
            if (now > deadline) {
                throw ResolveException(
                    "hotaudio stream timed out after ${timeoutMs / 1000}s " +
                        "(family=${state.family})",
                )
            }
            if (now - lastLog > 5_000L) {
                lastLog = now
                HotaudioLog.d(
                    "awaiting first bytes track=${spec.trackId} family=${state.family} " +
                        "phase=${state.progressPhase} ${state.progressLoaded}/${state.progressTotal}",
                )
            }
            delay(POLL_MS)
        }
    }

    /**
     * Wait until a small lead is buffered so the player does not immediately
     * catch up with the bursty downloader. Returns early on failure/completion
     * and never throws: the caller can still play what already exists.
     */
    suspend fun awaitLead(
        spec: HotaudioStreamSpec,
        targetBytes: Long = 320L * 1024L,
        timeoutMs: Long = 20_000L,
    ): HotaudioStreamState? {
        val state = states[spec.trackId] ?: return null
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (state.bytesWritten >= targetBytes || state.done || state.error != null) {
                return state
            }
            delay(POLL_MS)
        }
        HotaudioLog.i(
            "lead timeout track=${spec.trackId} bytes=${state.bytesWritten} " +
                "target=$targetBytes; starting anyway",
        )
        return state
    }

    suspend fun awaitComplete(
        spec: HotaudioStreamSpec,
        timeoutMs: Long = COMPLETE_TIMEOUT_MS,
    ): File {
        val state = states[spec.trackId]
            ?: throw ResolveException("hotaudio stream was not started")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!state.done) {
            if (System.currentTimeMillis() > deadline) {
                throw ResolveException("hotaudio download timed out after ${timeoutMs / 1000}s")
            }
            delay(POLL_MS)
        }
        state.error?.let { throw it }
        if (!spec.file.exists() || spec.file.length() == 0L) {
            throw ResolveException("hotaudio download produced no file")
        }
        return spec.file
    }

    fun cancel(trackId: String) {
        val state = states.remove(trackId)
        val job = jobs.remove(trackId)
        val token = tokens.remove(trackId)
        HotaudioLog.i(
            "cancel track=$trackId job=${job != null} token=${token != null} " +
                "done=${state?.done}",
        )
        token?.cancel()
        job?.cancel()
        if (state != null && !state.done) {
            // Wake up awaitComplete/awaitFirstBytes instead of leaving them to
            // time out after the state has been removed.
            state.error = ResolveException("hotaudio stream cancelled")
            state.done = true
            state.spec.file.delete()
            markerFor(state.spec).delete()
        }
    }

    fun cancelAllExcept(trackId: String?) {
        states.keys.filter { it != trackId }.forEach(::cancel)
    }

    fun cancelAll() {
        states.keys.toList().forEach(::cancel)
    }
}
