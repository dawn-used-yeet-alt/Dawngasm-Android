package dev.dawngasm.app.data

import android.content.Context
import dev.dawngasm.app.model.Track

class AudioResolver(
    private val context: Context? = null,
) : CommonAudioResolver(
    prefs = context?.let { AppPreferences(it) },
    hotaudioHandler = { track ->
        HotaudioLog.i("hotaudio resolve track=${track.id} page=${track.pageUrl}")
        val prefs = context?.let { AppPreferences(it) }
        HotaudioRuntime.applyClearance(prefs)

        val spec = HotaudioStreamer.spec(context, track.id, track.pageUrl)
        HotaudioStreamer.ensureStarted(spec, prefs)
        val state = HotaudioStreamer.awaitFirstBytes(spec)
        HotaudioStreamer.awaitLead(spec)

        HotaudioLog.i(
            "hotaudio ready track=${track.id} bytes=${state.bytesWritten} " +
                "family=${state.family} firstByteIn=${state.firstByteAt - state.startedAt}ms " +
                "lead=${state.bytesWritten}",
        )
        ResolvedAudio(
            url = HotaudioStreamer.streamUri(track.id).toString(),
            headers = emptyMap(),
            extension = "m4a",
        )
    },
) {
    companion object {
        const val audiochanVerificationMessage = CommonAudioResolver.audiochanVerificationMessage

        fun parseSoundgasm(html: String): String? = CommonAudioResolver.parseSoundgasm(html)
        fun parseWhyp(html: String): String? = CommonAudioResolver.parseWhyp(html)
        fun parseAudiochanSlug(text: String): String? = CommonAudioResolver.parseAudiochanSlug(text)
        fun parseAudiochanFileId(json: String): String? = CommonAudioResolver.parseAudiochanFileId(json)
        fun parseAudiochanFileUrl(json: String): String? = CommonAudioResolver.parseAudiochanFileUrl(json)
        fun parseJsonField(json: String, field: String): String? = CommonAudioResolver.parseJsonField(json, field)
        fun extensionFromUrl(url: String, fallback: String): String = CommonAudioResolver.extensionFromUrl(url, fallback)
    }
}
