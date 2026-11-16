package dev.dawngasm.app.data

import dev.dawngasm.app.model.Track

/**
 * Platform-neutral seam over the audio URL resolver. Each shell supplies its
 * own implementation (`CommonAudioResolver` plus a hotaudio handler) while the
 * presenter only depends on this interface.
 */
interface AudioUrlResolver {
    suspend fun resolve(track: Track): ResolvedAudio
}
