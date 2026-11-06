package dev.dawngasm.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import dev.dawngasm.app.BuildConfig
import dev.dawngasm.app.HotaudioClearanceActivity
import dev.dawngasm.app.model.SearchSyntax
import dev.dawngasm.app.model.TagSuggestion
import dev.dawngasm.app.util.compactScore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import dev.dawngasm.app.ui.theme.Dimens

private const val TAG_LOOKUP_DEBOUNCE_MS = 180L

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    blockedTags: List<String>,
    downloadFolderLabel: String,
    downloadTreeUri: String?,
    tagLookup: suspend (String) -> List<TagSuggestion>,
    hotaudioConnected: Boolean,
    onHotaudioClearanceChanged: () -> Unit,
    onClearHotaudioClearance: () -> Unit,
    onAddBlockedTag: (String) -> Unit,
    onRemoveBlockedTag: (String) -> Unit,
    onDownloadTreeChange: (String?) -> Unit,
    onClearDownloads: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val clearanceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) onHotaudioClearanceChanged()
    }
    var tagDraft by rememberSaveable { mutableStateOf("") }
    var tagSuggestions by remember { mutableStateOf(emptyList<TagSuggestion>()) }
    var tagSearching by remember { mutableStateOf(false) }

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

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            onDownloadTreeChange(uri.toString())
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings_screen"),
        contentPadding = PaddingValues(
            start = Dimens.ScreenHorizontalPadding,
            end = Dimens.ScreenHorizontalPadding,
            top = 24.dp,
            bottom = 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.testTag("settings_title"),
            )
        }

        item {
            SectionCard(
                icon = Icons.Outlined.Tag,
                title = "Blocked tags",
                subtitle = "Posts carrying these tags are hidden by the index.",
            ) {
                Column(
                    modifier = Modifier.animateContentSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (blockedTags.isEmpty()) {
                        Text(
                            text = "No blocked tags",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
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

                    OutlinedTextField(
                        value = tagDraft,
                        onValueChange = { tagDraft = it },
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
                                tagDraft.isNotEmpty() -> IconButton(
                                    onClick = {
                                        tagDraft = ""
                                        tagSuggestions = emptyList()
                                    },
                                ) {
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
                            onDone = { addBlockedTag(tagDraft) },
                        ),
                    )

                    AnimatedVisibility(
                        visible = tagSuggestions.isNotEmpty(),
                        enter = fadeIn(),
                        exit = fadeOut(),
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
                                            .clickable { addBlockedTag(suggestion.tag) }
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

        item {
            SectionCard(
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
                            onClick = { folderPicker.launch(null) },
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
                    }
                }
            }
        }

        item {
            SectionCard(
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

        item {
            SectionCard(
                icon = Icons.Outlined.Language,
                title = "hotaudio",
                subtitle = "hotaudio hides its audio behind a Cloudflare check. Open the site in the app, pass the check, and the clearance is saved for playback and downloads.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = if (hotaudioConnected) "Clearance saved" else "No clearance saved",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (hotaudioConnected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                clearanceLauncher.launch(
                                    Intent(context, HotaudioClearanceActivity::class.java),
                                )
                            },
                            shape = CircleShape,
                        ) {
                            Text(if (hotaudioConnected) "Check again" else "Open hotaudio check")
                        }
                        if (hotaudioConnected) {
                            TextButton(onClick = onClearHotaudioClearance) {
                                Text("Clear")
                            }
                        }
                    }
                }
            }
        }

        item {
            SectionCard(
                icon = Icons.Outlined.Info,
                title = "About",
                subtitle = "Version ${BuildConfig.VERSION_NAME}",
            ) {
                Text(
                    text = "A native Material 3 Expressive client for the Dawngasm audio index. " +
                        "Playback and downloads support soundgasm, audiochan, and whyp.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SectionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
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
