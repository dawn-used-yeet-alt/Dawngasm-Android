package dev.dawngasm.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowOverflow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.dawngasm.app.data.ImportMode
import dev.dawngasm.app.data.SyncBackendType
import dev.dawngasm.app.data.SyncConfig
import dev.dawngasm.app.data.SyncStatus
import dev.dawngasm.app.model.SearchSyntax
import dev.dawngasm.app.model.TagSuggestion
import dev.dawngasm.app.platform.SettingsPlatformActions
import dev.dawngasm.app.ui.theme.Dimens
import dev.dawngasm.app.util.Clock
import dev.dawngasm.app.util.compactScore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG_LOOKUP_DEBOUNCE_MS = 180L

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    blockedTags: List<String>,
    downloadFolderLabel: String,
    downloadTreeUri: String?,
    tagLookup: suspend (String) -> List<TagSuggestion>,
    hotaudioConnected: Boolean,
    audiochanConnected: Boolean,
    mediaSessionServiceEnabled: Boolean = true,
    syncStatus: SyncStatus = SyncStatus(),
    onToggleMediaSessionService: (Boolean) -> Unit = {},
    onClearHotaudioClearance: () -> Unit,
    onClearAudiochanClearance: () -> Unit,
    onAddBlockedTag: (String) -> Unit,
    onRemoveBlockedTag: (String) -> Unit,
    onDownloadTreeChange: (String?) -> Unit,
    onClearDownloads: () -> Unit,
    onExportBackup: suspend (String) -> Result<String> = { Result.success("") },
    onImportBackup: suspend (String, String, ImportMode) -> Result<Unit> = { _, _, _ -> Result.success(Unit) },
    onConfigureSync: suspend (SyncBackendType, String, String, String?, String) -> Result<SyncStatus> = { _, _, _, _, _ ->
        Result.success(SyncStatus())
    },
    onUnlockSync: suspend (String) -> Result<SyncStatus> = { Result.success(SyncStatus()) },
    onSyncNow: () -> Unit = {},
    onDisableSync: () -> Unit = {},
    onNotifyMessage: (String, Boolean) -> Unit = { _, _ -> },
    platform: SettingsPlatformActions,
    appVersion: String,
    modifier: Modifier = Modifier,
    wideLayout: Boolean = false,
    showTitle: Boolean = true,
    horizontalPadding: Dp = Dimens.ScreenHorizontalPadding,
) {
    val clearanceDetail = platform.hotaudioClearanceDetail()
    var tagDraft by rememberSaveable { mutableStateOf("") }
    var tagSuggestions by remember { mutableStateOf(emptyList<TagSuggestion>()) }
    var tagSearching by remember { mutableStateOf(false) }
    var blockedTagsExpanded by rememberSaveable { mutableStateOf(false) }

    fun addBlockedTag(raw: String) {
        val tag = SearchSyntax.normalizeTag(raw) ?: return
        if (blockedTags.none { it.equals(tag, ignoreCase = true) }) onAddBlockedTag(tag)
        tagDraft = ""
        tagSuggestions = emptyList()
    }

    LaunchedEffect(tagDraft, blockedTags) {
        val prefix = SearchSyntax.normalizeTag(tagDraft)
        if (prefix.isNullOrEmpty()) {
            tagSuggestions = emptyList()
            tagSearching = false
            return@LaunchedEffect
        }
        tagSearching = true
        delay(TAG_LOOKUP_DEBOUNCE_MS)
        val blockedLower = blockedTags.mapTo(HashSet()) { it.lowercase() }
        val result = try {
            tagLookup(prefix)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
        tagSuggestions = result
            .filter { it.tag.lowercase() !in blockedLower }
            .distinctBy { it.tag.lowercase() }
            .take(4)
        tagSearching = false
    }

    val sections: List<@Composable (Modifier) -> Unit> = listOf(
        { sectionModifier ->
            BlockedTagsSection(
                modifier = sectionModifier,
                blockedTags = blockedTags,
                blockedTagsExpanded = blockedTagsExpanded,
                onExpandedChange = { blockedTagsExpanded = it },
                tagDraft = tagDraft,
                onTagDraftChange = { tagDraft = it },
                tagSuggestions = tagSuggestions,
                tagSearching = tagSearching,
                onClearDraft = {
                    tagDraft = ""
                    tagSuggestions = emptyList()
                },
                onAddBlockedTag = { raw -> addBlockedTag(raw) },
                onRemoveBlockedTag = onRemoveBlockedTag,
            )
        },
        { sectionModifier ->
            DownloadLocationSection(
                modifier = sectionModifier,
                downloadFolderLabel = downloadFolderLabel,
                downloadTreeUri = downloadTreeUri,
                platform = platform,
                onDownloadTreeChange = onDownloadTreeChange,
            )
        },
        { sectionModifier ->
            DownloadedEntriesSection(
                modifier = sectionModifier,
                onClearDownloads = onClearDownloads,
            )
        },
        { sectionModifier ->
            MediaSessionSection(
                modifier = sectionModifier,
                enabled = mediaSessionServiceEnabled,
                onToggle = onToggleMediaSessionService,
            )
        },
        { sectionModifier ->
            HotaudioSection(
                modifier = sectionModifier,
                connected = hotaudioConnected,
                clearanceDetail = clearanceDetail,
                platform = platform,
                onClearClearance = onClearHotaudioClearance,
            )
        },
        { sectionModifier ->
            AudiochanSection(
                modifier = sectionModifier,
                connected = audiochanConnected,
                platform = platform,
                onClearClearance = onClearAudiochanClearance,
            )
        },
        { sectionModifier ->
            EncryptedBackupSection(
                modifier = sectionModifier,
                platform = platform,
                onExportBackup = onExportBackup,
                onImportBackup = onImportBackup,
                onNotifyMessage = onNotifyMessage,
            )
        },
        { sectionModifier ->
            CrossDeviceSyncSection(
                modifier = sectionModifier,
                syncStatus = syncStatus,
                onConfigureSync = onConfigureSync,
                onUnlockSync = onUnlockSync,
                onSyncNow = onSyncNow,
                onDisableSync = onDisableSync,
                onNotifyMessage = onNotifyMessage,
            )
        },
        { sectionModifier ->
            AboutSection(
                modifier = sectionModifier,
                appVersion = appVersion,
            )
        },
    )

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings_screen"),
        contentPadding = PaddingValues(
            start = horizontalPadding,
            end = horizontalPadding,
            top = if (wideLayout) 20.dp else 24.dp,
            bottom = 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(
            if (wideLayout) Dimens.DesktopSectionGap else 14.dp,
        ),
    ) {
        if (showTitle) {
            item(key = "settings_title") {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.testTag("settings_title"),
                )
            }
        }

        if (wideLayout) {
            // The tag library is the primary control, so it keeps the full
            // width. Related pairs share a row below it.
            item(key = "settings_blocked_tags") {
                sections[0](Modifier.fillMaxWidth())
            }
            itemsIndexed(
                items = sections.drop(1).chunked(2),
                key = { index, _ -> "settings_row_$index" },
            ) { _, row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.DesktopSectionGap),
                    verticalAlignment = Alignment.Top,
                ) {
                    row.forEach { section ->
                        section(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                    if (row.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        } else {
            itemsIndexed(
                items = sections,
                key = { index, _ -> "settings_section_$index" },
            ) { _, section ->
                section(Modifier.fillMaxWidth())
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockedTagsSection(
    blockedTags: List<String>,
    blockedTagsExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    tagDraft: String,
    onTagDraftChange: (String) -> Unit,
    tagSuggestions: List<TagSuggestion>,
    tagSearching: Boolean,
    onClearDraft: () -> Unit,
    onAddBlockedTag: (String) -> Unit,
    onRemoveBlockedTag: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        modifier = modifier,
        icon = Icons.Outlined.Tag,
        title = "Blocked tags",
        subtitle = "Posts carrying these tags are hidden by the index.",
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (blockedTags.isEmpty()) {
                Text(
                    text = "No blocked tags",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize(
                                animationSpec = spring(
                                    dampingRatio = 0.6f,
                                    stiffness = 800f,
                                ),
                            ),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        maxLines = if (blockedTagsExpanded) Int.MAX_VALUE else 4,
                        overflow = FlowRowOverflow.expandOrCollapseIndicator(
                            minRowsToShowCollapse = 4,
                            expandIndicator = {
                                AssistChip(
                                    onClick = { onExpandedChange(true) },
                                    label = { Text("Show more") },
                                    trailingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.ExpandMore,
                                            contentDescription = "Show more blocked tags",
                                            modifier = Modifier.size(16.dp),
                                        )
                                    },
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .testTag("blocked_tags_expand"),
                                )
                            },
                            collapseIndicator = {
                                AssistChip(
                                    onClick = { onExpandedChange(false) },
                                    label = { Text("Show less") },
                                    trailingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.ExpandLess,
                                            contentDescription = "Show fewer blocked tags",
                                            modifier = Modifier.size(16.dp),
                                        )
                                    },
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .testTag("blocked_tags_collapse"),
                                )
                            },
                        ),
                    ) {
                        blockedTags.forEach { tag ->
                            InputChip(
                                selected = true,
                                onClick = { onRemoveBlockedTag(tag) },
                                label = { Text(tag) },
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Close,
                                        contentDescription = "Remove $tag",
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = tagDraft,
                onValueChange = onTagDraftChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("blocked_tag_field"),
                singleLine = true,
                shape = CircleShape,
                placeholder = { Text("Type a tag, e.g. incest") },
                trailingIcon = {
                    when {
                        tagSearching -> CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        tagDraft.isNotEmpty() -> IconButton(onClick = onClearDraft) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "Clear tag",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { onAddBlockedTag(tagDraft) },
                ),
            )

            AnimatedVisibility(
                visible = tagSuggestions.isNotEmpty(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Column {
                        tagSuggestions.forEach { suggestion ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onAddBlockedTag(suggestion.tag) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = suggestion.tag,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = compactScore(suggestion.count),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.size(8.dp))
                                Icon(
                                    imageVector = Icons.Outlined.Add,
                                    contentDescription = "Block ${suggestion.tag}",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadLocationSection(
    downloadFolderLabel: String,
    downloadTreeUri: String?,
    platform: SettingsPlatformActions,
    onDownloadTreeChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        modifier = modifier,
        icon = Icons.Outlined.FolderOpen,
        title = "Download location",
        subtitle = "Downloads are written here with the system document picker.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = downloadFolderLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 10.dp),
                        maxLines = 2,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { platform.chooseDownloadFolder() },
                    shape = CircleShape,
                ) {
                    Text("Choose folder")
                }
                if (downloadTreeUri != null) {
                    TextButton(
                        onClick = { onDownloadTreeChange(null) },
                        shape = CircleShape,
                    ) {
                        Text("Use app storage")
                    }
                }
                if (platform.canOpenDownloadFolder()) {
                    TextButton(
                        onClick = { platform.openDownloadFolder() },
                        shape = CircleShape,
                    ) {
                        Text("Open folder")
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadedEntriesSection(
    onClearDownloads: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        modifier = modifier,
        icon = Icons.Outlined.DeleteOutline,
        title = "Downloaded entries",
        subtitle = "Remove the library entries. Files already on disk are left alone.",
    ) {
        OutlinedButton(
            onClick = onClearDownloads,
            shape = CircleShape,
        ) {
            Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
            Spacer(modifier = Modifier.size(8.dp))
            Text("Remove downloaded entries")
        }
    }
}

@Composable
private fun MediaSessionSection(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        modifier = modifier,
        icon = Icons.Outlined.Radio,
        title = "Media session service",
        subtitle = "Keep audio playing in the background with lock screen controls and system notifications.",
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (enabled) "Enabled" else "Disabled",
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.testTag("media_session_service_switch"),
            )
        }
    }
}

@Composable
private fun HotaudioSection(
    connected: Boolean,
    clearanceDetail: String?,
    platform: SettingsPlatformActions,
    onClearClearance: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        modifier = modifier,
        icon = Icons.Outlined.Language,
        title = "hotaudio",
        subtitle = "hotaudio hides its audio behind a Cloudflare check. Open the site in the app, pass the check, and the clearance is saved for playback and downloads.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val clearanceStatus = if (connected) {
                buildString {
                    append("Clearance saved")
                    if (!clearanceDetail.isNullOrBlank()) {
                        append(" (")
                        append(clearanceDetail)
                        append(")")
                    }
                }
            } else {
                "No clearance saved"
            }
            Text(
                text = clearanceStatus,
                style = MaterialTheme.typography.bodyMedium,
                color = if (connected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { platform.openHotaudioClearance() },
                    shape = CircleShape,
                ) {
                    Text(if (connected) "Check again" else "Open hotaudio check")
                }
                if (connected) {
                    TextButton(onClick = onClearClearance) {
                        Text("Clear")
                    }
                }
            }
        }
    }
}

@Composable
private fun AudiochanSection(
    connected: Boolean,
    platform: SettingsPlatformActions,
    onClearClearance: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        modifier = modifier,
        icon = Icons.Outlined.Language,
        title = "audiochan",
        subtitle = "audiochan protects audio streams with Cloudflare Turnstile. Open the check in the app, pass verification, and the clearance is saved for playback and downloads.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val clearanceStatus = if (connected) {
                "Clearance saved"
            } else {
                "No clearance saved"
            }
            Text(
                text = clearanceStatus,
                style = MaterialTheme.typography.bodyMedium,
                color = if (connected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { platform.openAudiochanClearance() },
                    shape = CircleShape,
                ) {
                    Text(if (connected) "Check again" else "Open audiochan check")
                }
                if (connected) {
                    TextButton(onClick = onClearClearance) {
                        Text("Clear")
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutSection(
    appVersion: String,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        modifier = modifier,
        icon = Icons.Outlined.Info,
        title = "About",
        subtitle = "Version $appVersion",
    ) {
        Text(
            text = "A native Material 3 Expressive client for the Dawngasm audio index. " +
                "Playback and downloads support soundgasm, audiochan, and whyp.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EncryptedBackupSection(
    platform: SettingsPlatformActions,
    onExportBackup: suspend (String) -> Result<String>,
    onImportBackup: suspend (String, String, ImportMode) -> Result<Unit>,
    onNotifyMessage: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current

    var exportPassword by rememberSaveable { mutableStateOf("") }
    var importSource by rememberSaveable { mutableStateOf("") }
    var importPassword by rememberSaveable { mutableStateOf("") }
    var importMode by rememberSaveable { mutableStateOf(ImportMode.MERGE) }
    var working by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf<String?>(null) }

    SectionCard(
        modifier = modifier,
        icon = Icons.Outlined.Lock,
        title = "Encrypted backup",
        subtitle = "Export or import saved tracks, full listening history, blocked tags, and feed filters as encrypted ASCII text.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "Export",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            OutlinedTextField(
                value = exportPassword,
                onValueChange = { exportPassword = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("backup_export_password"),
                singleLine = true,
                shape = CircleShape,
                placeholder = { Text("Backup password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    enabled = !working && exportPassword.isNotEmpty(),
                    onClick = {
                        scope.launch {
                            working = true
                            statusText = null
                            val encrypted = onExportBackup(exportPassword)
                            encrypted.onSuccess { text ->
                                val suggested = "dawngasm-backup-${Clock.nowEpochMillis()}.txt"
                                platform.saveBackupFile(suggested, text) { saveResult ->
                                    saveResult.onSuccess { msg ->
                                        statusText = msg
                                        onNotifyMessage(msg, false)
                                    }.onFailure { err ->
                                        val msg = err.message ?: "Failed to save backup file"
                                        statusText = msg
                                        onNotifyMessage(msg, true)
                                    }
                                }
                            }.onFailure { err ->
                                val msg = err.message ?: "Export failed"
                                statusText = msg
                                onNotifyMessage(msg, true)
                            }
                            working = false
                        }
                    },
                    shape = CircleShape,
                ) {
                    Text("Save file")
                }
                OutlinedButton(
                    enabled = !working && exportPassword.isNotEmpty(),
                    onClick = {
                        scope.launch {
                            working = true
                            statusText = null
                            onExportBackup(exportPassword)
                                .onSuccess { text ->
                                    clipboard.setText(AnnotatedString(text))
                                    statusText = "Encrypted backup copied to clipboard"
                                    onNotifyMessage("Encrypted backup copied", false)
                                }
                                .onFailure { err ->
                                    val msg = err.message ?: "Export failed"
                                    statusText = msg
                                    onNotifyMessage(msg, true)
                                }
                            working = false
                        }
                    },
                    shape = CircleShape,
                ) {
                    Text("Copy text")
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

            Text(
                text = "Import",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            OutlinedTextField(
                value = importSource,
                onValueChange = { importSource = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("backup_import_source"),
                maxLines = 3,
                shape = MaterialTheme.shapes.large,
                placeholder = { Text("Paste DAWNGASM-ENC-V1:... text, HTTPS/Gist link, or file path") },
            )
            OutlinedTextField(
                value = importPassword,
                onValueChange = { importPassword = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("backup_import_password"),
                singleLine = true,
                shape = CircleShape,
                placeholder = { Text("Decryption password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                    InputChip(
                        selected = importMode == ImportMode.MERGE,
                        onClick = { importMode = ImportMode.MERGE },
                        label = { Text("Merge") },
                    )
                    InputChip(
                        selected = importMode == ImportMode.REPLACE,
                        onClick = { importMode = ImportMode.REPLACE },
                        label = { Text("Replace") },
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    enabled = !working && importSource.isNotBlank() && importPassword.isNotEmpty(),
                    onClick = {
                        scope.launch {
                            working = true
                            statusText = null
                            onImportBackup(importSource, importPassword, importMode)
                                .onSuccess {
                                    statusText = "Backup imported (${importMode.name.lowercase()})"
                                    importSource = ""
                                    importPassword = ""
                                }
                                .onFailure { err ->
                                    val msg = err.message ?: "Import failed"
                                    statusText = msg
                                    onNotifyMessage(msg, true)
                                }
                            working = false
                        }
                    },
                    shape = CircleShape,
                ) {
                    Text("Import")
                }
                if (platform.canPickBackupFiles()) {
                    OutlinedButton(
                        enabled = !working,
                        onClick = {
                            platform.pickBackupFile { result ->
                                result.onSuccess { content ->
                                    importSource = content
                                    statusText = "Backup file loaded — enter password to import"
                                }.onFailure { err ->
                                    val msg = err.message ?: "Could not read file"
                                    statusText = msg
                                    onNotifyMessage(msg, true)
                                }
                            }
                        },
                        shape = CircleShape,
                    ) {
                        Text("Choose file")
                    }
                }
            }

            if (working) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            }
            statusText?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CrossDeviceSyncSection(
    syncStatus: SyncStatus,
    onConfigureSync: suspend (SyncBackendType, String, String, String?, String) -> Result<SyncStatus>,
    onUnlockSync: suspend (String) -> Result<SyncStatus>,
    onSyncNow: () -> Unit,
    onDisableSync: () -> Unit,
    onNotifyMessage: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val config = syncStatus.config

    var selectedBackend by rememberSaveable(config.backend) {
        mutableStateOf(if (config.backend == SyncBackendType.NONE) SyncBackendType.GITHUB_GIST else config.backend)
    }
    var remoteUrl by rememberSaveable(config.remoteUrl) { mutableStateOf(config.remoteUrl) }
    var gistFilename by rememberSaveable(config.gistFilename) {
        mutableStateOf(config.gistFilename.ifBlank { SyncConfig.DEFAULT_GIST_FILENAME })
    }
    var authToken by rememberSaveable { mutableStateOf("") }
    var syncPassword by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    SectionCard(
        modifier = modifier,
        icon = Icons.Outlined.Hub,
        title = "Cross-device sync",
        subtitle = "End-to-end encrypted conflict-free sync over HTTP/WebDAV, GitHub Gist, or a read-only link. Credentials stay in platform secure storage.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val statusSummary = when {
                syncStatus.isSyncing -> "Syncing..."
                syncStatus.needsUnlock -> "Sync locked — enter password to unlock"
                config.lastSyncError != null -> "Error: ${config.lastSyncError}"
                config.enabled -> "Enabled (${config.backend.label})"
                else -> "Off"
            }
            Text(
                text = statusSummary,
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    config.lastSyncError != null || syncStatus.needsUnlock -> MaterialTheme.colorScheme.error
                    config.enabled -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                        SyncBackendType.GITHUB_GIST,
                        SyncBackendType.HTTP,
                        SyncBackendType.READ_ONLY_LINK,
                    ).forEach { backend ->
                        InputChip(
                            selected = selectedBackend == backend,
                            onClick = { selectedBackend = backend },
                            label = { Text(backend.label) },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = remoteUrl,
                onValueChange = { remoteUrl = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sync_remote_url"),
                singleLine = true,
                shape = CircleShape,
                placeholder = {
                    Text(
                        when (selectedBackend) {
                            SyncBackendType.GITHUB_GIST -> "GitHub Gist URL or ID"
                            SyncBackendType.HTTP -> "https://server.example/dawngasm.txt"
                            SyncBackendType.READ_ONLY_LINK -> "https://... or GitHub Gist/blob URL"
                            SyncBackendType.NONE -> ""
                        },
                    )
                },
            )

            if (selectedBackend == SyncBackendType.GITHUB_GIST) {
                OutlinedTextField(
                    value = gistFilename,
                    onValueChange = { gistFilename = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = CircleShape,
                    placeholder = { Text("Gist filename (${SyncConfig.DEFAULT_GIST_FILENAME})") },
                )
            }

            if (selectedBackend != SyncBackendType.READ_ONLY_LINK) {
                OutlinedTextField(
                    value = authToken,
                    onValueChange = { authToken = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = CircleShape,
                    placeholder = {
                        Text(
                            if (selectedBackend == SyncBackendType.GITHUB_GIST) {
                                "GitHub PAT (gist scope)"
                            } else {
                                "Optional Authorization header (Bearer ...)"
                            },
                        )
                    },
                    visualTransformation = PasswordVisualTransformation(),
                )
            }

            OutlinedTextField(
                value = syncPassword,
                onValueChange = { syncPassword = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sync_password"),
                singleLine = true,
                shape = CircleShape,
                placeholder = { Text("Encryption password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    enabled = !busy && !syncStatus.isSyncing && remoteUrl.isNotBlank() && syncPassword.isNotEmpty(),
                    onClick = {
                        scope.launch {
                            busy = true
                            onConfigureSync(
                                selectedBackend,
                                remoteUrl,
                                syncPassword,
                                authToken.takeIf { it.isNotBlank() },
                                gistFilename,
                            ).onSuccess {
                                syncPassword = ""
                                authToken = ""
                            }.onFailure { err ->
                                onNotifyMessage(err.message ?: "Could not configure sync", true)
                            }
                            busy = false
                        }
                    },
                    shape = CircleShape,
                ) {
                    Text(if (config.enabled) "Update & Sync" else "Enable Sync")
                }

                if (config.enabled) {
                    if (syncStatus.needsUnlock) {
                        OutlinedButton(
                            enabled = !busy && syncPassword.isNotEmpty(),
                            onClick = {
                                scope.launch {
                                    busy = true
                                    onUnlockSync(syncPassword).onSuccess {
                                        syncPassword = ""
                                    }.onFailure { err ->
                                        onNotifyMessage(err.message ?: "Unlock failed", true)
                                    }
                                    busy = false
                                }
                            },
                            shape = CircleShape,
                        ) {
                            Text("Unlock")
                        }
                    } else {
                        OutlinedButton(
                            enabled = !busy && !syncStatus.isSyncing,
                            onClick = onSyncNow,
                            shape = CircleShape,
                        ) {
                            Text("Sync now")
                        }
                    }

                    TextButton(
                        enabled = !busy && !syncStatus.isSyncing,
                        onClick = onDisableSync,
                        shape = CircleShape,
                    ) {
                        Text("Disable")
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(9.dp)
                            .size(20.dp),
                    )
                }
                Spacer(modifier = Modifier.size(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            Spacer(modifier = Modifier.height(14.dp))
            content()
        }
    }
}
