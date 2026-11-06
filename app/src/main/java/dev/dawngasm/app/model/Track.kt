package dev.dawngasm.app.model

import org.json.JSONArray
import org.json.JSONObject

/** The audio hosts this first pass resolves. hotaudio is deliberately absent. */
enum class AudioHost(val label: String) {
    SOUNDGASM("soundgasm"),
    WHYP("whyp"),
    AUDIOCHAN("audiochan"),
    UNKNOWN("audio");

    companion object {
        fun fromLabel(label: String): AudioHost =
            entries.firstOrNull { it.label.equals(label, ignoreCase = true) } ?: UNKNOWN
    }
}

/**
 * A post from the Dawngasm index that resolves to one of the supported hosts.
 *
 * [localUri] is set once a download finishes and makes the track playable
 * offline. It is deliberately absent from the API model; the resolver only
 * deals with remote links.
 */
data class Track(
    val id: String,
    val title: String,
    val author: String,
    val host: AudioHost,
    val pageUrl: String,
    val subreddit: String = "",
    val score: Long = 0L,
    val createdUtc: Long = 0L,
    val localUri: String? = null,
) {
    val displayAuthor: String
        get() = author.ifBlank { "unknown" }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("author", author)
        put("host", host.label)
        put("pageUrl", pageUrl)
        put("subreddit", subreddit)
        put("score", score)
        put("createdUtc", createdUtc)
        localUri?.let { put("localUri", it) }
    }

    companion object {
        fun listToJson(tracks: List<Track>): String {
            val array = JSONArray()
            tracks.forEach { array.put(it.toJson()) }
            return array.toString()
        }

        fun listFromJson(raw: String?): List<Track> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(raw)
                buildList {
                    for (index in 0 until array.length()) {
                        array.optJSONObject(index)?.let { add(fromJson(it)) }
                    }
                }
            }.getOrDefault(emptyList())
        }

        fun fromJson(json: JSONObject): Track = Track(
            id = json.optString("id"),
            title = json.optString("title"),
            author = json.optString("author"),
            host = AudioHost.fromLabel(json.optString("host")),
            pageUrl = json.optString("pageUrl"),
            subreddit = json.optString("subreddit"),
            score = json.optLong("score"),
            createdUtc = json.optLong("createdUtc"),
            localUri = json.optString("localUri").takeIf { it.isNotBlank() },
        )
    }
}
