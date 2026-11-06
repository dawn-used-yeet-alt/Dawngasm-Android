package dev.dawngasm.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dawngasm.app.model.SearchRequest
import dev.dawngasm.app.model.SearchSyntax
import dev.dawngasm.app.model.TagSuggestion
import dev.dawngasm.app.util.compactScore
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private const val MAX_TAG_SUGGESTIONS = 6
private const val TAG_LOOKUP_DEBOUNCE_MS = 180L

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchSheet(
    initialQuery: String,
    initialTags: List<String>,
    initialExcludeTags: List<String>,
    defaultSort: String,
    defaultWindow: String,
    tagLookup: suspend (String) -> List<TagSuggestion>,
    onDismiss: () -> Unit,
    onApply: (SearchRequest) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val keyboard = LocalSoftwareKeyboardController.current
    var text by rememberSaveable { mutableStateOf(initialQuery) }
    var includeTags by remember { mutableStateOf(initialTags.toList()) }
    var excludeTags by remember { mutableStateOf(initialExcludeTags.toList()) }
    var sort by rememberSaveable { mutableStateOf(defaultSort) }
    var window by rememberSaveable { mutableStateOf(defaultWindow) }

    val preview = SearchRequest(text, includeTags, excludeTags, sort, window).summary()

    fun apply() {
        keyboard?.hide()
        onApply(
            SearchRequest(
                text = text.trim(),
                includeTags = includeTags.distinct(),
                excludeTags = excludeTags.distinct(),
                sort = sort,
                window = window,
            ),
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { androidx.compose.material3.BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Search",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("search_field"),
                singleLine = true,
                shape = CircleShape,
                placeholder = { Text("Text to Search") },
                leadingIcon = {
                    Icon(Icons.Rounded.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (text.isNotEmpty()) {
                        IconButton(onClick = { text = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear text")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { apply() }),
            )

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TagPickerField(
                    label = "Tags to block",
                    supportingText = "eg m4f",
                    icon = Icons.Rounded.RemoveCircleOutline,
                    selected = excludeTags,
                    onSelectedChange = { excludeTags = it },
                    tagLookup = tagLookup,
                    modifier = Modifier.weight(1f),
                )
                DropdownField(
                    label = "Sort",
                    value = SearchSyntax.sortLabel(sort),
                    icon = Icons.AutoMirrored.Rounded.Sort,
                    options = SearchSyntax.SORTS.map { it to SearchSyntax.sortLabel(it) },
                    onSelect = { sort = it },
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TagPickerField(
                    label = "Tags to search",
                    supportingText = "must match exactly",
                    icon = Icons.Rounded.LocalOffer,
                    selected = includeTags,
                    onSelectedChange = { includeTags = it },
                    tagLookup = tagLookup,
                    modifier = Modifier.weight(1f),
                )
                DropdownField(
                    label = "Window",
                    value = SearchSyntax.windowLabel(window),
                    icon = Icons.Rounded.Schedule,
                    options = WINDOW_OPTIONS,
                    onSelect = { window = it },
                    modifier = Modifier.weight(1f),
                )
            }

            AnimatedVisibility(
                visible = preview.isNotEmpty(),
                enter = fadeIn() + scaleIn(
                    initialScale = 0.97f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                ),
                exit = fadeOut() + scaleOut(targetScale = 0.98f),
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = preview,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Button(
                onClick = { apply() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("search_submit"),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Icon(Icons.Rounded.Search, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Search", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TagPickerField(
    label: String,
    supportingText: String,
    icon: ImageVector,
    selected: List<String>,
    onSelectedChange: (List<String>) -> Unit,
    tagLookup: suspend (String) -> List<TagSuggestion>,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var suggestions by remember { mutableStateOf(emptyList<TagSuggestion>()) }
    var searching by remember { mutableStateOf(false) }

    fun addTag(raw: String) {
        val tag = SearchSyntax.normalizeTag(raw) ?: return
        if (selected.none { it.equals(tag, ignoreCase = true) }) {
            onSelectedChange(selected + tag)
        }
        query = ""
        suggestions = emptyList()
    }

    LaunchedEffect(query, selected) {
        val prefix = SearchSyntax.normalizeTag(query)
        if (prefix.isNullOrEmpty()) {
            suggestions = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(TAG_LOOKUP_DEBOUNCE_MS)
        val selectedLower = selected.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
        val result = try {
            tagLookup(prefix)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
        suggestions = result
            .filter { it.tag.lowercase(Locale.ROOT) !in selectedLower }
            .distinctBy { it.tag.lowercase(Locale.ROOT) }
            .take(MAX_TAG_SUGGESTIONS)
        searching = false
    }

    Column(modifier = modifier) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("tag_field_$label"),
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            },
            trailingIcon = {
                when {
                    searching -> CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    query.isNotEmpty() -> IconButton(onClick = { query = "" }) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Clear $label",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            },
            supportingText = { Text(supportingText) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { addTag(query) }),
        )

        AnimatedVisibility(
            visible = suggestions.isNotEmpty(),
            enter = fadeIn() + scaleIn(
                initialScale = 0.97f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ),
            exit = fadeOut() + scaleOut(targetScale = 0.98f),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column {
                    suggestions.forEach { suggestion ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { addTag(suggestion.tag) }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
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
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = "Add ${suggestion.tag}",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }

        if (selected.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                selected.forEach { tag ->
                    InputChip(
                        selected = true,
                        onClick = { onSelectedChange(selected - tag) },
                        label = {
                            Text(
                                text = tag,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Remove $tag",
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownField(
    label: String,
    value: String,
    icon: ImageVector,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { (key, title) ->
                DropdownMenuItem(
                    text = { Text(title) },
                    onClick = {
                        expanded = false
                        onSelect(key)
                    },
                )
            }
        }
    }
}

private val WINDOW_OPTIONS = listOf(
    "day" to "Past day",
    "week" to "Past week",
    "month" to "Past month",
    "year" to "Past year",
    "all" to "All time",
    "14d" to "Last 14 days",
    "30d" to "Last 30 days",
    "90d" to "Last 90 days",
)
