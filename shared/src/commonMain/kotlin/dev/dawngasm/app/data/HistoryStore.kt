package dev.dawngasm.app.data

import dev.dawngasm.app.model.Track
import dev.dawngasm.app.util.Clock
import dev.dawngasm.app.util.PlatformLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Append-only NDJSON history log backed by [PlatformFileOps].
 *
 * Replaces storing the full history array inside `AppPreferences` (`KEY_HISTORY`),
 * giving O(1) appends on playback, unbounded history storage, async loading on
 * `Dispatchers.IO`, and periodic background compaction.
 */
class HistoryStore(
    private val filePathProvider: () -> String = { "${PlatformFileOps.getDataDir()}/history.ndjson" },
) {
    @Serializable
    private data class HistoryLogLine(
        val track: Track,
        val playedAtMs: Long,
    )

    private val mutex = Mutex()
    private val entriesById = LinkedHashMap<String, SyncedHistoryEntry>()
    private var orderedEntries: List<SyncedHistoryEntry> = emptyList()
    private var uncompactedLines = 0

    private val ndjson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    suspend fun load(
        legacyTracks: List<Track> = emptyList(),
        onLegacyMigrated: () -> Unit = {},
    ): List<Track> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val path = filePathProvider()
            var totalLines = 0
            val raw = runCatching { PlatformFileOps.readText(path) }.getOrNull()
            if (!raw.isNullOrBlank()) {
                for (line in raw.lineSequence()) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue
                    totalLines++
                    val parsed = runCatching {
                        ndjson.decodeFromString(HistoryLogLine.serializer(), trimmed)
                    }.getOrNull() ?: continue
                    val clean = parsed.track.withoutLocalUri()
                    val existing = entriesById[clean.id]
                    if (existing == null || parsed.playedAtMs > existing.playedAtMs) {
                        entriesById[clean.id] = SyncedHistoryEntry(
                            id = clean.id,
                            track = clean,
                            playedAtMs = parsed.playedAtMs,
                        )
                    }
                }
            }

            var needsCompaction = totalLines > entriesById.size + COMPACTION_THRESHOLD

            if (legacyTracks.isNotEmpty()) {
                val baseTime = Clock.nowEpochMillis() - legacyTracks.size
                legacyTracks.forEachIndexed { index, track ->
                    val clean = track.withoutLocalUri()
                    if (!entriesById.containsKey(clean.id)) {
                        // Preserve legacy most-recent-first ordering
                        val syntheticTime = (baseTime + (legacyTracks.size - index)).coerceAtLeast(1L)
                        entriesById[clean.id] = SyncedHistoryEntry(
                            id = clean.id,
                            track = clean,
                            playedAtMs = syntheticTime,
                        )
                        needsCompaction = true
                    }
                }
            }

            rebuildOrderedLocked()
            if (needsCompaction) {
                compactLocked(path)
            } else {
                uncompactedLines = (totalLines - entriesById.size).coerceAtLeast(0)
            }
            if (legacyTracks.isNotEmpty()) {
                runCatching { onLegacyMigrated() }
            }
            orderedEntries.map { it.track }
        }
    }

    suspend fun recordPlay(
        track: Track,
        playedAtMs: Long = Clock.nowEpochMillis(),
    ): List<Track> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val clean = track.withoutLocalUri()
            val entry = SyncedHistoryEntry(
                id = clean.id,
                track = clean,
                playedAtMs = playedAtMs,
            )
            val existed = entriesById.containsKey(clean.id)
            entriesById[clean.id] = entry
            rebuildOrderedLocked()

            val path = filePathProvider()
            val line = ndjson.encodeToString(
                HistoryLogLine.serializer(),
                HistoryLogLine(track = clean, playedAtMs = playedAtMs),
            ) + "\n"
            runCatching {
                PlatformFileOps.appendText(path, line)
                if (existed) uncompactedLines++
                if (uncompactedLines >= COMPACTION_THRESHOLD) {
                    compactLocked(path)
                }
            }.onFailure { error ->
                PlatformLog.w("HistoryStore", "Failed to append history log: ${error.message}")
            }

            orderedEntries.map { it.track }
        }
    }

    suspend fun entries(): List<SyncedHistoryEntry> = mutex.withLock {
        orderedEntries
    }

    suspend fun tracks(): List<Track> = mutex.withLock {
        orderedEntries.map { it.track }
    }

    suspend fun mergeRemote(remoteEntries: List<SyncedHistoryEntry>): List<Track> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                var changed = false
                for (remote in remoteEntries) {
                    val clean = remote.track.withoutLocalUri()
                    val existing = entriesById[remote.id]
                    if (existing == null || remote.playedAtMs > existing.playedAtMs) {
                        entriesById[remote.id] = SyncedHistoryEntry(
                            id = remote.id,
                            track = clean,
                            playedAtMs = remote.playedAtMs,
                        )
                        changed = true
                    }
                }
                if (changed) {
                    rebuildOrderedLocked()
                    compactLocked(filePathProvider())
                }
                orderedEntries.map { it.track }
            }
        }

    suspend fun replaceAll(newEntries: List<SyncedHistoryEntry>): List<Track> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                entriesById.clear()
                for (entry in newEntries) {
                    val clean = entry.track.withoutLocalUri()
                    val existing = entriesById[entry.id]
                    if (existing == null || entry.playedAtMs > existing.playedAtMs) {
                        entriesById[entry.id] = SyncedHistoryEntry(
                            id = entry.id,
                            track = clean,
                            playedAtMs = entry.playedAtMs,
                        )
                    }
                }
                rebuildOrderedLocked()
                compactLocked(filePathProvider())
                orderedEntries.map { it.track }
            }
        }

    private fun rebuildOrderedLocked() {
        orderedEntries = entriesById.values.sortedByDescending { it.playedAtMs }
    }

    private fun compactLocked(path: String) {
        val content = buildString {
            // Write oldest-to-newest so sequential replay matches timestamp order
            for (entry in orderedEntries.asReversed()) {
                append(
                    ndjson.encodeToString(
                        HistoryLogLine.serializer(),
                        HistoryLogLine(track = entry.track, playedAtMs = entry.playedAtMs),
                    ),
                )
                append('\n')
            }
        }
        runCatching {
            PlatformFileOps.writeText(path, content)
            uncompactedLines = 0
        }.onFailure { error ->
            PlatformLog.w("HistoryStore", "Failed to compact history log: ${error.message}")
        }
    }

    companion object {
        private const val COMPACTION_THRESHOLD = 100
    }
}
