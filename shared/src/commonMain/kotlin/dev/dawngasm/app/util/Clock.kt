package dev.dawngasm.app.util

/** Current wall-clock time in milliseconds. */
expect fun currentTimeMillis(): Long

object Clock {
    fun nowEpochMillis(): Long = currentTimeMillis()
}
