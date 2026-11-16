package dev.dawngasm.app.data

/**
 * Desktop resolver. Hotaudio gets a handler in Phase 18; until then resolving
 * a hotaudio track throws the standard unsupported message.
 */
class AudioResolver(
    prefs: AppPreferences? = null,
) : CommonAudioResolver(prefs = prefs), AudioUrlResolver
