package dev.dawngasm.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
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

private enum class ActiveTagField {
    Include,
    Exclude,
}

@OptIn(ExperimentalMaterial3Api::class)
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
    var includeCsv by rememberSaveable { mutableStateOf(initialTags.joinToString(",")) }
    var excludeCsv by rememberSaveable { mutableStateOf(initialExcludeTags.joinToString(",")) }
    var includeDraft by rememberSaveable { mutableStateOf("") }
    var excludeDraft by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(defaultSort) }
    var window by rememberSaveable { mutableStateOf(defaultWindow) }

    var activeTagField by rememberSaveable { mutableStateOf<ActiveTagField?>(null) }
    var suggestions by remember { mutableStateOf(emptyList<TagSuggestion>()) }
    var searching by remember { mutableStateOf(false) }

    // Tags live in the query preview; each field is only a transient input, so
    // a chosen tag clears the box and leaves it ready for the next one.
    val includeTags = remember(includeCsv) { SearchSyntax.parseTagList(includeCsv) }
    val excludeTags = remember(excludeCsv) { SearchSyntax.parseTagList(excludeCsv) }

    fun addTag(csv: String, raw: String): String {
        val tag = SearchSyntax.normalizeTag(raw) ?: return csv
        return (SearchSyntax.parseTagList(csv) + tag)
            .distinctBy { it.lowercase(Locale.ROOT) }
            .joinToString(",")
    }

    val activeDraft = when (activeTagField) {
        ActiveTagField.Exclude -> excludeDraft
        ActiveTagField.Include -> includeDraft
        null -> ""
    }
    val activeSelected = when (activeTagField) {
        ActiveTagField.Exclude -> excludeTags
        ActiveTagField.Include -> includeTags
        null -> emptyList()
    }

    LaunchedEffect(activeDraft, activeSelected) {
        val prefix = SearchSyntax.normalizeTag(activeDraft)
        if (prefix.isNullOrEmpty()) {
            suggestions = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(TAG_LOOKUP_DEBOUNCE_MS)
        val selectedLower = activeSelected.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
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

    fun selectSuggestion(tag: String) {
        when (activeTagField) {
            ActiveTagField.Exclude -> {
                excludeCsv = addTag(excludeCsv, tag)
                excludeDraft = ""
            }
            ActiveTagField.Include -> {
                includeCsv = addTag(includeCsv, tag)
                includeDraft = ""
            }
            null -> {}
        }
        suggestions = emptyList()
    }

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
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(top = 16.dp, bottom = 20.dp),
        ) {
            AnimatedVisibility(
                visible = suggestions.isNotEmpty(),
                enter = expandVertically(
                    animationSpec = spring(
                        dampingRatio = 0.6f,
                        stiffness = 800f,
                    ),
                    expandFrom = Alignment.Top,
                ) + fadeIn(
                    animationSpec = tween(
                        durationMillis = 160,
                        easing = FastOutSlowInEasing,
                    ),
                ),
                exit = shrinkVertically(
                    animationSpec = spring(
                        dampingRatio = 0.7f,
                        stiffness = 900f,
                    ),
                    shrinkTowards = Alignment.Top,
                ) + fadeOut(
                    animationSpec = tween(
                        durationMillis = 100,
                    ),
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    suggestions.forEachIndexed { index, suggestion ->
                        key(suggestion.tag) {
                            ExpressiveSuggestionPill(
                                suggestion = suggestion,
                                index = index,
                                onClick = { selectSuggestion(suggestion.tag) },
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged {
                            if (it.isFocused) {
                                suggestions = emptyList()
                            }
                        }
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
                        label = "Block tags",
                        supportingText = "eg m4f, m4m",
                        draft = excludeDraft,
                        onDraftChange = {
                            excludeDraft = it
                            activeTagField = ActiveTagField.Exclude
                        },
                        onFocus = {
                            activeTagField = ActiveTagField.Exclude
                        },
                        onAddTag = {
                            excludeCsv = addTag(excludeCsv, it)
                            excludeDraft = ""
                            suggestions = emptyList()
                        },
                        searching = searching && activeTagField == ActiveTagField.Exclude,
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
                        label = "Search tags",
                        supportingText = "eg f4m, script fill",
                        draft = includeDraft,
                        onDraftChange = {
                            includeDraft = it
                            activeTagField = ActiveTagField.Include
                        },
                        onFocus = {
                            activeTagField = ActiveTagField.Include
                        },
                        onAddTag = {
                            includeCsv = addTag(includeCsv, it)
                            includeDraft = ""
                            suggestions = emptyList()
                        },
                        searching = searching && activeTagField == ActiveTagField.Include,
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
}

@Composable
private fun ExpressiveSuggestionPill(
    suggestion: TagSuggestion,
    index: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isAppeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val staggerDelay = (index * 28L).coerceAtMost(140L)
        if (staggerDelay > 0L) {
            delay(staggerDelay)
        }
        isAppeared = true
    }

    val enterScale by animateFloatAsState(
        targetValue = if (isAppeared) 1f else 0.65f,
        animationSpec = spring(
            dampingRatio = 0.58f,
            stiffness = 750f,
        ),
        label = "pillEnterScale",
    )

    val enterAlpha by animateFloatAsState(
        targetValue = if (isAppeared) 1f else 0f,
        animationSpec = tween(
            durationMillis = 150,
            easing = FastOutSlowInEasing,
        ),
        label = "pillEnterAlpha",
    )

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = 0.6f,
            stiffness = 900f,
        ),
        label = "pillPressScale",
    )

    val haptic = LocalHapticFeedback.current

    Surface(
        modifier = modifier
            .graphicsLayer {
                val totalScale = enterScale * pressScale
                scaleX = totalScale
                scaleY = totalScale
                alpha = enterAlpha
            }
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(),
                role = Role.Button,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                },
            )
            .testTag("tag_suggestion_pill_${suggestion.tag}"),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = suggestion.tag,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (suggestion.count > 0) {
                Text(
                    text = compactScore(suggestion.count),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TagPickerField(
    label: String,
    supportingText: String,
    draft: String,
    onDraftChange: (String) -> Unit,
    onAddTag: (String) -> Unit,
    searching: Boolean,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged {
                    if (it.isFocused) onFocus()
                }
                .testTag("tag_field_$label"),
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = {
                when {
                    searching -> CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    draft.isNotEmpty() -> IconButton(onClick = { onDraftChange("") }) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Clear $label",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            },
            supportingText = {
                Text(supportingText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (SearchSyntax.normalizeTag(draft) != null) {
                        onAddTag(draft)
                    }
                },
            ),
        )
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
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
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
