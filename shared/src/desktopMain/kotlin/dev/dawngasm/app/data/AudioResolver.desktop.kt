package dev.dawngasm.app.data

import dev.dawngasm.app.model.Track
import dev.dawngasm.app.util.PlatformLog

/**
 * Desktop resolver. Soundgasm, Whyp, and Audiochan use the shared Ktor paths;
 * hotaudio starts the growing cache file and exposes it through the loopback
 * HTTP server so VLC can play it before the download finishes.
 */
class AudioResolver(
    private val prefs: AppPreferences? = null,
) : CommonAudioResolver(
    prefs = prefs,
    hotaudioHandler = { track: Track ->
        HotaudioRuntime.applyClearance(prefs)

        val spec = HotaudioStreamer.spec(track.id, track.pageUrl)
        HotaudioStreamer.ensureStarted(spec, prefs)
        val state = HotaudioStreamer.awaitFirstBytes(spec)
        HotaudioStreamer.awaitLead(spec)

        val url = HotaudioStreamServer.urlFor(track.id)
        PlatformLog.i(
            "AudioResolver",
            "hotaudio ready track=${track.id} bytes=${state.bytesWritten} " +
                "family=${state.family} url=$url",
        )
        ResolvedAudio(
            url = url,
            headers = emptyMap(),
            extension = "m4a",
        )
    },
) : AudioUrlResolver {
    init {
        HotaudioDesktopIntegration.install()
    }
}
