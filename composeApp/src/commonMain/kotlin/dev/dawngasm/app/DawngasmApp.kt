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
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.dawngasm.app.platform.DawngasmActions
import dev.dawngasm.app.platform.PlatformBackHandler
import dev.dawngasm.app.platform.SettingsPlatformActions
import dev.dawngasm.app.state.AppCommand
import dev.dawngasm.app.state.AppUiState
import dev.dawngasm.app.state.NavigationTab
import dev.dawngasm.app.state.PlaybackProgress
import dev.dawngasm.app.state.UiMessage
import dev.dawngasm.app.ui.components.AppNavBar
import dev.dawngasm.app.ui.components.AppNavRail
import dev.dawngasm.app.ui.components.MiniPlayer
import dev.dawngasm.app.ui.components.SearchFab
import dev.dawngasm.app.ui.components.SearchSheet
import dev.dawngasm.app.ui.screens.HomeScreen
import dev.dawngasm.app.ui.screens.LibraryScreen
import dev.dawngasm.app.ui.screens.SettingsScreen
import dev.dawngasm.app.ui.theme.Dimens
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Windows at least this wide get a navigation rail instead of a bottom bar. */
private val WideLayoutBreakpoint = 720.dp

private const val SEEK_STEP_MS = 10_000L

@Composable
fun DawngasmApp(
    state: AppUiState,
    progress: PlaybackProgress,
    messages: Flow<UiMessage>,
    actions: DawngasmActions,
    settingsPlatform: SettingsPlatformActions,
    appVersion: String,
    commands: Flow<AppCommand> = emptyFlow(),
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val focusRequester = remember { FocusRequester() }
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

    LaunchedEffect(commands) {
        commands.collect { command ->
            when (command) {
                AppCommand.OpenSearch -> searchOpen = true
                AppCommand.ShowHome -> selectedTab = NavigationTab.Home
                AppCommand.ShowLibrary -> selectedTab = NavigationTab.Library
                AppCommand.ShowSettings -> selectedTab = NavigationTab.Settings
                AppCommand.Refresh -> actions.refresh()
                AppCommand.DismissPlayer -> actions.dismissPlayer()
            }
        }
    }

    // The root owns focus so key events bubble through any focused child. Text
    // fields and lists consume their own keys first, so typing and list
    // navigation never trigger the global playback shortcuts.
    LaunchedEffect(searchOpen) {
        if (!searchOpen) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    fun seekBy(deltaMs: Long) {
        val duration = progress.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
        actions.seekTo((progress.positionMs + deltaMs).coerceIn(0L, duration))
    }

    fun handleEscape(): Boolean = when {
        isPlayerExpanded -> {
            isPlayerExpanded = false
            true
        }

        selectedTab != NavigationTab.Home -> {
            selectedTab = NavigationTab.Home
            true
        }

        state.canGoBack -> {
            actions.navigateBack()
            true
        }

        else -> false
    }

    /**
     * Window-level shortcuts that must win over focused widgets. Only modified
     * keys and Escape are handled here, so plain typing still reaches fields.
     */
    fun handleCommandKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false

        if ((event.isCtrlPressed || event.isMetaPressed) && event.key == Key.F) {
            searchOpen = true
            return true
        }

        if (event.key == Key.Escape) {
            if (searchOpen) {
                searchOpen = false
                return true
            }
            return handleEscape()
        }

        return false
    }

    /**
     * Playback shortcuts use bubbling instead of preview, so a focused text
     * field or list consumes its own Space/arrow input first.
     */
    fun handlePlaybackKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown || searchOpen) return false
        if (event.isCtrlPressed || event.isMetaPressed) return false

        return when (event.key) {
            Key.Spacebar -> {
                if (!playerVisible) return false
                actions.togglePlayPause()
                true
            }

            Key.DirectionLeft -> {
                if (!playerVisible) return false
                seekBy(-SEEK_STEP_MS)
                true
            }

            Key.DirectionRight -> {
                if (!playerVisible) return false
                seekBy(SEEK_STEP_MS)
                true
            }

            Key.DirectionUp -> {
                if (!playerVisible) return false
                actions.previousTrack()
                true
            }

            Key.DirectionDown -> {
                if (!playerVisible) return false
                actions.nextTrack()
                true
            }

            else -> false
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

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event -> handleCommandKey(event) }
            .onKeyEvent { event -> handlePlaybackKey(event) },
    ) {
        val useNavigationRail = maxWidth >= WideLayoutBreakpoint

        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .testTag("app_scaffold"),
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (!useNavigationRail) {
                    AppNavBar(
                        selectedTab = selectedTab,
                        onTabSelect = { selectedTab = it },
                    )
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
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
                                mediaSessionServiceEnabled = state.mediaSessionServiceEnabled,
                                onToggleMediaSessionService = actions::toggleMediaSessionService,
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

                if (useNavigationRail) {
                    AppNavRail(
                        selectedTab = selectedTab,
                        onTabSelect = { selectedTab = it },
                    )
                }
            }
        }
    }

    if (searchOpen) {
        SearchSheet(
            initialQuery = state.query,
            initialTags = state.includeTags,
            initialExcludeTags = state.excludeTags,
            initialAuthors = state.authors,
            initialHosts = state.hosts,
            defaultSort = state.sort,
            defaultWindow = state.window,
            tagLookup = actions::suggestTags,
            authorLookup = actions::suggestAuthors,
            onDismiss = { searchOpen = false },
            onApply = { request ->
                actions.applySearch(request)
                searchOpen = false
            },
        )
    }
}
