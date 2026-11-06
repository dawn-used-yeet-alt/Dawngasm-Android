package dev.dawngasm.app.model

import java.util.Locale

/** One inline search recommendation shown under the search field. */
data class SearchSuggestion(
    val token: String,
    val title: String,
    val subtitle: String,
)

/**
 * Discord-style search syntax:
 *
 *   top:day   top:week   top:month   top:year   top:all   top:30d
 *
 * The recognised tokens become feed filters, while every other word stays
 * search text. Unknown `top:` values are treated as ordinary text instead of
 * silently changing the feed.
 */
object SearchSyntax {
    const val DEFAULT_SORT = "top"
    const val DEFAULT_WINDOW = "day"

    val SORTS = listOf("top", "new", "relevance")
    val WINDOWS = listOf("day", "week", "month", "year", "all")

    private val customWindow = Regex("""^(\d{1,5})d$""")

    data class Parsed(
        val text: String,
        val sort: String?,
        val window: String?,
    )

    fun parse(input: String): Parsed {
        val words = input.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        var sort: String? = null
        var window: String? = null
        val text = mutableListOf<String>()

        for (word in words) {
            val lower = word.lowercase(Locale.ROOT)
            when {
                lower.startsWith("sort:") -> {
                    val value = lower.removePrefix("sort:")
                    if (value in SORTS) sort = value else text += word
                }
                lower.startsWith("top:") -> {
                    val value = normalizeWindow(lower.removePrefix("top:"))
                    if (value != null) window = value else text += word
                }
                else -> text += word
            }
        }
        return Parsed(text.joinToString(" "), sort, window)
    }

    fun normalizeWindow(raw: String): String? {
        val value = raw.trim().lowercase(Locale.ROOT)
        if (value in WINDOWS) return value
        val match = customWindow.matchEntire(value) ?: return null
        val days = match.groupValues[1].toIntOrNull() ?: return null
        return if (days in 1..36500) "${days}d" else null
    }

    fun windowLabel(window: String): String = when (window) {
        "day", "1d" -> "Past day"
        "week", "7d" -> "Past week"
        "month" -> "Past month"
        "year", "365d" -> "Past year"
        "all" -> "All time"
        else -> {
            val days = window.removeSuffix("d").toIntOrNull()
            if (days != null) "Last $days days" else window
        }
    }

    fun sortLabel(sort: String): String = when (sort) {
        "new" -> "New"
        "relevance" -> "Relevance"
        else -> "Top"
    }

    /**
     * Cleans a typed or suggested tag: `[F4M]`, `-m4f`, and `M4F` all become
     * `m4f`, while multi-word tags such as `script fill` stay intact.
     */
    fun normalizeTag(raw: String): String? {
        var tag = raw.trim().removePrefix("-").trim()
        if (tag.length >= 2 && tag.startsWith("[") && tag.endsWith("]")) {
            tag = tag.substring(1, tag.length - 1).trim()
        }
        return tag
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
            .takeIf { it.isNotEmpty() }
    }

    /**
     * Turns a comma-separated field value into clean tags, so the visible text
     * in each tag field is the single source of truth for that filter.
     */
    fun parseTagList(raw: String): List<String> =
        raw.split(',')
            .mapNotNull { normalizeTag(it) }
            .distinctBy { it.lowercase(Locale.ROOT) }

    fun activeToken(input: String): String {
        if (input.isEmpty() || input.last().isWhitespace()) return ""
        val trimmed = input.trimEnd()
        val space = trimmed.indexOfLast { it.isWhitespace() }
        return if (space == -1) trimmed else trimmed.substring(space + 1)
    }

    /** Replaces the last, partially typed token with the chosen suggestion. */
    fun replaceActiveToken(input: String, token: String): String {
        if (input.isEmpty() || input.last().isWhitespace()) {
            val base = input.trimEnd()
            return if (base.isEmpty()) "$token " else "$base $token "
        }
        val trimmed = input.trimEnd()
        val space = trimmed.indexOfLast { it.isWhitespace() }
        val prefix = if (space == -1) "" else trimmed.substring(0, space + 1)
        return "$prefix$token "
    }

    /**
     * Removes resolved filter tokens so a chip change is not silently overridden
     * by a `top:` or `sort:` token still sitting in the search box. Invalid
     * tokens remain search text.
     */
    fun withoutToken(input: String, prefix: String): String {
        val words = input.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return words.filterNot { word ->
            val lower = word.lowercase(Locale.ROOT)
            if (!lower.startsWith(prefix)) return@filterNot false
            when (prefix) {
                "sort:" -> lower.removePrefix(prefix) in SORTS
                "top:" -> normalizeWindow(lower.removePrefix(prefix)) != null
                else -> false
            }
        }.joinToString(" ")
    }

    fun suggestions(input: String): List<SearchSuggestion> {
        val active = activeToken(input).lowercase(Locale.ROOT)
        if (active.isEmpty()) return emptyList()

        return when {
            active == "top" || active.startsWith("top:") -> {
                val query = active.removePrefix("top:").takeIf { active.contains(":") } ?: ""
                windowSuggestions().filter {
                    query.isEmpty() || it.token.removePrefix("top:").startsWith(query)
                }
            }
            active == "sort" || active.startsWith("sort:") -> {
                val query = active.removePrefix("sort:").takeIf { active.contains(":") } ?: ""
                SORTS.filter { query.isEmpty() || it.startsWith(query) }.map {
                    SearchSuggestion(
                        token = "sort:$it",
                        title = sortLabel(it),
                        subtitle = "sort:$it",
                    )
                }
            }
            else -> emptyList()
        }
    }

    fun windowSuggestions(): List<SearchSuggestion> = buildList {
        add(SearchSuggestion("top:day", "Past day", "top:day"))
        add(SearchSuggestion("top:week", "Past week", "top:week"))
        add(SearchSuggestion("top:month", "Past month", "top:month"))
        add(SearchSuggestion("top:year", "Past year", "top:year"))
        add(SearchSuggestion("top:all", "All time", "top:all"))
        add(SearchSuggestion("top:1d", "Last 1 day", "top:1d"))
        add(SearchSuggestion("top:3d", "Last 3 days", "top:3d"))
        add(SearchSuggestion("top:7d", "Last 7 days", "top:7d"))
        add(SearchSuggestion("top:14d", "Last 14 days", "top:14d"))
        add(SearchSuggestion("top:30d", "Last 30 days", "top:30d"))
        add(SearchSuggestion("top:90d", "Last 90 days", "top:90d"))
        add(SearchSuggestion("top:365d", "Last 365 days", "top:365d"))
    }
}
