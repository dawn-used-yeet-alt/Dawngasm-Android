package dev.dawngasm.app.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Android-side JSON bridge for [Track]. The shared model is platform-neutral,
 * so persistence and the media session keep their `org.json` encoding here.
 */
object TrackJson {

    fun toJson(track: Track): JSONObject = JSONObject().apply {
        put("id", track.id)
        put("title", track.title)
        put("author", track.author)
        put("host", track.host.label)
        put("pageUrl", track.pageUrl)
        put("subreddit", track.subreddit)
        put("score", track.score)
        put("createdUtc", track.createdUtc)
        track.localUri?.let { put("localUri", it) }
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

    fun listToJson(tracks: List<Track>): String {
        val array = JSONArray()
        tracks.forEach { array.put(toJson(it)) }
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
}
