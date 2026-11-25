package dev.dawngasm.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import dev.dawngasm.app.state.LibrarySubTab
import dev.dawngasm.app.state.NavigationTab
import dev.dawngasm.app.state.PlaybackProgress
import dev.dawngasm.app.state.UiMessage
import dev.dawngasm.app.ui.components.AppNavBar
import dev.dawngasm.app.ui.components.AppNavRail
import dev.dawngasm.app.ui.components.DesktopTopBar
import dev.dawngasm.app.ui.components.MiniPlayer
import dev.dawngasm.app.ui.components.PillTabs
import dev.dawngasm.app.ui.components.SearchFab
import dev.dawngasm.app.ui.components.SearchRow
import dev.dawngasm.app.ui.components.SearchSheet
import dev.dawngasm.app.ui.screens.HomeScreen
import dev.dawngasm.app.ui.screens.LibraryScreen
import dev.dawngasm.app.ui.screens.SettingsScreen
import dev.dawngasm.app.ui.theme.Dimens
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Windows at least this wide get a navigation rail instead of a bottom bar. */
private val WideLayoutBreakpoint = 720.dp

/** Desktop windows at least this wide use the top-navigation shell. */
private val DesktopShellBreakpoint = 840.dp

private const val SEEK_STEP_MS = 10_000L

/**
 * Direction-aware page switch: the incoming page drifts in from the side of
 * the tab order it lives on, with a springy scale so the change reads as a
 * deliberate move instead of a plain cross-fade.
 */
private fun AnimatedContentTransitionScope<NavigationTab>.tabTransition(): ContentTransform {
    val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
    return (
        fadeIn(tween(240)) +
            scaleIn(
                initialScale = 0.965f,
                animationSpec = spring(
                    dampingRatio = 0.6f,
                    stiffness = 420f,
                ),
            ) +
            slideInHorizontally(
                animationSpec = spring(
                    dampingRatio = 0.72f,
                    stiffness = 420f,
                ),
            ) { width -> direction * width / 14 }
        ) togetherWith (
        fadeOut(tween(150)) +
            scaleOut(
                targetScale = 1.015f,
                animationSpec = tween(200),
            ) +
            slideOutHorizontally(tween(210)) { width -> -direction * width / 22 }
        )
}

@Composable
fun DawngasmApp(
    state: AppUiState,
    progress: PlaybackProgress,
    messages: Flow<UiMessage>,
    actions: DawngasmActions,
    settingsPlatform: SettingsPlatformActions,
    appVersion: String,
    commands: Flow<AppCommand> = emptyFlow(),
    isDesktop: Boolean = false,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val focusRequester = remember { FocusRequester() }
    var selectedTab by rememberSaveable { mutableStateOf(NavigationTab.Home) }
    var librarySubTab by rememberSaveable { mutableStateOf(LibrarySubTab.History) }
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

        if ((event.isCtrlPressed || event.isMetaPressed) &&
            (event.key == Key.F || event.key == Key.K)
        ) {
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

    // One definition of every screen, with the desktop shell switching a few
    // layout flags. That keeps both shells in sync as the window is resized.
    val screenContent: @Composable (NavigationTab, Boolean) -> Unit = { tab, desktop ->
        val horizontalPadding = if (desktop) {
            Dimens.DesktopHorizontalPadding
        } else {
            Dimens.ScreenHorizontalPadding
        }
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
                showSearchRow = !desktop,
                showSimilarAction = desktop,
                pullToRefreshEnabled = !desktop,
                horizontalPadding = horizontalPadding,
                contentTopPadding = if (desktop) 16.dp else 8.dp,
            )

            NavigationTab.Library -> LibraryScreen(
                historyTracks = state.history,
                savedTracks = state.saved,
                downloadedTracks = state.downloads,
                downloadedIds = downloadedIds,
                downloadingIds = state.downloadingIds,
                selectedSubTab = librarySubTab,
                onSubTabSelected = { librarySubTab = it },
                onTrackClick = { track, list -> actions.play(track, list) },
                onToggleDownload = actions::download,
                onSimilarClick = { track ->
                    selectedTab = NavigationTab.Home
                    actions.showSimilar(track, 0, 0)
                },
                bottomInset = bottomInset,
                showTabs = !desktop,
                showSimilarAction = desktop,
                horizontalPadding = horizontalPadding,
                contentTopPadding = if (desktop) 16.dp else Dimens.ScreenHorizontalPadding,
            )

            NavigationTab.Settings -> SettingsScreen(
                blockedTags = state.blockedTags,
                downloadFolderLabel = state.downloadFolderLabel,
                downloadTreeUri = state.downloadTreeUri,
                tagLookup = actions::suggestTags,
                hotaudioConnected = state.hotaudioConnected,
                audiochanConnected = state.audiochanConnected,
                mediaSessionServiceEnabled = state.mediaSessionServiceEnabled,
                syncStatus = state.syncStatus,
                onToggleMediaSessionService = actions::toggleMediaSessionService,
                onClearHotaudioClearance = actions::clearHotaudioClearance,
                onClearAudiochanClearance = actions::clearAudiochanClearance,
                onAddBlockedTag = actions::addBlockedTag,
                onRemoveBlockedTag = actions::removeBlockedTag,
                onDownloadTreeChange = actions::setDownloadTree,
                onClearDownloads = actions::clearDownloads,
                onExportBackup = actions::exportEncryptedBackup,
                onImportBackup = actions::importEncryptedBackup,
                onConfigureSync = actions::configureSync,
                onUnlockSync = actions::unlockSync,
                onSyncNow = actions::syncNow,
                onDisableSync = actions::disableSync,
                onNotifyMessage = actions::notifyMessage,
                platform = settingsPlatform,
                appVersion = appVersion,
                wideLayout = desktop,
                showTitle = !desktop,
                horizontalPadding = horizontalPadding,
            )
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
        // Desktop windows below the shell breakpoint get the exact phone
        // layout (bottom navigation), not the tablet rail.
        val useNavigationRail = !isDesktop && maxWidth >= WideLayoutBreakpoint
        val useDesktopShell = isDesktop && maxWidth >= DesktopShellBreakpoint

        AnimatedContent(
            targetState = useDesktopShell,
            transitionSpec = {
                val enteringDesktop = targetState
                // Desktop settles down from above; the phone layout rises up
                // from the bottom. A bouncy scale gives the reflow weight.
                val enterSlide = if (enteringDesktop) -1f / 9f else 1f / 9f
                val exitSlide = if (enteringDesktop) 1f / 26f else -1f / 26f
                val enterScale = if (enteringDesktop) 0.92f else 0.95f
                val exitScale = if (enteringDesktop) 1.04f else 0.95f
                (fadeIn(tween(220)) +
                    scaleIn(
                        initialScale = enterScale,
                        animationSpec = spring(
                            dampingRatio = 0.6f,
                            stiffness = 380f,
                        ),
                    ) +
                    slideInVertically(
                        animationSpec = spring(
                            dampingRatio = 0.72f,
                            stiffness = 360f,
                        ),
                    ) { height -> (height * enterSlide).toInt() }
                    ) togetherWith (
                    fadeOut(tween(160)) +
                        scaleOut(
                            targetScale = exitScale,
                            animationSpec = tween(240),
                        ) +
                        slideOutVertically(tween(220)) { height ->
                            (height * exitSlide).toInt()
                        }
                    )
            },
            label = "shellLayoutTransition",
            modifier = Modifier.fillMaxSize(),
        ) { desktopShell ->
            if (desktopShell) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = Dimens.DesktopMaxContentWidth)
                            .fillMaxWidth()
                            .fillMaxHeight(),
                    ) {
                        DesktopTopBar(
                            selectedTab = selectedTab,
                            onTabSelect = { selectedTab = it },
                        ) {
                            AnimatedContent(
                                targetState = selectedTab,
                                transitionSpec = {
                                    val direction =
                                        if (targetState.ordinal > initialState.ordinal) 1 else -1
                                    (fadeIn(tween(200)) +
                                        scaleIn(
                                            initialScale = 0.96f,
                                            animationSpec = spring(
                                                dampingRatio = 0.7f,
                                                stiffness = 500f,
                                            ),
                                        ) +
                                        slideInHorizontally(tween(260)) { width ->
                                            direction * width / 20
                                        }
                                        ) togetherWith (
                                        fadeOut(tween(120)) +
                                            slideOutHorizontally(tween(180)) { width ->
                                                -direction * width / 28
                                            }
                                        )
                                },
                                label = "desktopTopBarLeading",
                            ) { tab ->
                                when (tab) {
                                    NavigationTab.Home -> SearchRow(
                                        query = state.searchSummary,
                                        onClick = { searchOpen = true },
                                        onClear = actions::clearSearch,
                                        shortcutHint = "Ctrl K",
                                        height = Dimens.PillTabsHeight,
                                        modifier = Modifier.fillMaxWidth(),
                                    )

                                    NavigationTab.Library -> PillTabs(
                                        selectedTab = librarySubTab,
                                        onTabSelected = { librarySubTab = it },
                                        modifier = Modifier.fillMaxWidth(),
                                    )

                                    NavigationTab.Settings -> Text(
                                        text = "Settings",
                                        style = MaterialTheme.typography.headlineMedium,
                                        color = MaterialTheme.colorScheme.onBackground,
                                    )
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        ) {
                            AnimatedContent(
                                targetState = selectedTab,
                                transitionSpec = { tabTransition() },
                                label = "desktopScreenTabTransition",
                                modifier = Modifier.fillMaxSize(),
                            ) { tab ->
                                screenContent(tab, true)
                            }

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
                        }
                    }

                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 24.dp + bottomInset),
                    )
                }
            } else {
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
                                transitionSpec = { tabTransition() },
                                label = "screenTabTransition",
                                modifier = Modifier.fillMaxSize(),
                            ) { tab ->
                                screenContent(tab, false)
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
        }

        val searchSheet: @Composable (Boolean) -> Unit = { desktopOverlay ->
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
                desktopOverlay = desktopOverlay,
            )
        }

        if (useDesktopShell) {
            AnimatedVisibility(
                visible = searchOpen,
                enter = fadeIn(tween(180)) +
                    scaleIn(
                        initialScale = 0.90f,
                        animationSpec = spring(
                            dampingRatio = 0.55f,
                            stiffness = 520f,
                        ),
                    ) +
                    slideInVertically(
                        animationSpec = spring(
                            dampingRatio = 0.7f,
                            stiffness = 450f,
                        ),
                    ) { height -> -height / 36 },
                exit = fadeOut(tween(140)) +
                    scaleOut(
                        targetScale = 0.94f,
                        animationSpec = tween(170),
                    ) +
                    slideOutVertically(tween(170)) { height -> -height / 60 },
                label = "desktopSearchVisibility",
            ) {
                searchSheet(true)
            }
        } else if (searchOpen) {
            searchSheet(false)
        }
    }
}
