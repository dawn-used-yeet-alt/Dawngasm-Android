package dev.dawngasm.app

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.dawngasm.app.model.AudioHost
import dev.dawngasm.app.model.Track
import dev.dawngasm.app.state.NavigationTab
import dev.dawngasm.app.ui.components.AppNavBar
import dev.dawngasm.app.ui.components.MiniPlayer
import dev.dawngasm.app.ui.components.SearchFab
import dev.dawngasm.app.ui.components.SearchSheet
import dev.dawngasm.app.ui.screens.HomeScreen
import dev.dawngasm.app.ui.screens.LibraryScreen
import dev.dawngasm.app.ui.screens.SettingsScreen
import dev.dawngasm.app.ui.theme.Dimens
import dev.dawngasm.app.ui.theme.DawngasmTheme

@Composable
fun DawngasmApp(viewModel: AppViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
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

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message.text)
        }
    }

    BackHandler(enabled = isPlayerExpanded || selectedTab != NavigationTab.Home || state.canGoBack) {
        if (isPlayerExpanded) {
            isPlayerExpanded = false
        } else if (selectedTab != NavigationTab.Home) {
            selectedTab = NavigationTab.Home
        } else if (state.canGoBack) {
            viewModel.navigateBack()
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
                        onScrollRestored = viewModel::onScrollRestored,
                        onSimilarClick = { track, scrollIndex, scrollOffset ->
                            viewModel.showSimilar(track, scrollIndex, scrollOffset)
                        },
                        onOpenSearch = { searchOpen = true },
                        onClearSearch = viewModel::clearSearch,
                        onRefresh = viewModel::refresh,
                        onLoadMore = viewModel::loadMore,
                        onTrackClick = { viewModel.play(it, state.tracks) },
                        onToggleSave = viewModel::toggleSave,
                        onToggleDownload = viewModel::download,
                        bottomInset = bottomInset,
                    )

                    NavigationTab.Library -> LibraryScreen(
                        historyTracks = state.history,
                        savedTracks = state.saved,
                        downloadedTracks = state.downloads,
                        downloadedIds = downloadedIds,
                        downloadingIds = state.downloadingIds,
                        onTrackClick = { track, list -> viewModel.play(track, list) },
                        onToggleDownload = viewModel::download,
                        onSimilarClick = { track ->
                            selectedTab = NavigationTab.Home
                            viewModel.showSimilar(track, 0, 0)
                        },
                        bottomInset = bottomInset,
                    )

                    NavigationTab.Settings -> SettingsScreen(
                        blockedTags = state.blockedTags,
                        downloadFolderLabel = state.downloadFolderLabel,
                        downloadTreeUri = state.downloadTreeUri,
                        tagLookup = viewModel::suggestTags,
                        hotaudioConnected = state.hotaudioConnected,
                        onHotaudioClearanceChanged = viewModel::refreshHotaudioClearance,
                        onClearHotaudioClearance = viewModel::clearHotaudioClearance,
                        audiochanConnected = state.audiochanConnected,
                        onAudiochanClearanceChanged = viewModel::refreshAudiochanClearance,
                        onClearAudiochanClearance = viewModel::clearAudiochanClearance,
                        onAddBlockedTag = viewModel::addBlockedTag,
                        onRemoveBlockedTag = viewModel::removeBlockedTag,
                        onDownloadTreeChange = viewModel::setDownloadTree,
                        onClearDownloads = viewModel::clearDownloads,
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
                onTogglePlayPause = viewModel::togglePlayPause,
                onToggleSave = {
                    state.player.currentTrack?.let(viewModel::toggleSave)
                },
                onNextTrack = viewModel::nextTrack,
                onDismiss = viewModel::dismissPlayer,
                onSeek = viewModel::seekTo,
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
            tagLookup = viewModel::suggestTags,
            onDismiss = { searchOpen = false },
            onApply = { request ->
                viewModel.applySearch(request)
                searchOpen = false
            },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HomePreview() {
    DawngasmTheme(darkTheme = false) {
        val sample = listOf(
            Track(
                id = "1",
                title = "[F4M] A very long, warm and slightly ridiculous title that wraps onto a second line",
                author = "LateNightAudio",
                host = AudioHost.SOUNDGASM,
                pageUrl = "",
                score = 12_400,
                createdUtc = System.currentTimeMillis() / 1000L - 7_200,
            ),
            Track(
                id = "2",
                title = "[M4F] Quiet rain and a slow story",
                author = "SleepyVibes",
                host = AudioHost.WHYP,
                pageUrl = "",
                score = 840,
                createdUtc = System.currentTimeMillis() / 1000L - 86_400,
            ),
            Track(
                id = "3",
                title = "[FF4M] Two voices, one long afternoon",
                author = "AuraSphere",
                host = AudioHost.AUDIOCHAN,
                pageUrl = "",
                score = 3_100,
                createdUtc = System.currentTimeMillis() / 1000L - 172_800,
            ),
        )
        HomeScreen(
            tracks = sample,
            savedIds = setOf("2"),
            downloadedIds = setOf("3"),
            downloadingIds = emptySet(),
            query = "",
            isLoading = false,
            isRefreshing = false,
            error = null,
            hasMore = false,
            onOpenSearch = {},
            onClearSearch = {},
            onRefresh = {},
            onLoadMore = {},
            onTrackClick = {},
            onToggleSave = {},
            onToggleDownload = {},
        )
    }
}
