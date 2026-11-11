package dev.dawngasm.app.data

import dev.dawngasm.app.util.Clock
import dev.dawngasm.app.util.PlatformLog
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class HotaudioStreamSpec(
    val trackId: String,
    val pageUrl: String,
    val filePath: String,
)

class HotaudioStreamState internal constructor(
    val spec: HotaudioStreamSpec,
) {
    @Volatile var bytesWritten: Long = 0L
    @Volatile var done: Boolean = false
    @Volatile var error: Throwable? = null
    @Volatile var family: String? = null
    @Volatile var startedAt: Long = Clock.nowEpochMillis()
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
 * already playable. Audio players read the file while this object keeps
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
     * usually means the server is tarpitting a key page.
     */
    private const val STALL_TIMEOUT_MS = 45_000L

    /**
     * Before the first byte arrives the call is still doing the page handshake
     * and the initial listen, which may take longer than a mid-stream stall.
     */
    private const val NO_FIRST_BYTE_ABORT_MS = 150_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val states = mutableMapOf<String, HotaudioStreamState>()
    private val jobs = mutableMapOf<String, Job>()
    private val cancelledFlags = mutableMapOf<String, Boolean>()

    fun normalizePageUrl(raw: String): String {
        var url = raw.trim()
        if (url.startsWith("http://", ignoreCase = true)) {
            url = "https" + url.substring(4)
        }
        return url.replace("://www.hotaudio.net", "://hotaudio.net")
    }

    fun spec(context: Any?, trackId: String, pageUrl: String): HotaudioStreamSpec =
        spec(trackId, pageUrl)

    fun spec(trackId: String, pageUrl: String): HotaudioStreamSpec {
        val root = PlatformFileOps.getCacheDir()
        val dir = "$root/hotaudio"
        PlatformFileOps.ensureDir(dir)
        return HotaudioStreamSpec(
            trackId = trackId,
            pageUrl = normalizePageUrl(pageUrl),
            filePath = "$dir/$trackId.m4a.part",
        )
    }

    fun streamUri(trackId: String): String = "$SCHEME://stream/$trackId"

    fun trackIdFrom(uriString: String): String? {
        if (!uriString.startsWith(SCHEME, ignoreCase = true)) return null
        return uriString.substringAfterLast('/').substringBefore('?').takeIf { it.isNotBlank() }
    }

    fun stateFor(trackId: String, context: Any?): HotaudioStreamState? = stateFor(trackId)

    /** The live state, or a completed state restored from the `.done` marker. */
    fun stateFor(trackId: String): HotaudioStreamState? {
        states[trackId]?.let { return it }

        val spec = spec(trackId, "")
        val marker = markerPathFor(spec)
        val fileLen = PlatformFileOps.fileLength(spec.filePath)
        if (PlatformFileOps.fileExists(marker) && fileLen > 0L) {
            val state = HotaudioStreamState(spec).apply {
                bytesWritten = fileLen
                done = true
                firstByteAt = Clock.nowEpochMillis()
            }
            states[trackId] = state
            PlatformLog.i("HotaudioStreamer", "restored completed stream track=$trackId bytes=${state.bytesWritten}")
            return state
        }
        return null
    }

    fun ensureStarted(spec: HotaudioStreamSpec, prefs: AppPreferences?): HotaudioStreamState {
        states[spec.trackId]?.let { existing ->
            if (!existing.done || existing.error == null) {
                PlatformLog.d(
                    "HotaudioStreamer",
                    "stream already active track=${spec.trackId} " +
                        "bytes=${existing.bytesWritten} done=${existing.done}",
                )
                return existing
            }
            PlatformLog.w(
                "HotaudioStreamer",
                "retrying previously failed stream track=${spec.trackId}: ${existing.error?.message}",
            )
            states.remove(spec.trackId)
        }

        val marker = markerPathFor(spec)
        val fileLen = PlatformFileOps.fileLength(spec.filePath)
        if (PlatformFileOps.fileExists(marker) && fileLen > 0L) {
            val state = HotaudioStreamState(spec).apply {
                bytesWritten = fileLen
                done = true
                firstByteAt = Clock.nowEpochMillis()
            }
            states[spec.trackId] = state
            PlatformLog.i(
                "HotaudioStreamer",
                "reusing completed stream track=${spec.trackId} bytes=${state.bytesWritten}",
            )
            return state
        }

        PlatformFileOps.deleteFile(spec.filePath)
        PlatformFileOps.deleteFile(marker)

        val state = HotaudioStreamState(spec)
        states[spec.trackId] = state
        cancelledFlags[spec.trackId] = false
        PlatformLog.i(
            "HotaudioStreamer",
            "starting stream track=${spec.trackId} page=${spec.pageUrl} file=${spec.filePath}",
        )
        jobs[spec.trackId] = scope.launch { download(spec, state, prefs) }
        return state
    }

    private suspend fun download(
        spec: HotaudioStreamSpec,
        state: HotaudioStreamState,
        prefs: AppPreferences?,
    ) {
        val bridge = HotaudioBridgeProvider.bridge
        if (bridge == null) {
            state.error = ResolveException("hotaudio native bridge is not configured")
            state.done = true
            return
        }

        val families = familiesToTry(prefs)
        val cachedKeys = runCatching {
            PlatformFileOps.readText(keysFilePathFor(spec))
        }.getOrNull()

        PlatformLog.i(
            "HotaudioStreamer",
            "download begin track=${spec.trackId} families=$families cachedKeys=${cachedKeys != null}",
        )
        var lastError: Throwable? = null

        for ((familyIndex, family) in families.withIndex()) {
            state.family = family
            var attempt = 1

            while (true) {
                if (!currentCoroutineContext().isActive || cancelledFlags[spec.trackId] == true) {
                    PlatformLog.w("HotaudioStreamer", "download cancelled before attempt track=${spec.trackId} family=$family")
                    return
                }

                state.generation += 1
                PlatformLog.i(
                    "HotaudioStreamer",
                    "attempt ${familyIndex + 1}/${families.size} #$attempt track=${spec.trackId} family=$family generation=${state.generation}",
                )

                try {
                    bridge.setIpFamily(family)
                    PlatformLog.d("HotaudioStreamer", "setIpFamily($family) OK")
                } catch (error: Throwable) {
                    PlatformLog.e("HotaudioStreamer", "setIpFamily($family) failed", error)
                }

                PlatformFileOps.deleteFile(markerPathFor(spec))
                state.abortReason = null

                var attemptCancelled = false
                val poller = scope.launch {
                    var lastChangeAt = Clock.nowEpochMillis()
                    var wroteAnything = false
                    while (currentCoroutineContext().isActive) {
                        val length = PlatformFileOps.fileLength(spec.filePath)
                        if (length != state.bytesWritten) {
                            if (state.firstByteAt == 0L && length > 0L) {
                                state.firstByteAt = Clock.nowEpochMillis()
                                PlatformLog.i(
                                    "HotaudioStreamer",
                                    "first bytes track=${spec.trackId} bytes=$length after=${state.firstByteAt - state.startedAt}ms family=$family",
                                )
                            }
                            state.bytesWritten = length
                            lastChangeAt = Clock.nowEpochMillis()
                            if (length > 0L) wroteAnything = true
                        }
                        if (state.done) break
                        val idleMs = Clock.nowEpochMillis() - lastChangeAt
                        val limit = if (wroteAnything) STALL_TIMEOUT_MS else NO_FIRST_BYTE_ABORT_MS
                        if (idleMs >= limit) {
                            PlatformLog.w(
                                "HotaudioStreamer",
                                "download stalled track=${spec.trackId} family=$family for ${idleMs}ms; aborting attempt",
                            )
                            state.abortReason = "stalled"
                            attemptCancelled = true
                            break
                        }
                        delay(POLL_MS)
                    }
                }

                val parentJob = currentCoroutineContext()[Job]
                var retrySameFamily = false
                try {
                    val result = bridge.downloadToFile(
                        pageUrl = spec.pageUrl,
                        destinationPath = spec.filePath,
                        initialKeysJson = cachedKeys,
                        onProgress = { phase, loaded, total ->
                            state.progressPhase = phase
                            state.progressLoaded = loaded
                            state.progressTotal = total
                        },
                        isCancelled = {
                            parentJob?.isActive == false ||
                                cancelledFlags[spec.trackId] == true ||
                                attemptCancelled
                        },
                    )

                    state.bytesWritten = PlatformFileOps.fileLength(spec.filePath)
                    state.done = true
                    state.error = null
                    runCatching {
                        PlatformFileOps.writeText(markerPathFor(spec), state.bytesWritten.toString())
                        PlatformFileOps.writeText(keysFilePathFor(spec), result.keysJson)
                    }
                    if (family != "any") prefs?.saveHotaudioIpFamily(family)
                    PlatformLog.i(
                        "HotaudioStreamer",
                        "download complete track=${spec.trackId} bytes=${state.bytesWritten} family=$family keyPages=${result.keyPages}",
                    )
                    return
                } catch (error: Throwable) {
                    lastError = error
                    state.bytesWritten = PlatformFileOps.fileLength(spec.filePath)

                    val stalled = state.abortReason == "stalled"
                    if (!currentCoroutineContext().isActive || (cancelledFlags[spec.trackId] == true && !stalled)) {
                        PlatformLog.w("HotaudioStreamer", "download cancelled track=${spec.trackId} family=$family: ${error.message}")
                        PlatformFileOps.deleteFile(spec.filePath)
                        PlatformFileOps.deleteFile(markerPathFor(spec))
                        return
                    }

                    PlatformLog.e(
                        "HotaudioStreamer",
                        "attempt failed track=${spec.trackId} family=$family attempt=$attempt challenge=${HotaudioErrors.isChallenge(error)} stalled=$stalled bytes=${state.bytesWritten}",
                        error,
                    )

                    val transient = HotaudioErrors.isDnsOrTransient(error) || stalled
                    val rateLimited = HotaudioErrors.isRateLimit(error)
                    if (transient && attempt < TRANSIENT_ATTEMPTS) {
                        val backoff = (TRANSIENT_BACKOFF_START_MS shl (attempt - 1))
                            .coerceAtMost(TRANSIENT_BACKOFF_MAX_MS)
                        PlatformLog.w("HotaudioStreamer", "transient failure on family=$family; retrying in ${backoff}ms")
                        try {
                            delay(backoff)
                        } catch (c: CancellationException) {
                            return
                        }
                        attempt += 1
                        retrySameFamily = true
                    } else if (rateLimited && attempt < RATE_LIMIT_ATTEMPTS) {
                        PlatformLog.w("HotaudioStreamer", "rate limited on family=$family; retrying in ${RATE_LIMIT_BACKOFF_MS}ms")
                        try {
                            delay(RATE_LIMIT_BACKOFF_MS)
                        } catch (c: CancellationException) {
                            return
                        }
                        attempt += 1
                        retrySameFamily = true
                    }
                } finally {
                    poller.cancel()
                }

                if (retrySameFamily) continue
                break
            }

            if (familyIndex < families.lastIndex) {
                val failure = lastError
                when {
                    failure == null -> Unit
                    HotaudioErrors.isRateLimit(failure) -> {
                        try {
                            delay(2500)
                        } catch (c: CancellationException) {
                            return
                        }
                    }
                    HotaudioErrors.isChallenge(failure) || HotaudioErrors.isDnsOrTransient(failure) -> Unit
                    else -> break
                }
            }
        }

        state.error = ResolveException(HotaudioErrors.userMessage(lastError))
        state.done = true
        PlatformLog.e("HotaudioStreamer", "download failed track=${spec.trackId}: ${state.error?.message}", lastError)
    }

    private fun familiesToTry(prefs: AppPreferences?): List<String> {
        val saved = prefs?.hotaudioIpFamily()
            ?.lowercase()
            ?.takeIf { it == "ipv4" || it == "ipv6" }

        if (saved != null && prefs.hotaudioClearance() != null) {
            return listOf(saved)
        }

        return buildList {
            if (saved != null) add(saved)
            add("ipv4")
            add("ipv6")
            add("any")
        }.distinct()
    }

    private fun markerPathFor(spec: HotaudioStreamSpec): String =
        spec.filePath.substringBeforeLast('/') + "/${spec.trackId}.done"

    private fun keysFilePathFor(spec: HotaudioStreamSpec): String =
        spec.filePath.substringBeforeLast('/') + "/${spec.trackId}.keys.json"

    suspend fun awaitFirstBytes(
        spec: HotaudioStreamSpec,
        timeoutMs: Long = FIRST_BYTES_TIMEOUT_MS,
    ): HotaudioStreamState {
        val state = states[spec.trackId]
            ?: throw ResolveException("hotaudio stream was not started")
        val deadline = Clock.nowEpochMillis() + timeoutMs

        while (true) {
            state.error?.let { throw it }
            if (state.bytesWritten > 0L) return state
            if (state.done) {
                state.error?.let { throw it }
                throw ResolveException("hotaudio stream finished without producing audio")
            }

            val now = Clock.nowEpochMillis()
            if (now > deadline) {
                throw ResolveException(
                    "hotaudio stream timed out after ${timeoutMs / 1000}s (family=${state.family})",
                )
            }
            delay(POLL_MS)
        }
    }

    suspend fun awaitLead(
        spec: HotaudioStreamSpec,
        targetBytes: Long = 320L * 1024L,
        timeoutMs: Long = 20_000L,
    ): HotaudioStreamState? {
        val state = states[spec.trackId] ?: return null
        val deadline = Clock.nowEpochMillis() + timeoutMs
        while (Clock.nowEpochMillis() < deadline) {
            if (state.bytesWritten >= targetBytes || state.done || state.error != null) {
                return state
            }
            delay(POLL_MS)
        }
        return state
    }

    suspend fun awaitComplete(
        spec: HotaudioStreamSpec,
        timeoutMs: Long = COMPLETE_TIMEOUT_MS,
    ): String {
        val state = states[spec.trackId]
            ?: throw ResolveException("hotaudio stream was not started")
        val deadline = Clock.nowEpochMillis() + timeoutMs
        while (!state.done) {
            if (Clock.nowEpochMillis() > deadline) {
                throw ResolveException("hotaudio download timed out after ${timeoutMs / 1000}s")
            }
            delay(POLL_MS)
        }
        state.error?.let { throw it }
        if (PlatformFileOps.fileLength(spec.filePath) == 0L) {
            throw ResolveException("hotaudio download produced no file")
        }
        return spec.filePath
    }

    fun cancel(trackId: String) {
        val state = states.remove(trackId)
        val job = jobs.remove(trackId)
        cancelledFlags[trackId] = true
        job?.cancel()
        if (state != null && !state.done) {
            state.error = ResolveException("hotaudio stream cancelled")
            state.done = true
            PlatformFileOps.deleteFile(state.spec.filePath)
            PlatformFileOps.deleteFile(markerPathFor(state.spec))
        }
    }

    fun cancelAllExcept(trackId: String?) {
        states.keys.filter { it != trackId }.forEach(::cancel)
    }

    fun cancelAll() {
        states.keys.toList().forEach(::cancel)
    }
}
