package dev.dawngasm.app.model

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Shared JSON bridge for [Track] and track lists using kotlinx.serialization.
 * Preserves exact field names and types for backwards compatibility with
 * saved tracks, history, downloads, and playback session extras.
 */
object TrackJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    fun encodeToString(track: Track): String =
        json.encodeToString(Track.serializer(), track)

    fun decodeFromString(raw: String): Track =
        json.decodeFromString(Track.serializer(), raw)

    fun listToJson(tracks: List<Track>): String =
        json.encodeToString(ListSerializer(Track.serializer()), tracks)

    fun listFromJson(raw: String?): List<Track> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(Track.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    // Convenience aliases
    fun toJson(track: Track): String = encodeToString(track)
    fun fromJson(raw: String): Track = decodeFromString(raw)
}
