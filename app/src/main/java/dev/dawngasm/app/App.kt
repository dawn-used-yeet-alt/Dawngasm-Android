package dev.dawngasm.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
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
import dev.dawngasm.app.ui.theme.DawngasmTheme

@Composable
fun DawngasmApp(viewModel: AppViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by rememberSaveable { mutableStateOf(NavigationTab.Home) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }

    val savedIds = remember(state.saved) { state.saved.mapTo(HashSet()) { it.id } }
    val downloadedIds = remember(state.downloads) { state.downloads.mapTo(HashSet()) { it.id } }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message.text)
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("app_scaffold"),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            val progress by viewModel.progress.collectAsStateWithLifecycle()
            Column(modifier = Modifier.testTag("bottom_bar_container")) {
                MiniPlayer(
                    player = state.player,
                    progress = progress,
                    isSaved = state.player.currentTrack?.let { it.id in savedIds } == true,
                    onTogglePlayPause = viewModel::togglePlayPause,
                    onToggleSave = {
                        state.player.currentTrack?.let(viewModel::toggleSave)
                    },
                    onNextTrack = viewModel::nextTrack,
                    onDismiss = viewModel::dismissPlayer,
                )
                AppNavBar(
                    selectedTab = selectedTab,
                    onTabSelect = { selectedTab = it },
                )
            }
        },
        floatingActionButton = {
            if (selectedTab == NavigationTab.Home && !searchOpen) {
                SearchFab(onClick = { searchOpen = true })
            }
        },
    ) { innerPadding ->
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
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) { tab ->
            when (tab) {
                NavigationTab.Home -> HomeScreen(
                    tracks = state.tracks,
                    savedIds = savedIds,
                    downloadedIds = downloadedIds,
                    downloadingIds = state.downloadingIds,
                    query = state.query,
                    sort = state.sort,
                    window = state.window,
                    isLoading = state.isLoading,
                    isRefreshing = state.isRefreshing,
                    error = state.error,
                    hasMore = state.hasMore,
                    onOpenSearch = { searchOpen = true },
                    onClearSearch = viewModel::clearSearch,
                    onSort = viewModel::setSort,
                    onWindow = viewModel::setWindow,
                    onRefresh = viewModel::refresh,
                    onLoadMore = viewModel::loadMore,
                    onTrackClick = { viewModel.play(it, state.tracks) },
                    onToggleSave = viewModel::toggleSave,
                    onToggleDownload = viewModel::download,
                )

                NavigationTab.Library -> LibraryScreen(
                    historyTracks = state.history,
                    savedTracks = state.saved,
                    downloadedTracks = state.downloads,
                    downloadedIds = downloadedIds,
                    downloadingIds = state.downloadingIds,
                    onTrackClick = { track, list -> viewModel.play(track, list) },
                    onToggleDownload = viewModel::download,
                )

                NavigationTab.Settings -> SettingsScreen(
                    blockedTags = state.blockedTags,
                    downloadFolderLabel = state.downloadFolderLabel,
                    downloadTreeUri = state.downloadTreeUri,
                    onAddBlockedTag = viewModel::addBlockedTag,
                    onRemoveBlockedTag = viewModel::removeBlockedTag,
                    onDownloadTreeChange = viewModel::setDownloadTree,
                    onClearDownloads = viewModel::clearDownloads,
                )
            }
        }
    }

    if (searchOpen) {
        SearchSheet(
            initialQuery = state.query,
            defaultSort = state.sort,
            defaultWindow = state.window,
            onDismiss = { searchOpen = false },
            onApply = { query ->
                viewModel.applySearch(query)
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
            sort = "top",
            window = "day",
            isLoading = false,
            isRefreshing = false,
            error = null,
            hasMore = false,
            onOpenSearch = {},
            onClearSearch = {},
            onSort = {},
            onWindow = {},
            onRefresh = {},
            onLoadMore = {},
            onTrackClick = {},
            onToggleSave = {},
            onToggleDownload = {},
        )
    }
}
