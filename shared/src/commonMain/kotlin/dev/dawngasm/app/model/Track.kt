package dev.dawngasm.app.model

/** Supported audio hosts. */
enum class AudioHost(val label: String) {
    SOUNDGASM("soundgasm"),
    WHYP("whyp"),
    AUDIOCHAN("audiochan"),
    HOTAUDIO("hotaudio"),
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
}
