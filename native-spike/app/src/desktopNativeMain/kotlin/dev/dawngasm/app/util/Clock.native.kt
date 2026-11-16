package dev.dawngasm.app.util

import kotlin.time.TimeSource
import platform.posix.time

private val startEpochMs: Long = time(null) * 1000L
private val startMark = TimeSource.Monotonic.markNow()

actual fun currentTimeMillis(): Long =
    startEpochMs + startMark.elapsedNow().inWholeMilliseconds


