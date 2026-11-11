package dev.dawngasm.app.data

class AudioResolver(
    prefs: AppPreferences? = null,
) : CommonAudioResolver(
    prefs = prefs,
    hotaudioHandler = { track ->
        HotaudioRuntime.applyClearance(prefs)
        val spec = HotaudioStreamer.spec(track.id, track.pageUrl)
        HotaudioStreamer.ensureStarted(spec, prefs)
        val state = HotaudioStreamer.awaitFirstBytes(spec)
        HotaudioStreamer.awaitLead(spec)
        ResolvedAudio(
            url = HotaudioStreamer.streamUri(track.id),
            headers = emptyMap(),
            extension = "m4a",
        )
    },
)
