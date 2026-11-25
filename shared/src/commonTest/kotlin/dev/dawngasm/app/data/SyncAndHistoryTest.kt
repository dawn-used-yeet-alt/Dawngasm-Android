package dev.dawngasm.app.data

import com.russhwolf.settings.MapSettings
import dev.dawngasm.app.crypto.BackupCrypto
import dev.dawngasm.app.crypto.BackupKdfParams
import dev.dawngasm.app.model.AudioHost
import dev.dawngasm.app.model.Track
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncAndHistoryTest {

    private class InMemorySecureStorage : SecureStorage {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) {
            map[key] = value
        }
        override fun remove(key: String) {
            map.remove(key)
        }
    }

    private class FakeSyncRemote(
        override val isReadOnly: Boolean = false,
        override val supportsArchiveChunks: Boolean = false,
        var failFirstPushWithConflict: Boolean = false,
    ) : SyncRemote {
        var payload: String? = null
        var etag: String? = null
        var pushCount = 0
        val chunks = mutableMapOf<String, String>()
        private var version = 0

        override suspend fun pull(ifNoneMatch: String?): RemotePullResult {
            if (ifNoneMatch != null && ifNoneMatch == etag) {
                return RemotePullResult(null, notModified = true, etag = etag)
            }
            return RemotePullResult(payload, notModified = false, etag = etag)
        }

        override suspend fun push(payload: String, expectedEtag: String?): RemotePushResult {
            if (failFirstPushWithConflict) {
                failFirstPushWithConflict = false
                throw SyncConflictException("Conflict")
            }
            if (expectedEtag != null && expectedEtag != etag) {
                throw SyncConflictException("ETag mismatch")
            }
            this.payload = payload
            version++
            this.etag = "\"v$version\""
            pushCount++
            return RemotePushResult(this.etag)
        }

        override suspend fun pullChunk(chunkId: String): String? = chunks[chunkId]

        override suspend fun pushChunk(chunkId: String, payload: String) {
            chunks[chunkId] = payload
        }
    }

    private fun sampleTrack(id: String, localUri: String? = null) = Track(
        id = id,
        title = "Track $id",
        author = "Author $id",
        host = AudioHost.SOUNDGASM,
        pageUrl = "https://soundgasm.net/u/a/$id",
        localUri = localUri,
    )

    @Test
    fun backupMergeAppliesLwwTombstonesAndHistoryUnion() {
        val localSaved = listOf(
            SyncedTrackEntry("1", sampleTrack("1"), updatedAtMs = 100L, deleted = false),
            SyncedTrackEntry("2", sampleTrack("2"), updatedAtMs = 200L, deleted = false),
        )
        val remoteSaved = listOf(
            SyncedTrackEntry("1", null, updatedAtMs = 150L, deleted = true), // newer delete wins
            SyncedTrackEntry("3", sampleTrack("3"), updatedAtMs = 120L, deleted = false),
        )
        val mergedSaved = BackupMerge.mergeSaved(localSaved, remoteSaved)
        val activeIds = mergedSaved.filter { !it.deleted }.map { it.id }.toSet()
        assertEquals(setOf("2", "3"), activeIds)

        val mergedHistory = BackupMerge.mergeHistory(
            local = listOf(
                SyncedHistoryEntry("1", sampleTrack("1"), playedAtMs = 100L),
                SyncedHistoryEntry("2", sampleTrack("2"), playedAtMs = 300L),
            ),
            remote = listOf(
                SyncedHistoryEntry("1", sampleTrack("1"), playedAtMs = 500L),
                SyncedHistoryEntry("3", sampleTrack("3"), playedAtMs = 200L),
            ),
        )
        assertEquals(listOf("1", "2", "3"), mergedHistory.map { it.id })
        assertEquals(500L, mergedHistory.first().playedAtMs)
    }

    @Test
    fun playbackQueueSliceAroundBoundsBinderPayload() {
        val tracks = (1..250).map { sampleTrack(it.toString()) }

        val middle = PlaybackQueue.sliceAround(tracks[124], tracks, radius = 50)
        assertEquals(101, middle.size)
        assertEquals("75", middle.first().id)
        assertEquals("175", middle.last().id)

        val head = PlaybackQueue.sliceAround(tracks[2], tracks, radius = 50)
        assertEquals(101, head.size)
        assertEquals("1", head.first().id)

        val tail = PlaybackQueue.sliceAround(tracks[248], tracks, radius = 50)
        assertEquals(101, tail.size)
        assertEquals("250", tail.last().id)
    }

    @Test
    fun historyStoreIsUnboundedAndMigratesLegacyPreferences() = runTest {
        val dir = PlatformFileOps.getCacheDir() + "/dawngasm-test-history-${kotlin.random.Random.nextInt()}"
        val filePath = "$dir/history.ndjson"
        val settings = MapSettings()
        val prefs = AppPreferences(settings)
        val legacy = listOf(sampleTrack("legacy-1"), sampleTrack("legacy-2"))
        prefs.saveHistory(legacy)

        try {
            val store = HistoryStore(filePathProvider = { filePath })
            val loaded = store.load(
                legacyTracks = prefs.history(),
                onLegacyMigrated = prefs::clearLegacyHistory,
            )
            assertEquals(listOf("legacy-1", "legacy-2"), loaded.map { it.id })
            assertTrue(prefs.history().isEmpty(), "Legacy KEY_HISTORY should be cleared after migration")

            // Append more than the old 100-item cap
            for (i in 1..150) {
                store.recordPlay(sampleTrack("t-$i"), playedAtMs = 1_000L + i)
            }
            // Replay an older track -> moves to front without duplicating
            store.recordPlay(sampleTrack("t-1"), playedAtMs = 9_999L)

            val reopened = HistoryStore(filePathProvider = { filePath })
            val reloaded = reopened.load()
            assertEquals(152, reloaded.size)
            assertEquals("t-1", reloaded.first().id)
        } finally {
            PlatformFileOps.deleteFile(filePath)
        }
    }

    @Test
    fun backupUrlResolverNormalizesGithubBlobAndGistLinks() {
        assertEquals(
            "https://raw.githubusercontent.com/user/repo/main/backup.txt",
            BackupUrlResolver.normalizeDirectUrl("https://github.com/user/repo/blob/main/backup.txt"),
        )
        assertEquals(
            "abc123def4567890",
            BackupUrlResolver.extractGistId("https://gist.github.com/someone/abc123def4567890"),
        )
        assertEquals(
            "abc123def4567890",
            BackupUrlResolver.extractGistId("https://gist.githubusercontent.com/someone/abc123def4567890/raw"),
        )
        assertTrue(BackupUrlResolver.isAllowedImportUrl("https://example.com/backup.txt"))
        assertFalse(BackupUrlResolver.isAllowedImportUrl("http://example.com/backup.txt"))
    }

    @Test
    fun exportImportStripsLocalUriAndPreservesDeviceLocalSettings() = runTest {
        val fastKdf = BackupKdfParams(memoryKiB = 64, iterations = 1, parallelism = 1)
        val dir = PlatformFileOps.getCacheDir() + "/dawngasm-test-sync-${kotlin.random.Random.nextInt()}"
        val srcFile = "$dir/src-history.ndjson"
        val dstFile = "$dir/dst-history.ndjson"

        try {
            val srcPrefs = AppPreferences(MapSettings())
            srcPrefs.saveSaved(listOf(sampleTrack("s1", localUri = "file:///secret/local.m4a")))
            srcPrefs.saveBlockedTags(listOf("tag1", "tag2"))
            srcPrefs.saveFeed("new", "month")
            val srcHistory = HistoryStore { srcFile }
            srcHistory.load()
            srcHistory.recordPlay(sampleTrack("h1", localUri = "content://local/h1"), playedAtMs = 5000L)
            val srcEngine = SyncEngine(
                prefs = srcPrefs,
                historyStore = srcHistory,
                scope = this,
                secureStorage = InMemorySecureStorage(),
                kdfParams = fastKdf,
            )

            val exported = srcEngine.exportEncrypted("sync-pass-123")
            assertTrue(BackupCrypto.isEncryptedBackup(exported))

            // Destination device has its own device-local settings
            val dstPrefs = AppPreferences(MapSettings())
            dstPrefs.saveDownloads(listOf(sampleTrack("dl1", localUri = "file:///my/dl1.m4a")))
            dstPrefs.saveDownloadTreeUri("content://tree/custom")
            dstPrefs.saveHotaudioClearance("cf_clearance=abc", "UA")
            dstPrefs.saveMediaSessionServiceEnabled(false)
            dstPrefs.saveSaved(listOf(sampleTrack("local-saved")))
            val dstHistory = HistoryStore { dstFile }
            dstHistory.load()

            val dstEngine = SyncEngine(
                prefs = dstPrefs,
                historyStore = dstHistory,
                scope = this,
                secureStorage = InMemorySecureStorage(),
                kdfParams = fastKdf,
            )

            val merged = dstEngine.importEncrypted(exported, "sync-pass-123", ImportMode.MERGE)
            assertEquals(setOf("s1", "local-saved"), merged.saved.map { it.id }.toSet())
            assertNull(merged.saved.first { it.id == "s1" }.localUri)
            assertEquals(listOf("h1"), merged.history.map { it.id })
            assertNull(merged.history.first().localUri)
            assertEquals(listOf("tag1", "tag2"), merged.blockedTags)
            assertEquals("new", merged.sort)
            assertEquals("month", merged.window)

            // Device-local settings must remain untouched
            assertEquals(listOf("dl1"), dstPrefs.downloads().map { it.id })
            assertEquals("content://tree/custom", dstPrefs.downloadTreeUri())
            assertEquals("cf_clearance=abc" to "UA", dstPrefs.hotaudioClearance())
            assertFalse(dstPrefs.mediaSessionServiceEnabled())
        } finally {
            PlatformFileOps.deleteFile(srcFile)
            PlatformFileOps.deleteFile(dstFile)
        }
    }

    @Test
    fun continuousSyncPullMergePushAndCachesVaultKeySecurely() = runTest {
        val fastKdf = BackupKdfParams(memoryKiB = 64, iterations = 1, parallelism = 1)
        val remote = FakeSyncRemote(supportsArchiveChunks = true)
        val dir = PlatformFileOps.getCacheDir() + "/dawngasm-test-cont-${kotlin.random.Random.nextInt()}"
        val fileA = "$dir/a.ndjson"
        val fileB = "$dir/b.ndjson"

        try {
            val prefsA = AppPreferences(MapSettings())
            val secureA = InMemorySecureStorage()
            val histA = HistoryStore { fileA }.also { it.load() }
            val engineA = SyncEngine(
                prefs = prefsA,
                historyStore = histA,
                scope = this,
                secureStorage = secureA,
                kdfParams = fastKdf,
                remoteFactory = { _, _ -> remote },
            )

            prefsA.saveSaved(listOf(sampleTrack("a1")))
            engineA.onSavedChanged(prefsA.saved())
            val statusA = engineA.configureSync(
                backend = SyncBackendType.GITHUB_GIST,
                remoteUrl = "abc123def4567890",
                password = "shared-vault-password",
                authToken = "ghp_secret_pat",
            )
            assertNull(statusA.config.lastSyncError)
            assertEquals(1, remote.pushCount)
            assertTrue(secureA.map.containsKey(SyncEngine.KEY_VAULT_KEY))
            assertTrue(secureA.map.containsKey(SyncEngine.KEY_AUTH_TOKEN))
            // Secrets must never appear in AppPreferences
            val rawConfig = prefsA.syncConfig().toString()
            assertFalse(rawConfig.contains("ghp_secret_pat"))
            assertFalse(rawConfig.contains("shared-vault-password"))

            // Second device joins with same password and merges its own track
            val prefsB = AppPreferences(MapSettings())
            val secureB = InMemorySecureStorage()
            val histB = HistoryStore { fileB }.also { it.load() }
            val engineB = SyncEngine(
                prefs = prefsB,
                historyStore = histB,
                scope = this,
                secureStorage = secureB,
                kdfParams = fastKdf,
                remoteFactory = { _, _ -> remote },
            )
            prefsB.saveSaved(listOf(sampleTrack("b1")))
            engineB.onSavedChanged(prefsB.saved())
            remote.failFirstPushWithConflict = true
            val statusB = engineB.configureSync(
                backend = SyncBackendType.GITHUB_GIST,
                remoteUrl = "abc123def4567890",
                password = "shared-vault-password",
                authToken = "ghp_secret_pat",
            )
            assertNull(statusB.config.lastSyncError)
            assertEquals(setOf("a1", "b1"), prefsB.saved().map { it.id }.toSet())

            // Device A pulls B's update
            val pullA = engineA.syncNow()
            assertNull(pullA.config.lastSyncError)
            assertEquals(setOf("a1", "b1"), prefsA.saved().map { it.id }.toSet())
        } finally {
            PlatformFileOps.deleteFile(fileA)
            PlatformFileOps.deleteFile(fileB)
        }
    }
}
