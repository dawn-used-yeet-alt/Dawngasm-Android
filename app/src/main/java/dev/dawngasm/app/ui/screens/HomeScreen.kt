package dev.dawngasm.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.dawngasm.app.model.Track
import dev.dawngasm.app.ui.components.CardGroupPosition
import dev.dawngasm.app.ui.components.EmptyState
import dev.dawngasm.app.ui.components.HomeFilterBar
import dev.dawngasm.app.ui.components.SearchRow
import dev.dawngasm.app.ui.components.TrackCard
import dev.dawngasm.app.ui.components.TrackSkeleton
import dev.dawngasm.app.ui.theme.Dimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    tracks: List<Track>,
    savedIds: Set<String>,
    downloadedIds: Set<String>,
    downloadingIds: Set<String>,
    query: String,
    sort: String,
    window: String,
    isLoading: Boolean,
    isRefreshing: Boolean,
    error: String?,
    hasMore: Boolean,
    onOpenSearch: () -> Unit,
    onClearSearch: () -> Unit,
    onSort: (String) -> Unit,
    onWindow: (String) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onTrackClick: (Track) -> Unit,
    onToggleSave: (Track) -> Unit,
    onToggleDownload: (Track) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("home_screen"),
    ) {
        SearchRow(
            query = query,
            onClick = onOpenSearch,
            onClear = onClearSearch,
            modifier = Modifier.padding(
                start = Dimens.ScreenHorizontalPadding,
                end = Dimens.ScreenHorizontalPadding,
                top = Dimens.SearchRowTopPadding,
            ),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = Dimens.ScreenHorizontalPadding,
                    end = Dimens.ScreenHorizontalPadding,
                    top = 8.dp,
                    bottom = 6.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HomeFilterBar(
                sort = sort,
                window = window,
                onSort = onSort,
                onWindow = onWindow,
                modifier = Modifier.weight(1f),
            )
        }

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("home_track_list"),
                contentPadding = PaddingValues(
                    start = Dimens.ScreenHorizontalPadding,
                    end = Dimens.ScreenHorizontalPadding,
                    top = 8.dp,
                    bottom = 24.dp,
                ),
            ) {
                if (error != null && tracks.isNotEmpty()) {
                    item(key = "error_banner") {
                        ErrorBanner(
                            message = error,
                            onRetry = onRefresh,
                            modifier = Modifier.padding(bottom = 10.dp),
                        )
                    }
                }

                if (isLoading && tracks.isEmpty()) {
                    items(8) { index ->
                        TrackSkeleton(
                            modifier = Modifier.animateItem(),
                            cardPosition = skeletonPosition(index, 8),
                        )
                        Spacer(
                            modifier = Modifier.height(
                                if (skeletonPosition(index, 8) == CardGroupPosition.Bottom) {
                                    Dimens.CardGroupGap
                                } else {
                                    Dimens.CardConnectedGap
                                },
                            ),
                        )
                    }
                } else if (tracks.isEmpty() && error != null) {
                    item(key = "error_empty") {
                        EmptyState(
                            icon = Icons.Outlined.CloudOff,
                            title = "Could not load the feed",
                            subtitle = error,
                        )
                    }
                    item(key = "retry") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Button(onClick = onRefresh) {
                                Text("Retry")
                            }
                        }
                    }
                } else if (tracks.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(
                            icon = Icons.Outlined.SearchOff,
                            title = "Nothing here yet",
                            subtitle = "Pull to refresh, widen the time window, or try another search.",
                        )
                    }
                } else {
                    itemsIndexed(
                        items = tracks,
                        key = { _, track -> track.id },
                    ) { index, track ->
                        val position = groupPosition(index, tracks.size)
                        TrackCard(
                            track = track,
                            isSaved = track.id in savedIds,
                            isDownloaded = track.id in downloadedIds,
                            isDownloading = track.id in downloadingIds,
                            onTrackClick = onTrackClick,
                            onToggleSave = { onToggleSave(track) },
                            onToggleDownload = { onToggleDownload(track) },
                            cardPosition = position,
                            showSaveAction = true,
                            modifier = Modifier.animateItem(),
                        )
                        Spacer(
                            modifier = Modifier.height(
                                if (position == CardGroupPosition.Bottom ||
                                    position == CardGroupPosition.Standalone
                                ) {
                                    Dimens.CardGroupGap
                                } else {
                                    Dimens.CardConnectedGap
                                },
                            ),
                        )
                    }

                    if (hasMore) {
                        item(key = "load_more") {
                            LaunchedEffect(tracks.size, hasMore) {
                                onLoadMore()
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 14.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.5.dp,
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
private fun ErrorBanner(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                textAlign = TextAlign.Start,
                maxLines = 2,
            )
            Button(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}

internal fun groupPosition(index: Int, total: Int): CardGroupPosition {
    if (total == 1) return CardGroupPosition.Standalone
    if (index == total - 1) return CardGroupPosition.Bottom
    return when (index % 4) {
        0 -> CardGroupPosition.Top
        3 -> CardGroupPosition.Bottom
        else -> CardGroupPosition.Middle
    }
}

private fun skeletonPosition(index: Int, total: Int): CardGroupPosition =
    groupPosition(index, total)
