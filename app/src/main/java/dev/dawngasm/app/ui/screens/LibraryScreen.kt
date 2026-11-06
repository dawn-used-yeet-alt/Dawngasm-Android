package dev.dawngasm.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.History
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.dawngasm.app.model.Track
import dev.dawngasm.app.state.LibrarySubTab
import dev.dawngasm.app.ui.components.CardGroupPosition
import dev.dawngasm.app.ui.components.EmptyState
import dev.dawngasm.app.ui.components.PillTabs
import dev.dawngasm.app.ui.components.TrackCard
import dev.dawngasm.app.ui.theme.Dimens

@Composable
fun LibraryScreen(
    historyTracks: List<Track>,
    savedTracks: List<Track>,
    downloadedTracks: List<Track>,
    downloadedIds: Set<String>,
    downloadingIds: Set<String>,
    onTrackClick: (Track, List<Track>) -> Unit,
    onToggleDownload: (Track) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedSubTab by rememberSaveable { mutableStateOf(LibrarySubTab.History) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("library_screen"),
    ) {
        PillTabs(
            selectedTab = selectedSubTab,
            onTabSelected = { selectedSubTab = it },
            modifier = Modifier.padding(
                start = Dimens.ScreenHorizontalPadding,
                end = Dimens.ScreenHorizontalPadding,
                top = Dimens.ScreenHorizontalPadding,
            ),
        )

        AnimatedContent(
            targetState = selectedSubTab,
            transitionSpec = {
                (fadeIn(tween(200)) + scaleIn(
                    initialScale = 0.97f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                )) togetherWith (fadeOut(tween(120)) + scaleOut(targetScale = 1.01f))
            },
            label = "libraryTabTransition",
            modifier = Modifier.fillMaxSize(),
        ) { targetTab ->
            when (targetTab) {
                LibrarySubTab.History -> TrackList(
                    tracks = historyTracks,
                    downloadedIds = downloadedIds,
                    downloadingIds = downloadingIds,
                    emptyIcon = Icons.Outlined.History,
                    emptyTitle = "No listening history",
                    emptySubtitle = "Tracks you play will appear here.",
                    testTag = "history_track_list",
                    onTrackClick = { onTrackClick(it, historyTracks) },
                    onToggleDownload = onToggleDownload,
                )

                LibrarySubTab.Saved -> TrackList(
                    tracks = savedTracks,
                    downloadedIds = downloadedIds,
                    downloadingIds = downloadingIds,
                    emptyIcon = Icons.Outlined.FavoriteBorder,
                    emptyTitle = "No saved tracks yet",
                    emptySubtitle = "Heart a track on Home to keep it here.",
                    testTag = "saved_track_list",
                    onTrackClick = { onTrackClick(it, savedTracks) },
                    onToggleDownload = onToggleDownload,
                )

                LibrarySubTab.Downloads -> TrackList(
                    tracks = downloadedTracks,
                    downloadedIds = downloadedIds,
                    downloadingIds = downloadingIds,
                    emptyIcon = Icons.Outlined.Download,
                    emptyTitle = "No downloads yet",
                    emptySubtitle = "Tap download on any track to listen offline.",
                    testTag = "downloads_track_list",
                    onTrackClick = { onTrackClick(it, downloadedTracks) },
                    onToggleDownload = onToggleDownload,
                )
            }
        }
    }
}

@Composable
private fun TrackList(
    tracks: List<Track>,
    downloadedIds: Set<String>,
    downloadingIds: Set<String>,
    emptyIcon: androidx.compose.ui.graphics.vector.ImageVector,
    emptyTitle: String,
    emptySubtitle: String,
    testTag: String,
    onTrackClick: (Track) -> Unit,
    onToggleDownload: (Track) -> Unit,
) {
    if (tracks.isEmpty()) {
        EmptyState(
            icon = emptyIcon,
            title = emptyTitle,
            subtitle = emptySubtitle,
        )
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag(testTag),
        contentPadding = PaddingValues(
            start = Dimens.ScreenHorizontalPadding,
            end = Dimens.ScreenHorizontalPadding,
            top = Dimens.ScreenHorizontalPadding,
            bottom = 24.dp,
        ),
    ) {
        itemsIndexed(
            items = tracks,
            key = { _, track -> track.id },
        ) { index, track ->
            val position = groupPosition(index, tracks.size)
            TrackCard(
                track = track,
                isSaved = false,
                isDownloaded = track.id in downloadedIds,
                isDownloading = track.id in downloadingIds,
                onTrackClick = onTrackClick,
                onToggleSave = {},
                onToggleDownload = { onToggleDownload(track) },
                cardPosition = position,
                showSaveAction = false,
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
    }
}
