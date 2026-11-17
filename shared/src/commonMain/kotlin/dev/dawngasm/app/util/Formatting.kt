package dev.dawngasm.app.util

import kotlin.math.max

fun relativeTime(epochSeconds: Long, nowSeconds: Long = Clock.nowEpochMillis() / 1000L): String {
    if (epochSeconds <= 0) return ""
    val seconds = max(0L, nowSeconds - epochSeconds)
    return when {
        seconds < 60 -> "just now"
        seconds < 3_600 -> "${seconds / 60}m ago"
        seconds < 86_400 -> "${seconds / 3_600}h ago"
        seconds < 2_592_000 -> "${seconds / 86_400}d ago"
        seconds < 31_536_000 -> "${seconds / 2_592_000}mo ago"
        else -> "${seconds / 31_536_000}y ago"
    }
}

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val minutes = total / 60
    val seconds = total % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

fun compactScore(score: Long): String = when {
    score >= 1_000_000 -> "${score / 1_000_000}.${(score % 1_000_000) / 100_000}M"
    score >= 1_000 -> "${score / 1_000}.${(score % 1_000) / 100}k"
    else -> score.toString()
}

/** File-name-safe version of a track title, capped so SAF providers stay happy. */
fun safeFileName(input: String, fallback: String, maxLength: Int = 110): String {
    val candidate = sanitizeSegment(input, maxLength)
        .ifBlank { sanitizeSegment(fallback, maxLength) }
        .ifBlank { "track" }
    val stem = candidate.substringBefore('.')
    return if (stem.uppercase() in WINDOWS_RESERVED_NAMES) "_$candidate" else candidate
}

private fun sanitizeSegment(input: String, maxLength: Int): String = input
    .replace(INVALID_FILE_NAME_CHARS, " ")
    .replace(WHITESPACE, " ")
    .trim()
    .take(maxLength)
    .trim()
    // Windows rejects names ending in a dot or a space; truncation can add one.
    .trimEnd('.', ' ')

private val INVALID_FILE_NAME_CHARS = Regex("""[\\/:*?"<>|\u0000-\u001F]""")
private val WHITESPACE = Regex("\\s+")

/**
 * Windows device names are reserved even with an extension (`CON.m4a`), so a
 * title matching one gets a leading underscore.
 */
private val WINDOWS_RESERVED_NAMES = buildSet {
    addAll(listOf("CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$"))
    for (index in 1..9) {
        add("COM$index")
        add("LPT$index")
    }
}
