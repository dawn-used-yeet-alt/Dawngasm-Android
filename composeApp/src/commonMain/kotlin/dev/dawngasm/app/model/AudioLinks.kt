package dev.dawngasm.app.model

/**
 * Host detection mirrors the desktop client's link parsing: normalise the
 * escaped text the index stores, then try the hosts in a fixed order so a post
 * carrying several links picks the same one every time.
 */
object AudioLinks {
    private val soundgasm = Regex(
        """(?i)https?://(?:www\.)?soundgasm\.net/u/[a-zA-Z0-9_%~.-]+/[a-zA-Z0-9_%~.-]+""",
    )
    private val whyp = Regex(
        """(?i)https?://(?:www\.)?whyp\.it/(?:tracks|t)/[a-zA-Z0-9_%~.-]+(?:/[a-zA-Z0-9_%~.-]+)?(?:\?[^"'\s<>)\]]*)?""",
    )
    private val audiochan = Regex(
        """(?i)https?://(?:www\.)?audiochan\.com/(?:embed/)?(?:a|s)/([a-zA-Z0-9_-]+)""",
    )
    private val hotaudio = Regex(
        """(?i)https?://(?:www\.)?hotaudio\.net/u/[a-zA-Z0-9_%~.-]+/[a-zA-Z0-9_%~.-]+(?:\?[^"'\s<>)\]]*)?""",
    )
    private val patterns = listOf(soundgasm, whyp, audiochan, hotaudio)

    private val tagPattern = Regex("""\[([^\]]{1,48})]""")

    fun normalize(text: String): String = text
        .replace("\\u002F", "/")
        .replace("\\u002f", "/")
        .replace("\\/", "/")
        .replace("\\_", "_")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&#x2F;", "/")
        .replace("&#x2f;", "/")
        .replace("&#47;", "/")

    fun cleanUrl(url: String): String {
        var cleaned = url.trim()
        while (cleaned.isNotEmpty() && cleaned.last() in ")]>.,;\"'") {
            cleaned = cleaned.dropLast(1)
        }
        return cleaned
    }

    /** The first supported link in [postUrl] or [selftext], in host order. */
    fun find(postUrl: String, selftext: String): Pair<AudioHost, String>? {
        val targets = listOf(normalize(postUrl), normalize(selftext)).filter { it.isNotEmpty() }
        for (pattern in patterns) {
            for (target in targets) {
                val match = pattern.find(target) ?: continue
                val url = cleanUrl(match.value)
                return hostOf(url) to url
            }
        }
        return null
    }

    fun hostOf(url: String): AudioHost {
        val lower = url.lowercase()
        return when {
            lower.contains("soundgasm.net/u/") -> AudioHost.SOUNDGASM
            lower.contains("whyp.it/tracks/") || lower.contains("whyp.it/t/") -> AudioHost.WHYP
            lower.contains("audiochan.com/a/") ||
                lower.contains("audiochan.com/embed/a/") ||
                lower.contains("audiochan.com/s/") ||
                lower.contains("audiochan.com/embed/s/") -> AudioHost.AUDIOCHAN
            lower.contains("hotaudio.net/u/") -> AudioHost.HOTAUDIO
            else -> AudioHost.UNKNOWN
        }
    }

    /** Bracketed tags from a post title, lower-cased and de-duplicated. */
    fun tagsInTitle(title: String): List<String> = tagPattern.findAll(title)
        .map { it.groupValues[1].trim().lowercase() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(12)
        .toList()
}
