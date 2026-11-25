package dev.dawngasm.app.data

import dev.dawngasm.app.crypto.BackupCrypto
import dev.dawngasm.app.crypto.BackupCryptoException
import dev.dawngasm.app.crypto.BackupKdfParams
import dev.dawngasm.app.crypto.Blake2b
import dev.dawngasm.app.crypto.secureRandomBytes
import dev.dawngasm.app.model.SearchSyntax
import dev.dawngasm.app.model.Track
import dev.dawngasm.app.util.Clock
import dev.dawngasm.app.util.PlatformLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class AppliedSyncState(
    val saved: List<Track>,
    val history: List<Track>,
    val blockedTags: List<String>,
    val sort: String,
    val window: String,
    val config: SyncConfig,
)

data class SyncStatus(
    val config: SyncConfig = SyncConfig(),
    val isSyncing: Boolean = false,
    val needsUnlock: Boolean = false,
    val lastMessage: String? = null,
)

class SyncNeedsUnlockException(message: String) : Exception(message)

/**
 * Coordinates one-time encrypted export/import and continuous conflict-free sync
 * across Android, Desktop, and iOS.
 */
class SyncEngine(
    private val prefs: AppPreferences,
    private val historyStore: HistoryStore,
    private val scope: CoroutineScope,
    private val secureStorage: SecureStorage = PlatformSecureStorage,
    private val kdfParams: BackupKdfParams = BackupKdfParams(),
    private val remoteFactory: (SyncConfig, String?) -> SyncRemote? = ::defaultRemoteFactory,
    private val onStateApplied: suspend (AppliedSyncState) -> Unit = {},
) {
    private val syncMutex = Mutex()
    private val metaMutex = Mutex()
    private var debounceJob: Job? = null
    private var debounceIsHistoryOnly = false

    private val _status = MutableStateFlow(SyncStatus(config = prefs.syncConfig()))
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    // --- One-time export / import -----------------------------------------

    suspend fun exportEncrypted(password: String): String = withContext(Dispatchers.Default) {
        if (password.isEmpty()) throw BackupCryptoException("Password cannot be empty")
        val now = Clock.nowEpochMillis()
        val backup = buildLocalBackup(now = now, inlineLimit = null)
        val json = DawngasmBackup.toJson(backup)
        BackupCrypto.encrypt(json, password, kdfParams)
    }

    suspend fun importEncrypted(
        source: String,
        password: String,
        mode: ImportMode = ImportMode.MERGE,
    ): AppliedSyncState {
        if (password.isEmpty()) throw BackupCryptoException("Password cannot be empty")
        val encryptedText = resolveImportPayload(source)
        val decrypted = withContext(Dispatchers.Default) {
            BackupCrypto.decrypt(encryptedText, password)
        }
        val remoteBackup = runCatching { DawngasmBackup.fromJson(decrypted.plaintextJson) }
            .getOrElse { throw BackupCryptoException("Decrypted backup JSON is invalid", it) }

        val applied = applyImportedBackup(remoteBackup, mode)
        onStateApplied(applied)

        if (applied.config.enabled) {
            scheduleSync(historyOnly = false)
        }
        return applied
    }

    internal suspend fun resolveImportPayload(source: String): String {
        val trimmed = source.trim()
        if (trimmed.isEmpty()) {
            throw IllegalArgumentException("Paste encrypted backup text, an HTTPS link, or choose a file")
        }
        if (BackupCrypto.isEncryptedBackup(trimmed)) {
            return trimmed
        }
        if (trimmed.startsWith("https://", ignoreCase = true)) {
            val pulled = BackupUrlResolver.fetchText(trimmed, requireHttps = true)
            val text = pulled.payload?.trim().orEmpty()
            if (!BackupCrypto.isEncryptedBackup(text)) {
                throw BackupCryptoException("Link did not return a valid Dawngasm encrypted backup")
            }
            return text
        }
        if (trimmed.startsWith("http://", ignoreCase = true)) {
            throw IllegalArgumentException("Only HTTPS links are allowed for backup import")
        }
        val localPath = trimmed.removePrefix("file://")
        val fileText = PlatformFileOps.readText(localPath)?.trim()
        if (!fileText.isNullOrEmpty()) {
            if (!BackupCrypto.isEncryptedBackup(fileText)) {
                throw BackupCryptoException("Selected file does not contain a valid Dawngasm encrypted backup")
            }
            return fileText
        }
        throw IllegalArgumentException("Unrecognized backup input — expected DAWNGASM-ENC-V1 text, HTTPS link, or file path")
    }

    private suspend fun applyImportedBackup(
        remote: DawngasmBackup,
        mode: ImportMode,
    ): AppliedSyncState = metaMutex.withLock {
        val now = Clock.nowEpochMillis()
        val currentMeta = ensureInitializedMetaLocked(now)

        val applied = when (mode) {
            ImportMode.MERGE -> {
                val localSaved = currentMeta.saved.values.toList()
                val mergedSavedEntries = BackupMerge.mergeSaved(localSaved, remote.saved)
                val activeSaved = mergedSavedEntries
                    .filter { !it.deleted && it.track != null }
                    .mapNotNull { it.track?.withoutLocalUri() }

                val localTags = currentMeta.blockedTags.values.toList()
                val mergedTagEntries = BackupMerge.mergeBlockedTags(localTags, remote.blockedTags)
                val activeTags = mergedTagEntries.filter { !it.deleted }.map { it.tag }

                val localSort = LwwField(prefs.sort(), currentMeta.sortUpdatedAtMs)
                val mergedSort = BackupMerge.mergeField(localSort, remote.sort)
                val finalSort = mergedSort?.value?.takeIf { it in SearchSyntax.SORTS } ?: prefs.sort()

                val localWindow = LwwField(prefs.window(), currentMeta.windowUpdatedAtMs)
                val mergedWindow = BackupMerge.mergeField(localWindow, remote.window)
                val finalWindow = mergedWindow?.value?.takeIf { SearchSyntax.normalizeWindow(it) != null }
                    ?: prefs.window()

                val mergedHistory = historyStore.mergeRemote(remote.history)

                prefs.saveSaved(activeSaved)
                prefs.saveBlockedTags(activeTags)
                prefs.saveFeed(finalSort, finalWindow)
                val nextMeta = currentMeta.copy(
                    saved = mergedSavedEntries.associateBy { it.id },
                    blockedTags = mergedTagEntries.associateBy { it.tag },
                    sortUpdatedAtMs = mergedSort?.updatedAtMs ?: currentMeta.sortUpdatedAtMs,
                    windowUpdatedAtMs = mergedWindow?.updatedAtMs ?: currentMeta.windowUpdatedAtMs,
                    dirty = true,
                )
                prefs.saveLocalSyncMetadata(nextMeta)

                AppliedSyncState(
                    saved = activeSaved,
                    history = mergedHistory,
                    blockedTags = activeTags,
                    sort = finalSort,
                    window = finalWindow,
                    config = prefs.syncConfig(),
                )
            }

            ImportMode.REPLACE -> {
                val activeSaved = remote.saved
                    .filter { !it.deleted && it.track != null }
                    .mapNotNull { it.track?.withoutLocalUri() }
                val activeTags = remote.blockedTags
                    .filter { !it.deleted }
                    .mapNotNull { SearchSyntax.normalizeTag(it.tag) }
                    .distinct()
                val finalSort = remote.sort?.value?.takeIf { it in SearchSyntax.SORTS }
                    ?: SearchSyntax.DEFAULT_SORT
                val finalWindow = remote.window?.value?.takeIf { SearchSyntax.normalizeWindow(it) != null }
                    ?: SearchSyntax.DEFAULT_WINDOW

                val replacedHistory = historyStore.replaceAll(remote.history)

                // Tombstone any previously local items removed by Replace so continuous sync converges
                val nextSavedMeta = LinkedHashMap<String, SyncedTrackEntry>()
                for (prev in currentMeta.saved.values) {
                    nextSavedMeta[prev.id] = SyncedTrackEntry(
                        id = prev.id,
                        track = null,
                        updatedAtMs = now,
                        deleted = true,
                    )
                }
                for (track in activeSaved) {
                    nextSavedMeta[track.id] = SyncedTrackEntry(
                        id = track.id,
                        track = track,
                        updatedAtMs = now,
                        deleted = false,
                    )
                }

                val nextTagMeta = LinkedHashMap<String, SyncedTagEntry>()
                for (prev in currentMeta.blockedTags.values) {
                    nextTagMeta[prev.tag] = SyncedTagEntry(
                        tag = prev.tag,
                        updatedAtMs = now,
                        deleted = true,
                    )
                }
                for (tag in activeTags) {
                    nextTagMeta[tag] = SyncedTagEntry(
                        tag = tag,
                        updatedAtMs = now,
                        deleted = false,
                    )
                }

                prefs.saveSaved(activeSaved)
                prefs.saveBlockedTags(activeTags)
                prefs.saveFeed(finalSort, finalWindow)
                prefs.saveLocalSyncMetadata(
                    LocalSyncMetadata(
                        saved = nextSavedMeta,
                        blockedTags = nextTagMeta,
                        sortUpdatedAtMs = now,
                        windowUpdatedAtMs = now,
                        dirty = true,
                        historyDirty = true,
                    ),
                )

                AppliedSyncState(
                    saved = activeSaved,
                    history = replacedHistory,
                    blockedTags = activeTags,
                    sort = finalSort,
                    window = finalWindow,
                    config = prefs.syncConfig(),
                )
            }
        }
        applied
    }

    // --- Local mutation tracking for continuous sync ----------------------

    suspend fun onSavedChanged(saved: List<Track>) = metaMutex.withLock {
        val now = Clock.nowEpochMillis()
        val meta = ensureInitializedMetaLocked(now)
        val nextSaved = LinkedHashMap(meta.saved)
        val newIds = saved.mapTo(HashSet()) { it.id }

        // Mark removed tracks as deleted tombstones
        for ((id, existing) in meta.saved) {
            if (id !in newIds && !existing.deleted) {
                nextSaved[id] = SyncedTrackEntry(
                    id = id,
                    track = null,
                    updatedAtMs = now,
                    deleted = true,
                )
            }
        }
        // Record/update present tracks
        for (track in saved) {
            val existing = nextSaved[track.id]
            if (existing == null || existing.deleted) {
                nextSaved[track.id] = SyncedTrackEntry(
                    id = track.id,
                    track = track.withoutLocalUri(),
                    updatedAtMs = now,
                    deleted = false,
                )
            }
        }

        prefs.saveLocalSyncMetadata(meta.copy(saved = nextSaved, dirty = true))
        if (prefs.syncConfig().enabled) {
            scheduleSync(historyOnly = false)
        }
    }

    suspend fun onBlockedTagsChanged(tags: List<String>) = metaMutex.withLock {
        val now = Clock.nowEpochMillis()
        val meta = ensureInitializedMetaLocked(now)
        val nextTags = LinkedHashMap(meta.blockedTags)
        val normalized = tags.mapNotNull { SearchSyntax.normalizeTag(it) }.distinct()
        val newSet = normalized.toHashSet()

        for ((tag, existing) in meta.blockedTags) {
            if (tag !in newSet && !existing.deleted) {
                nextTags[tag] = SyncedTagEntry(tag = tag, updatedAtMs = now, deleted = true)
            }
        }
        for (tag in normalized) {
            val existing = nextTags[tag]
            if (existing == null || existing.deleted) {
                nextTags[tag] = SyncedTagEntry(tag = tag, updatedAtMs = now, deleted = false)
            }
        }

        prefs.saveLocalSyncMetadata(meta.copy(blockedTags = nextTags, dirty = true))
        if (prefs.syncConfig().enabled) {
            scheduleSync(historyOnly = false)
        }
    }

    suspend fun onFeedSettingsChanged() = metaMutex.withLock {
        val now = Clock.nowEpochMillis()
        val meta = ensureInitializedMetaLocked(now)
        prefs.saveLocalSyncMetadata(
            meta.copy(
                sortUpdatedAtMs = now,
                windowUpdatedAtMs = now,
                dirty = true,
            ),
        )
        if (prefs.syncConfig().enabled) {
            scheduleSync(historyOnly = false)
        }
    }

    suspend fun onHistoryRecorded() = metaMutex.withLock {
        val now = Clock.nowEpochMillis()
        val meta = ensureInitializedMetaLocked(now)
        prefs.saveLocalSyncMetadata(meta.copy(historyDirty = true))
        if (prefs.syncConfig().enabled) {
            scheduleSync(historyOnly = true)
        }
    }

    // --- Continuous Sync Configuration & Execution ------------------------

    suspend fun configureSync(
        backend: SyncBackendType,
        remoteUrl: String,
        password: String,
        authToken: String? = null,
        gistFilename: String = SyncConfig.DEFAULT_GIST_FILENAME,
    ): SyncStatus {
        require(backend != SyncBackendType.NONE) { "Select a sync backend" }
        require(remoteUrl.isNotBlank()) { "Remote URL or Gist ID is required" }
        if (password.isEmpty()) throw BackupCryptoException("Sync password cannot be empty")

        val cleanToken = authToken?.trim().orEmpty()
        if (cleanToken.isNotEmpty()) {
            secureStorage.putString(KEY_AUTH_TOKEN, cleanToken)
        } else {
            secureStorage.remove(KEY_AUTH_TOKEN)
        }

        // Probe existing remote first so if another device already created the vault,
        // we adopt its salt/KDF params and cache the derived key directly.
        val provisionalConfig = SyncConfig(
            enabled = true,
            backend = backend,
            remoteUrl = remoteUrl.trim(),
            gistFilename = gistFilename.ifBlank { SyncConfig.DEFAULT_GIST_FILENAME },
            vaultSaltBase64 = null,
            kdfMemoryKiB = kdfParams.memoryKiB,
            kdfIterations = kdfParams.iterations,
            kdfParallelism = kdfParams.parallelism,
        )
        val remote = remoteFactory(provisionalConfig, cleanToken.ifEmpty { null })
            ?: throw IllegalStateException("Could not create sync remote")

        var vaultSalt = secureRandomBytes(BackupCrypto.SALT_SIZE)
        var effectiveParams = kdfParams
        var derivedKey: ByteArray? = null

        val probe = runCatching { remote.pull(ifNoneMatch = null) }.getOrNull()
        val existingPayload = probe?.payload?.trim()
        if (!existingPayload.isNullOrEmpty() && BackupCrypto.isEncryptedBackup(existingPayload)) {
            val decrypted = withContext(Dispatchers.Default) {
                BackupCrypto.decrypt(existingPayload, password)
            }
            vaultSalt = decrypted.header.salt
            effectiveParams = decrypted.header.kdfParams
            derivedKey = decrypted.derivedKey
        }

        val finalKey = derivedKey ?: withContext(Dispatchers.Default) {
            BackupCrypto.deriveKey(password, vaultSalt, effectiveParams)
        }
        secureStorage.putString(KEY_VAULT_KEY, BackupCrypto.encodeUrlSafeBase64(finalKey))

        val savedConfig = provisionalConfig.copy(
            vaultSaltBase64 = BackupCrypto.encodeUrlSafeBase64(vaultSalt),
            kdfMemoryKiB = effectiveParams.memoryKiB,
            kdfIterations = effectiveParams.iterations,
            kdfParallelism = effectiveParams.parallelism,
            lastEtag = null,
            lastSyncError = null,
        )
        prefs.saveSyncConfig(savedConfig)
        metaMutex.withLock {
            val meta = ensureInitializedMetaLocked(Clock.nowEpochMillis())
            prefs.saveLocalSyncMetadata(meta.copy(dirty = true))
        }
        _status.value = SyncStatus(config = savedConfig)
        return syncNow(passwordOverride = password)
    }

    suspend fun unlockWithPassword(password: String): SyncStatus {
        if (password.isEmpty()) throw BackupCryptoException("Password cannot be empty")
        val config = prefs.syncConfig()
        val saltB64 = config.vaultSaltBase64
        val salt = if (!saltB64.isNullOrBlank()) {
            BackupCrypto.decodeUrlSafeBase64(saltB64)
        } else {
            secureRandomBytes(BackupCrypto.SALT_SIZE)
        }
        val params = BackupKdfParams(
            memoryKiB = config.kdfMemoryKiB,
            iterations = config.kdfIterations,
            parallelism = config.kdfParallelism,
        )
        val key = withContext(Dispatchers.Default) {
            BackupCrypto.deriveKey(password, salt, params)
        }
        secureStorage.putString(KEY_VAULT_KEY, BackupCrypto.encodeUrlSafeBase64(key))
        val updated = config.copy(
            vaultSaltBase64 = BackupCrypto.encodeUrlSafeBase64(salt),
            lastSyncError = null,
        )
        prefs.saveSyncConfig(updated)
        _status.value = _status.value.copy(config = updated, needsUnlock = false)
        return syncNow(passwordOverride = password)
    }

    fun disableSync(clearSecrets: Boolean = true) {
        debounceJob?.cancel()
        debounceJob = null
        val updated = prefs.syncConfig().copy(
            enabled = false,
            lastSyncError = null,
        )
        prefs.saveSyncConfig(updated)
        if (clearSecrets) {
            secureStorage.remove(KEY_VAULT_KEY)
            secureStorage.remove(KEY_AUTH_TOKEN)
        }
        _status.value = SyncStatus(config = updated)
    }

    fun scheduleSync(historyOnly: Boolean) {
        val config = prefs.syncConfig()
        if (!config.enabled || config.backend == SyncBackendType.NONE) return

        // Never downgrade a quick metadata sync debounce to the longer history debounce
        if (debounceJob?.isActive == true && !debounceIsHistoryOnly && historyOnly) {
            return
        }
        debounceJob?.cancel()
        debounceIsHistoryOnly = historyOnly
        val delayMs = if (historyOnly) HISTORY_DEBOUNCE_MS else NORMAL_DEBOUNCE_MS
        debounceJob = scope.launch {
            delay(delayMs)
            runCatching { syncNow() }
        }
    }

    suspend fun flushPendingSync() {
        val config = prefs.syncConfig()
        if (!config.enabled || config.backend == SyncBackendType.NONE) return
        val meta = prefs.localSyncMetadata()
        if (meta.dirty || meta.historyDirty || debounceJob?.isActive == true) {
            debounceJob?.cancel()
            syncNow()
        }
    }

    suspend fun syncNow(passwordOverride: String? = null): SyncStatus = syncMutex.withLock {
        val initialConfig = prefs.syncConfig()
        if (!initialConfig.enabled || initialConfig.backend == SyncBackendType.NONE) {
            val status = SyncStatus(config = initialConfig)
            _status.value = status
            return status
        }

        _status.value = _status.value.copy(config = initialConfig, isSyncing = true)

        val authToken = secureStorage.getString(KEY_AUTH_TOKEN)?.takeIf { it.isNotBlank() }
        val remote = try {
            remoteFactory(initialConfig, authToken)
                ?: throw IllegalStateException("Sync backend is not configured")
        } catch (error: Exception) {
            val failedConfig = initialConfig.copy(lastSyncError = error.message ?: "Invalid sync config")
            prefs.saveSyncConfig(failedConfig)
            val status = SyncStatus(config = failedConfig, isSyncing = false)
            _status.value = status
            return status
        }

        var workingConfig = initialConfig

        try {
            var attempts = 0
            var resultStatus: SyncStatus? = null
            while (resultStatus == null) {
                attempts++
                var cachedKey = secureStorage.getString(KEY_VAULT_KEY)
                    ?.let { runCatching { BackupCrypto.decodeUrlSafeBase64(it) }.getOrNull() }
                    ?.takeIf { it.size == BackupCrypto.KEY_SIZE }

                val pullResult = remote.pull(ifNoneMatch = workingConfig.lastEtag)
                var currentEtag = pullResult.etag
                var mergedChangedLocally = false
                var remoteMissingOrEmpty = false

                if (!pullResult.notModified) {
                    val payload = pullResult.payload?.trim()
                    if (payload.isNullOrEmpty()) {
                        remoteMissingOrEmpty = true
                    } else {
                        val header = BackupCrypto.inspectHeader(payload)
                        val storedSalt = workingConfig.vaultSaltBase64?.let {
                            runCatching { BackupCrypto.decodeUrlSafeBase64(it) }.getOrNull()
                        }
                        val matchesCached = cachedKey != null &&
                            storedSalt != null &&
                            header.salt.contentEquals(storedSalt) &&
                            header.kdfParams.memoryKiB == workingConfig.kdfMemoryKiB &&
                            header.kdfParams.iterations == workingConfig.kdfIterations &&
                            header.kdfParams.parallelism == workingConfig.kdfParallelism

                        val decrypted = withContext(Dispatchers.Default) {
                            when {
                                matchesCached -> BackupCrypto.decryptWithKey(payload, cachedKey!!)
                                passwordOverride != null -> {
                                    val dec = BackupCrypto.decrypt(payload, passwordOverride)
                                    // Cache adopted remote key & salt in secure storage
                                    secureStorage.putString(
                                        KEY_VAULT_KEY,
                                        BackupCrypto.encodeUrlSafeBase64(dec.derivedKey),
                                    )
                                    workingConfig = workingConfig.copy(
                                        vaultSaltBase64 = BackupCrypto.encodeUrlSafeBase64(dec.header.salt),
                                        kdfMemoryKiB = dec.header.kdfParams.memoryKiB,
                                        kdfIterations = dec.header.kdfParams.iterations,
                                        kdfParallelism = dec.header.kdfParams.parallelism,
                                    )
                                    cachedKey = dec.derivedKey
                                    dec
                                }
                                else -> throw SyncNeedsUnlockException(
                                    "Remote vault password changed — unlock sync in Settings",
                                )
                            }
                        }

                        val remoteBackup = DawngasmBackup.fromJson(decrypted.plaintextJson)
                        val pulledArchiveEntries = pullMissingArchiveChunks(
                            remote = remote,
                            refs = remoteBackup.historyArchives,
                            vaultKey = decrypted.derivedKey,
                        )

                        val mergeResult = applyRemoteMerge(
                            remote = remoteBackup,
                            extraHistory = pulledArchiveEntries,
                            adoptedArchives = remoteBackup.historyArchives,
                        )
                        mergedChangedLocally = mergeResult.localChanged
                        if (mergeResult.localChanged) {
                            onStateApplied(mergeResult.appliedState.copy(config = workingConfig))
                        }
                    }
                }

                val meta = prefs.localSyncMetadata()
                val shouldPush = !remote.isReadOnly && (
                    meta.dirty ||
                        meta.historyDirty ||
                        remoteMissingOrEmpty ||
                        mergedChangedLocally
                    )

                if (shouldPush) {
                    val activeKey = cachedKey
                        ?: throw SyncNeedsUnlockException("Sync key is missing — unlock sync in Settings")
                    val activeSalt = workingConfig.vaultSaltBase64?.let {
                        runCatching { BackupCrypto.decodeUrlSafeBase64(it) }.getOrNull()
                    } ?: throw SyncNeedsUnlockException("Sync vault salt is missing — reconfigure sync")
                    val activeParams = BackupKdfParams(
                        memoryKiB = workingConfig.kdfMemoryKiB,
                        iterations = workingConfig.kdfIterations,
                        parallelism = workingConfig.kdfParallelism,
                    )

                    val pushedArchives = rollAndPushArchiveChunksIfSupported(
                        remote = remote,
                        vaultKey = activeKey,
                        salt = activeSalt,
                        params = activeParams,
                    )
                    val backupToPush = buildLocalBackup(
                        now = Clock.nowEpochMillis(),
                        inlineLimit = if (remote.supportsArchiveChunks) INLINE_HISTORY_LIMIT else null,
                        archiveRefs = pushedArchives,
                    )
                    val encryptedPayload = withContext(Dispatchers.Default) {
                        BackupCrypto.encryptWithKey(
                            jsonText = DawngasmBackup.toJson(backupToPush),
                            derivedKey = activeKey,
                            salt = activeSalt,
                            params = activeParams,
                        )
                    }

                    try {
                        val pushResult = remote.push(
                            payload = encryptedPayload,
                            expectedEtag = currentEtag,
                        )
                        currentEtag = pushResult.etag ?: currentEtag
                    } catch (conflict: SyncConflictException) {
                        if (attempts < MAX_CONFLICT_RETRIES) {
                            PlatformLog.w("SyncEngine", "Conflict on push (attempt $attempts), retrying pull+merge")
                            continue
                        }
                        throw conflict
                    }
                }

                val now = Clock.nowEpochMillis()
                workingConfig = workingConfig.copy(
                    lastEtag = currentEtag,
                    lastSyncAtMs = now,
                    lastSyncError = null,
                )
                prefs.saveSyncConfig(workingConfig)
                metaMutex.withLock {
                    val latestMeta = prefs.localSyncMetadata()
                    prefs.saveLocalSyncMetadata(
                        latestMeta.copy(
                            dirty = false,
                            historyDirty = false,
                        ),
                    )
                }

                val okStatus = SyncStatus(
                    config = workingConfig,
                    isSyncing = false,
                    needsUnlock = false,
                    lastMessage = "Synced",
                )
                _status.value = okStatus
                resultStatus = okStatus
            }
            resultStatus
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unlock: SyncNeedsUnlockException) {
            val failedConfig = workingConfig.copy(lastSyncError = unlock.message)
            prefs.saveSyncConfig(failedConfig)
            val status = SyncStatus(config = failedConfig, isSyncing = false, needsUnlock = true)
            _status.value = status
            status
        } catch (error: Exception) {
            PlatformLog.e("SyncEngine", "Sync failed: ${error.message}", error)
            val failedConfig = workingConfig.copy(
                lastSyncError = error.message ?: "Sync failed",
            )
            prefs.saveSyncConfig(failedConfig)
            val status = SyncStatus(config = failedConfig, isSyncing = false, needsUnlock = false)
            _status.value = status
            status
        }
    }

    private suspend fun pullMissingArchiveChunks(
        remote: SyncRemote,
        refs: List<HistoryArchiveRef>,
        vaultKey: ByteArray,
    ): List<SyncedHistoryEntry> {
        if (refs.isEmpty()) return emptyList()
        val knownChunks = prefs.localSyncMetadata().pushedArchiveChunks
        val result = ArrayList<SyncedHistoryEntry>()
        for (ref in refs) {
            if (ref.chunkId in knownChunks) continue
            val encryptedChunk = runCatching { remote.pullChunk(ref.chunkId) }.getOrNull() ?: continue
            val decrypted = withContext(Dispatchers.Default) {
                runCatching { BackupCrypto.decryptWithKey(encryptedChunk, vaultKey) }.getOrNull()
            } ?: continue
            val chunk = runCatching {
                DawngasmBackup.json.decodeFromString(HistoryArchiveChunk.serializer(), decrypted.plaintextJson)
            }.getOrNull() ?: continue
            result.addAll(chunk.entries)
        }
        return result
    }

    private suspend fun rollAndPushArchiveChunksIfSupported(
        remote: SyncRemote,
        vaultKey: ByteArray,
        salt: ByteArray,
        params: BackupKdfParams,
    ): List<HistoryArchiveRef> {
        if (!remote.supportsArchiveChunks) return emptyList()
        val allEntries = historyStore.entries()
        if (allEntries.size <= INLINE_HISTORY_LIMIT) return emptyList()

        // Entries are sorted newest-first; roll oldest entries beyond INLINE_HISTORY_LIMIT
        // into deterministic immutable chunks of ARCHIVE_CHUNK_SIZE.
        val tailOldestFirst = allEntries.drop(INLINE_HISTORY_LIMIT).asReversed()
        val chunks = tailOldestFirst.chunked(ARCHIVE_CHUNK_SIZE)
        val meta = prefs.localSyncMetadata()
        val pushed = meta.pushedArchiveChunks.toMutableSet()
        val archivedIds = meta.archivedHistoryIds.toMutableSet()
        val refs = ArrayList<HistoryArchiveRef>(chunks.size)

        for (entries in chunks) {
            // Only finalize full chunks so the active growing edge stays inline
            if (entries.size < ARCHIVE_CHUNK_SIZE) continue
            val minTime = entries.minOf { it.playedAtMs }
            val maxTime = entries.maxOf { it.playedAtMs }
            val chunkKeyInput = entries.joinToString("|") { "${it.id}:${it.playedAtMs}" }.encodeToByteArray()
            val chunkId = Blake2b.hash(chunkKeyInput, outLen = 8).joinToString("") {
                ((it.toInt() and 0xFF) or 0x100).toString(16).substring(1)
            }
            if (chunkId !in pushed) {
                val chunkPayload = DawngasmBackup.json.encodeToString(
                    HistoryArchiveChunk.serializer(),
                    HistoryArchiveChunk(chunkId = chunkId, entries = entries),
                )
                val encryptedChunk = withContext(Dispatchers.Default) {
                    BackupCrypto.encryptWithKey(chunkPayload, vaultKey, salt, params)
                }
                remote.pushChunk(chunkId, encryptedChunk)
                pushed.add(chunkId)
                entries.forEach { archivedIds.add(it.id) }
            }
            refs.add(
                HistoryArchiveRef(
                    chunkId = chunkId,
                    minPlayedAtMs = minTime,
                    maxPlayedAtMs = maxTime,
                    count = entries.size,
                ),
            )
        }

        if (pushed.size != meta.pushedArchiveChunks.size) {
            metaMutex.withLock {
                val latest = prefs.localSyncMetadata()
                prefs.saveLocalSyncMetadata(
                    latest.copy(
                        pushedArchiveChunks = latest.pushedArchiveChunks + pushed,
                        archivedHistoryIds = latest.archivedHistoryIds + archivedIds,
                    ),
                )
            }
        }
        return refs
    }

    private data class RemoteMergeResult(
        val localChanged: Boolean,
        val appliedState: AppliedSyncState,
    )

    private suspend fun applyRemoteMerge(
        remote: DawngasmBackup,
        extraHistory: List<SyncedHistoryEntry>,
        adoptedArchives: List<HistoryArchiveRef>,
    ): RemoteMergeResult = metaMutex.withLock {
        val now = Clock.nowEpochMillis()
        val meta = ensureInitializedMetaLocked(now)

        val localSaved = meta.saved.values.toList()
        val mergedSavedEntries = BackupMerge.mergeSaved(localSaved, remote.saved)
        val activeSaved = mergedSavedEntries
            .filter { !it.deleted && it.track != null }
            .mapNotNull { it.track?.withoutLocalUri() }

        val localTags = meta.blockedTags.values.toList()
        val mergedTagEntries = BackupMerge.mergeBlockedTags(localTags, remote.blockedTags)
        val activeTags = mergedTagEntries.filter { !it.deleted }.map { it.tag }

        val localSort = LwwField(prefs.sort(), meta.sortUpdatedAtMs)
        val mergedSort = BackupMerge.mergeField(localSort, remote.sort)
        val finalSort = mergedSort?.value?.takeIf { it in SearchSyntax.SORTS } ?: prefs.sort()

        val localWindow = LwwField(prefs.window(), meta.windowUpdatedAtMs)
        val mergedWindow = BackupMerge.mergeField(localWindow, remote.window)
        val finalWindow = mergedWindow?.value?.takeIf { SearchSyntax.normalizeWindow(it) != null }
            ?: prefs.window()

        val prevSaved = prefs.saved()
        val prevTags = prefs.blockedTags()
        val prevSort = prefs.sort()
        val prevWindow = prefs.window()
        val prevHistoryTracks = historyStore.tracks()

        val combinedRemoteHistory = remote.history + extraHistory
        val mergedHistory = historyStore.mergeRemote(combinedRemoteHistory)

        val changed = activeSaved != prevSaved ||
            activeTags != prevTags ||
            finalSort != prevSort ||
            finalWindow != prevWindow ||
            mergedHistory != prevHistoryTracks

        if (changed) {
            prefs.saveSaved(activeSaved)
            prefs.saveBlockedTags(activeTags)
            prefs.saveFeed(finalSort, finalWindow)
        }

        val archiveChunkIds = adoptedArchives.mapTo(HashSet()) { it.chunkId }
        val nextMeta = meta.copy(
            saved = mergedSavedEntries.associateBy { it.id },
            blockedTags = mergedTagEntries.associateBy { it.tag },
            sortUpdatedAtMs = mergedSort?.updatedAtMs ?: meta.sortUpdatedAtMs,
            windowUpdatedAtMs = mergedWindow?.updatedAtMs ?: meta.windowUpdatedAtMs,
            pushedArchiveChunks = meta.pushedArchiveChunks + archiveChunkIds,
        )
        prefs.saveLocalSyncMetadata(nextMeta)

        RemoteMergeResult(
            localChanged = changed,
            appliedState = AppliedSyncState(
                saved = activeSaved,
                history = mergedHistory,
                blockedTags = activeTags,
                sort = finalSort,
                window = finalWindow,
                config = prefs.syncConfig(),
            ),
        )
    }

    private suspend fun buildLocalBackup(
        now: Long,
        inlineLimit: Int?,
        archiveRefs: List<HistoryArchiveRef> = emptyList(),
    ): DawngasmBackup {
        val meta = metaMutex.withLock { ensureInitializedMetaLocked(now) }
        val allHistory = historyStore.entries()
        val archivedChunkIds = archiveRefs.mapTo(HashSet()) { it.chunkId }
        val inlineHistory = if (inlineLimit != null && allHistory.size > inlineLimit && archiveRefs.isNotEmpty()) {
            val archivedIds = meta.archivedHistoryIds
            allHistory.filterIndexed { index, entry ->
                index < inlineLimit || entry.id !in archivedIds
            }
        } else {
            allHistory
        }

        return DawngasmBackup(
            schemaVersion = DawngasmBackup.CURRENT_SCHEMA_VERSION,
            exportedAtMs = now,
            saved = meta.saved.values.toList(),
            history = inlineHistory.map { it.copy(track = it.track.withoutLocalUri()) },
            historyArchives = archiveRefs.filter { it.chunkId in archivedChunkIds },
            blockedTags = meta.blockedTags.values.toList(),
            sort = LwwField(prefs.sort(), meta.sortUpdatedAtMs),
            window = LwwField(prefs.window(), meta.windowUpdatedAtMs),
        )
    }

    private fun ensureInitializedMetaLocked(now: Long): LocalSyncMetadata {
        val meta = prefs.localSyncMetadata()
        var updated = meta
        var changed = false

        val savedTracks = prefs.saved()
        if (meta.saved.isEmpty() && savedTracks.isNotEmpty()) {
            val map = LinkedHashMap<String, SyncedTrackEntry>(savedTracks.size)
            for (t in savedTracks) {
                map[t.id] = SyncedTrackEntry(
                    id = t.id,
                    track = t.withoutLocalUri(),
                    updatedAtMs = now,
                    deleted = false,
                )
            }
            updated = updated.copy(saved = map)
            changed = true
        }

        val blocked = prefs.blockedTags()
        if (meta.blockedTags.isEmpty() && blocked.isNotEmpty()) {
            val map = LinkedHashMap<String, SyncedTagEntry>(blocked.size)
            for (raw in blocked) {
                val norm = SearchSyntax.normalizeTag(raw) ?: continue
                map[norm] = SyncedTagEntry(tag = norm, updatedAtMs = now, deleted = false)
            }
            updated = updated.copy(blockedTags = map)
            changed = true
        }

        if (meta.sortUpdatedAtMs == 0L) {
            updated = updated.copy(sortUpdatedAtMs = now)
            changed = true
        }
        if (meta.windowUpdatedAtMs == 0L) {
            updated = updated.copy(windowUpdatedAtMs = now)
            changed = true
        }

        if (changed) {
            prefs.saveLocalSyncMetadata(updated)
        }
        return updated
    }

    companion object {
        const val KEY_VAULT_KEY = "sync_vault_key_v1"
        const val KEY_AUTH_TOKEN = "sync_auth_token_v1"

        private const val NORMAL_DEBOUNCE_MS = 3_000L
        private const val HISTORY_DEBOUNCE_MS = 30_000L
        private const val MAX_CONFLICT_RETRIES = 3
        internal const val INLINE_HISTORY_LIMIT = 500
        internal const val ARCHIVE_CHUNK_SIZE = 500

        fun defaultRemoteFactory(config: SyncConfig, authToken: String?): SyncRemote? {
            if (config.remoteUrl.isBlank()) return null
            return when (config.backend) {
                SyncBackendType.NONE -> null
                SyncBackendType.HTTP -> HttpSyncRemote(
                    url = config.remoteUrl,
                    authorizationHeader = authToken,
                )
                SyncBackendType.GITHUB_GIST -> GitHubGistSyncRemote(
                    gistIdOrUrl = config.remoteUrl,
                    pat = authToken.orEmpty(),
                    filename = config.gistFilename.ifBlank { SyncConfig.DEFAULT_GIST_FILENAME },
                )
                SyncBackendType.READ_ONLY_LINK -> ReadOnlyLinkSyncRemote(
                    url = config.remoteUrl,
                )
            }
        }
    }
}
