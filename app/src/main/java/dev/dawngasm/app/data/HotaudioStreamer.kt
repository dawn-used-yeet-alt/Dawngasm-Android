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
        HotaudioLog.i("download begin track=${spec.trackId} families=$families")
        var lastError: Throwable? = null

        for ((index, family) in families.withIndex()) {
            if (!currentCoroutineContext().isActive) {
                HotaudioLog.w("download cancelled before attempt track=${spec.trackId} family=$family")
                return
            }

            state.family = family
            state.generation += 1
            HotaudioLog.i(
                "attempt ${index + 1}/${families.size} track=${spec.trackId} " +
                    "family=$family generation=${state.generation}",
            )

            try {
                net.hotaudio.extractor.setIpFamily(family)
                HotaudioLog.d("setIpFamily($family) OK")
            } catch (error: Throwable) {
                HotaudioLog.e("setIpFamily($family) failed", error)
            }

            spec.file.delete()
            markerFor(spec).delete()
            state.bytesWritten = 0L
            state.firstByteAt = 0L

            val token = CancellationToken()
            tokens[spec.trackId] = token

            val poller = scope.launch {
                while (currentCoroutineContext().isActive) {
                    val length = if (spec.file.exists()) spec.file.length() else 0L
                    if (length != state.bytesWritten) {
                        if (state.bytesWritten == 0L && length > 0L) {
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
                    }
                    if (state.done) break
                    delay(POLL_MS)
                }
            }

            try {
                val options = DownloadOptions(
                    trackId = null,
                    apiBase = "https://hotaudio.net",
                    delayMs = 250u,
                    initialKeysJson = null,
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
                        "family=$family",
                )
                downloadToFile(
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
                if (family != "any") prefs?.saveHotaudioIpFamily(family)
                HotaudioLog.i(
                    "download complete track=${spec.trackId} bytes=${state.bytesWritten} " +
                        "family=$family elapsed=${System.currentTimeMillis() - state.startedAt}ms",
                )
                return
            } catch (error: Throwable) {
                lastError = error
                state.bytesWritten = if (spec.file.exists()) spec.file.length() else 0L

                if (!currentCoroutineContext().isActive || token.isCancelled()) {
                    HotaudioLog.w(
                        "download cancelled track=${spec.trackId} family=$family: ${error.message}",
                    )
                    spec.file.delete()
                    markerFor(spec).delete()
                    return
                }

                HotaudioLog.e(
                    "attempt failed track=${spec.trackId} family=$family " +
                        "challenge=${HotaudioErrors.isChallenge(error)} " +
                        "rateLimit=${HotaudioErrors.isRateLimit(error)} " +
                        "dnsOrTransient=${HotaudioErrors.isDnsOrTransient(error)}",
                    error,
                )

                spec.file.delete()
                markerFor(spec).delete()
                state.bytesWritten = 0L

                if (index < families.lastIndex) {
                    when {
                        HotaudioErrors.isRateLimit(error) -> {
                            HotaudioLog.w("rate limited; waiting 2500ms before next family")
                            try {
                                delay(2500)
                            } catch (cancelled: CancellationException) {
                                HotaudioLog.w("cancelled while backing off")
                                return
                            }
                        }
                        HotaudioErrors.isChallenge(error) -> {
                            HotaudioLog.w("challenge on family=$family; trying next family")
                        }
                        HotaudioErrors.isDnsOrTransient(error) -> {
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
            } finally {
                poller.cancel()
                tokens.remove(spec.trackId, token)
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
        return buildList {
            if (saved != null) add(saved)
            add("ipv4")
            add("ipv6")
            add("any")
        }.distinct()
    }

    private fun markerFor(spec: HotaudioStreamSpec): File =
        File(spec.file.parentFile, "${spec.trackId}.done")

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
