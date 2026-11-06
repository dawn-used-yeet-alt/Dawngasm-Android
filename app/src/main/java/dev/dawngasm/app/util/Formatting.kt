package dev.dawngasm.app.util

import java.util.concurrent.TimeUnit
import kotlin.math.max

fun relativeTime(epochSeconds: Long, nowSeconds: Long = System.currentTimeMillis() / 1000L): String {
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
    val total = TimeUnit.MILLISECONDS.toSeconds(ms)
    val minutes = total / 60
    val seconds = total % 60
    return "%d:%02d".format(minutes, seconds)
}

fun compactScore(score: Long): String = when {
    score >= 1_000_000 -> "${score / 1_000_000}.${(score % 1_000_000) / 100_000}M"
    score >= 1_000 -> "${score / 1_000}.${(score % 1_000) / 100}k"
    else -> score.toString()
}

/** File-name-safe version of a track title, capped so SAF providers stay happy. */
fun safeFileName(input: String, fallback: String): String {
    val cleaned = input
        .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trimEnd('.')
        .take(110)
    return cleaned.ifBlank { fallback }
}
