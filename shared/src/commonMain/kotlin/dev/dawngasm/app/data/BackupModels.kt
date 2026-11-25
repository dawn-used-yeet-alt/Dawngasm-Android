package dev.dawngasm.app.data

import dev.dawngasm.app.model.SearchSyntax
import dev.dawngasm.app.model.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Synced / exported snapshot of Dawngasm library state.
 *
 * Device-local settings (`downloads`, `downloadTreeUri`, Cloudflare clearances,
 * `mediaSessionServiceEnabled`, desktop window bounds) are deliberately excluded,
 * and [Track.localUri] is always stripped.
 */
@Serializable
data class DawngasmBackup(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val exportedAtMs: Long = 0L,
    val saved: List<SyncedTrackEntry> = emptyList(),
    val history: List<SyncedHistoryEntry> = emptyList(),
    val historyArchives: List<HistoryArchiveRef> = emptyList(),
    val blockedTags: List<SyncedTagEntry> = emptyList(),
    val sort: LwwField? = null,
    val window: LwwField? = null,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1

        val json: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
            explicitNulls = false
            coerceInputValues = true
        }

        fun toJson(backup: DawngasmBackup): String =
            json.encodeToString(serializer(), backup)

        fun fromJson(raw: String): DawngasmBackup =
            json.decodeFromString(serializer(), raw)
    }
}

@Serializable
data class SyncedTrackEntry(
    val id: String,
    val track: Track? = null,
    val updatedAtMs: Long,
    val deleted: Boolean = false,
)

@Serializable
data class SyncedHistoryEntry(
    val id: String,
    val track: Track,
    val playedAtMs: Long,
)

@Serializable
data class HistoryArchiveRef(
    val chunkId: String,
    val minPlayedAtMs: Long,
    val maxPlayedAtMs: Long,
    val count: Int,
)

@Serializable
data class HistoryArchiveChunk(
    val chunkId: String,
    val entries: List<SyncedHistoryEntry>,
)

@Serializable
data class SyncedTagEntry(
    val tag: String,
    val updatedAtMs: Long,
    val deleted: Boolean = false,
)

@Serializable
data class LwwField(
    val value: String,
    val updatedAtMs: Long,
)

enum class ImportMode {
    MERGE,
    REPLACE,
}

enum class SyncBackendType(val label: String) {
    NONE("Off"),
    HTTP("HTTP / WebDAV"),
    GITHUB_GIST("GitHub Gist"),
    READ_ONLY_LINK("Read-only link"),
}

@Serializable
data class SyncConfig(
    val enabled: Boolean = false,
    val backend: SyncBackendType = SyncBackendType.NONE,
    val remoteUrl: String = "",
    val gistFilename: String = DEFAULT_GIST_FILENAME,
    val vaultSaltBase64: String? = null,
    val kdfMemoryKiB: Int = 16 * 1024,
    val kdfIterations: Int = 2,
    val kdfParallelism: Int = 1,
    val lastEtag: String? = null,
    val lastSyncAtMs: Long? = null,
    val lastSyncError: String? = null,
) {
    companion object {
        const val DEFAULT_GIST_FILENAME = "dawngasm-backup.txt"
    }
}

/**
 * Local CRDT metadata persisted per device so deletions create tombstones and
 * edits carry LWW timestamps across restarts.
 */
@Serializable
data class LocalSyncMetadata(
    val saved: Map<String, SyncedTrackEntry> = emptyMap(),
    val blockedTags: Map<String, SyncedTagEntry> = emptyMap(),
    val sortUpdatedAtMs: Long = 0L,
    val windowUpdatedAtMs: Long = 0L,
    val archivedHistoryIds: Set<String> = emptySet(),
    val pushedArchiveChunks: Set<String> = emptySet(),
    val dirty: Boolean = false,
    val historyDirty: Boolean = false,
)

/** Strips device-local file URIs before serialization/export/sync. */
fun Track.withoutLocalUri(): Track = if (localUri == null) this else copy(localUri = null)

/** Conflict-free merge helpers for [DawngasmBackup]. */
object BackupMerge {
    fun mergeSaved(
        local: List<SyncedTrackEntry>,
        remote: List<SyncedTrackEntry>,
    ): List<SyncedTrackEntry> {
        val merged = LinkedHashMap<String, SyncedTrackEntry>()
        for (entry in local) {
            merged[entry.id] = entry.copy(track = entry.track?.withoutLocalUri())
        }
        for (entry in remote) {
            val clean = entry.copy(track = entry.track?.withoutLocalUri())
            val existing = merged[clean.id]
            if (existing == null || isNewer(clean.updatedAtMs, clean.deleted, existing.updatedAtMs, existing.deleted)) {
                merged[clean.id] = clean
            }
        }
        return merged.values.sortedByDescending { it.updatedAtMs }
    }

    fun mergeBlockedTags(
        local: List<SyncedTagEntry>,
        remote: List<SyncedTagEntry>,
    ): List<SyncedTagEntry> {
        val merged = LinkedHashMap<String, SyncedTagEntry>()
        for (entry in local) {
            val norm = SearchSyntax.normalizeTag(entry.tag) ?: continue
            merged[norm] = entry.copy(tag = norm)
        }
        for (entry in remote) {
            val norm = SearchSyntax.normalizeTag(entry.tag) ?: continue
            val clean = entry.copy(tag = norm)
            val existing = merged[norm]
            if (existing == null || isNewer(clean.updatedAtMs, clean.deleted, existing.updatedAtMs, existing.deleted)) {
                merged[norm] = clean
            }
        }
        return merged.values.sortedBy { it.tag }
    }

    fun mergeHistory(
        local: List<SyncedHistoryEntry>,
        remote: List<SyncedHistoryEntry>,
    ): List<SyncedHistoryEntry> {
        val byId = LinkedHashMap<String, SyncedHistoryEntry>()
        for (entry in local) {
            val clean = entry.copy(track = entry.track.withoutLocalUri())
            val existing = byId[clean.id]
            if (existing == null || clean.playedAtMs > existing.playedAtMs) {
                byId[clean.id] = clean
            }
        }
        for (entry in remote) {
            val clean = entry.copy(track = entry.track.withoutLocalUri())
            val existing = byId[clean.id]
            if (existing == null || clean.playedAtMs > existing.playedAtMs) {
                byId[clean.id] = clean
            }
        }
        return byId.values.sortedByDescending { it.playedAtMs }
    }

    fun mergeField(local: LwwField?, remote: LwwField?): LwwField? = when {
        local == null -> remote
        remote == null -> local
        remote.updatedAtMs > local.updatedAtMs -> remote
        else -> local
    }

    private fun isNewer(
        candTime: Long,
        candDeleted: Boolean,
        currTime: Long,
        currDeleted: Boolean,
    ): Boolean {
        if (candTime != currTime) return candTime > currTime
        // Deterministic tie-breaker when timestamps are identical: delete wins
        return candDeleted && !currDeleted
    }
}
