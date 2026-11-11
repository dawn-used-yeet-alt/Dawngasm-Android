package dev.dawngasm.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.dawngasm.app.platform.DawngasmActions
import dev.dawngasm.app.platform.PlatformBackHandler
import dev.dawngasm.app.platform.SettingsPlatformActions
import dev.dawngasm.app.state.AppUiState
import dev.dawngasm.app.state.NavigationTab
import dev.dawngasm.app.state.PlaybackProgress
import dev.dawngasm.app.state.UiMessage
import dev.dawngasm.app.ui.components.AppNavBar
import dev.dawngasm.app.ui.components.MiniPlayer
import dev.dawngasm.app.ui.components.SearchFab
import dev.dawngasm.app.ui.components.SearchSheet
import dev.dawngasm.app.ui.screens.HomeScreen
import dev.dawngasm.app.ui.screens.LibraryScreen
import dev.dawngasm.app.ui.screens.SettingsScreen
import dev.dawngasm.app.ui.theme.Dimens
import kotlinx.coroutines.flow.Flow

@Composable
fun DawngasmApp(
    state: AppUiState,
    progress: PlaybackProgress,
    messages: Flow<UiMessage>,
    actions: DawngasmActions,
    settingsPlatform: SettingsPlatformActions,
    appVersion: String,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by rememberSaveable { mutableStateOf(NavigationTab.Home) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var isPlayerExpanded by rememberSaveable { mutableStateOf(false) }

    val savedIds = remember(state.saved) { state.saved.mapTo(HashSet()) { it.id } }
    val downloadedIds = remember(state.downloads) { state.downloads.mapTo(HashSet()) { it.id } }

    // Space the floating mini player occupies, so lists and the FAB can stay clear of it.
    val playerVisible = state.player.currentTrack != null
    LaunchedEffect(playerVisible) {
        if (!playerVisible) {
            isPlayerExpanded = false
        }
    }

    val bottomInset by animateDpAsState(
        targetValue = when {
            !playerVisible -> 0.dp
            isPlayerExpanded -> Dimens.MiniPlayerExpandedHeight + Dimens.MiniPlayerBottomMargin * 2
            else -> Dimens.MiniPlayerHeight + Dimens.MiniPlayerBottomMargin * 2
        },
        animationSpec = spring(
            dampingRatio = 0.6f,
            stiffness = 900f,
        ),
        label = "miniPlayerInset",
    )

    LaunchedEffect(messages) {
        messages.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message.text)
        }
    }

    PlatformBackHandler(enabled = isPlayerExpanded || selectedTab != NavigationTab.Home || state.canGoBack) {
        if (isPlayerExpanded) {
            isPlayerExpanded = false
        } else if (selectedTab != NavigationTab.Home) {
            selectedTab = NavigationTab.Home
        } else if (state.canGoBack) {
            actions.navigateBack()
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("app_scaffold"),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            AppNavBar(
                selectedTab = selectedTab,
                onTabSelect = { selectedTab = it },
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    (fadeIn(tween(220)) + scaleIn(
                        initialScale = 0.975f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    )) togetherWith (fadeOut(tween(130)) + scaleOut(targetScale = 1.01f))
                },
                label = "screenTabTransition",
                modifier = Modifier.fillMaxSize(),
            ) { tab ->
                when (tab) {
                    NavigationTab.Home -> HomeScreen(
                        tracks = state.tracks,
                        savedIds = savedIds,
                        downloadedIds = downloadedIds,
                        downloadingIds = state.downloadingIds,
                        query = state.searchSummary,
                        isLoading = state.isLoading,
                        isRefreshing = state.isRefreshing,
                        error = state.error,
                        hasMore = state.hasMore,
                        isSimilarMode = state.isSimilarMode,
                        pendingScrollRestore = state.pendingScrollRestore,
                        onScrollRestored = actions::onScrollRestored,
                        onSimilarClick = { track, scrollIndex, scrollOffset ->
                            actions.showSimilar(track, scrollIndex, scrollOffset)
                        },
                        onOpenSearch = { searchOpen = true },
                        onClearSearch = actions::clearSearch,
                        onRefresh = actions::refresh,
                        onLoadMore = actions::loadMore,
                        onTrackClick = { actions.play(it, state.tracks) },
                        onToggleSave = actions::toggleSave,
                        onToggleDownload = actions::download,
                        bottomInset = bottomInset,
                    )

                    NavigationTab.Library -> LibraryScreen(
                        historyTracks = state.history,
                        savedTracks = state.saved,
                        downloadedTracks = state.downloads,
                        downloadedIds = downloadedIds,
                        downloadingIds = state.downloadingIds,
                        onTrackClick = { track, list -> actions.play(track, list) },
                        onToggleDownload = actions::download,
                        onSimilarClick = { track ->
                            selectedTab = NavigationTab.Home
                            actions.showSimilar(track, 0, 0)
                        },
                        bottomInset = bottomInset,
                    )

                    NavigationTab.Settings -> SettingsScreen(
                        blockedTags = state.blockedTags,
                        downloadFolderLabel = state.downloadFolderLabel,
                        downloadTreeUri = state.downloadTreeUri,
                        tagLookup = actions::suggestTags,
                        hotaudioConnected = state.hotaudioConnected,
                        audiochanConnected = state.audiochanConnected,
                        onClearHotaudioClearance = actions::clearHotaudioClearance,
                        onClearAudiochanClearance = actions::clearAudiochanClearance,
                        onAddBlockedTag = actions::addBlockedTag,
                        onRemoveBlockedTag = actions::removeBlockedTag,
                        onDownloadTreeChange = actions::setDownloadTree,
                        onClearDownloads = actions::clearDownloads,
                        platform = settingsPlatform,
                        appVersion = appVersion,
                    )
                }
            }

            // Floats over the content, so the area around the pill is transparent.
            MiniPlayer(
                player = state.player,
                progress = progress,
                isSaved = state.player.currentTrack?.let { it.id in savedIds } == true,
                isExpanded = isPlayerExpanded,
                onExpandedChange = { isPlayerExpanded = it },
                onTogglePlayPause = actions::togglePlayPause,
                onToggleSave = {
                    state.player.currentTrack?.let(actions::toggleSave)
                },
                onNextTrack = actions::nextTrack,
                onDismiss = actions::dismissPlayer,
                onSeek = actions::seekTo,
                modifier = Modifier.align(Alignment.BottomCenter),
            )

            if (selectedTab == NavigationTab.Home && !searchOpen) {
                SearchFab(
                    onClick = { searchOpen = true },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 16.dp + bottomInset),
                )
            }
        }
    }

    if (searchOpen) {
        SearchSheet(
            initialQuery = state.query,
            initialTags = state.includeTags,
            initialExcludeTags = state.excludeTags,
            defaultSort = state.sort,
            defaultWindow = state.window,
            tagLookup = actions::suggestTags,
            onDismiss = { searchOpen = false },
            onApply = { request ->
                actions.applySearch(request)
                searchOpen = false
            },
        )
    }
}
