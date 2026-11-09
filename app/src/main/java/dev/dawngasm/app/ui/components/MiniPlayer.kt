package dev.dawngasm.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.dawngasm.app.model.Track
import dev.dawngasm.app.state.PlaybackProgress
import dev.dawngasm.app.state.PlayerUiState
import dev.dawngasm.app.ui.theme.Dimens
import dev.dawngasm.app.util.formatDuration
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun MiniPlayer(
    player: PlayerUiState,
    progress: PlaybackProgress,
    isSaved: Boolean,
    onTogglePlayPause: () -> Unit,
    onToggleSave: () -> Unit,
    onNextTrack: () -> Unit,
    onDismiss: () -> Unit,
    onSeek: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = player.currentTrack != null,
        enter = slideInVertically(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
            initialOffsetY = { it },
        ) + fadeIn(animationSpec = tween(160)),
        exit = slideOutVertically(
            animationSpec = tween(160),
            targetOffsetY = { it },
        ) + fadeOut(animationSpec = tween(110)),
        modifier = modifier,
    ) {
        player.currentTrack?.let { track ->
            MiniPlayerBody(
                track = track,
                player = player,
                progress = progress,
                isSaved = isSaved,
                onTogglePlayPause = onTogglePlayPause,
                onToggleSave = onToggleSave,
                onNextTrack = onNextTrack,
                onDismiss = onDismiss,
                onSeek = onSeek,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MiniPlayerBody(
    track: Track,
    player: PlayerUiState,
    progress: PlaybackProgress,
    isSaved: Boolean,
    onTogglePlayPause: () -> Unit,
    onToggleSave: () -> Unit,
    onNextTrack: () -> Unit,
    onDismiss: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var isExpanded by remember { mutableStateOf(false) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { 40.dp.toPx() }
    val fraction = if (progress.durationMs > 0) {
        (progress.positionMs.toFloat() / progress.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = if (fraction == 0f || isScrubbing) snap() else tween(durationMillis = 500, easing = LinearEasing),
        label = "miniPlayerProgress",
    )

    val cornerRadius by animateDpAsState(
        targetValue = if (isExpanded) Dimens.CardCornerRadiusLarge else Dimens.MiniPlayerHeight / 2,
        animationSpec = spring(
            dampingRatio = 0.6f,
            stiffness = 900f,
        ),
        label = "miniPlayerCornerRadius",
    )
    val cardShape = RoundedCornerShape(cornerRadius)

    LaunchedEffect(track.id) {
        dragOffsetY = 0f
        isScrubbing = false
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = Dimens.MiniPlayerHorizontalMargin,
                vertical = Dimens.MiniPlayerBottomMargin,
            )
            .offset { IntOffset(0, dragOffsetY.roundToInt()) }
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = 0.6f,
                    stiffness = 900f,
                ),
            )
            .clip(cardShape)
            .pointerInput(track.id) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (dragOffsetY > dismissThresholdPx) {
                            if (isExpanded) {
                                isExpanded = false
                            } else {
                                onDismiss()
                            }
                            dragOffsetY = 0f
                        } else {
                            scope.launch {
                                animate(
                                    initialValue = dragOffsetY,
                                    targetValue = 0f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioLowBouncy,
                                        stiffness = Spring.StiffnessMedium,
                                    ),
                                ) { value, _ -> dragOffsetY = value }
                            }
                        }
                    },
                    onVerticalDrag = { change, dragAmount ->
                        if (!isScrubbing) {
                            change.consume()
                            dragOffsetY = (dragOffsetY + dragAmount).coerceAtLeast(0f)
                        }
                    },
                )
            }
            .testTag("mini_player"),
        shape = cardShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = Dimens.MiniPlayerElevation,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Dimens.MiniPlayerHeight)
                    .padding(
                        start = Dimens.MiniPlayerStartPadding,
                        end = Dimens.MiniPlayerEndPadding,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnimatedContent(
                    targetState = track,
                    transitionSpec = {
                        fadeIn(tween(170)) togetherWith fadeOut(tween(90))
                    },
                    label = "playerTrackTransition",
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(),
                            role = Role.Button,
                            onClickLabel = if (isExpanded) "Retract player" else "Expand player",
                            onClick = { isExpanded = !isExpanded },
                        )
                        .padding(vertical = 4.dp, horizontal = 2.dp)
                        .testTag("mini_player_text_toggle"),
                ) { current ->
                    Column {
                        Text(
                            text = current.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val subtitle = buildString {
                            append(current.displayAuthor)
                            if (progress.durationMs > 0 && !isExpanded) {
                                append(" · ")
                                append(formatDuration(progress.positionMs))
                                append(" / ")
                                append(formatDuration(progress.durationMs))
                            }
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 1.dp),
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.MiniPlayerActionsGap),
                ) {
                    val heartTint by animateColorAsState(
                        targetValue = if (isSaved) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        animationSpec = tween(160),
                        label = "miniPlayerHeartTint",
                    )
                    val heartScale by animateFloatAsState(
                        targetValue = if (isSaved) 1.14f else 1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium,
                        ),
                        label = "miniPlayerHeartScale",
                    )
                    Box(
                        modifier = Modifier
                            .size(Dimens.MiniPlayerHeartSize)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
                            .clickable(role = Role.Button, onClick = onToggleSave)
                            .testTag("mini_player_save"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (isSaved) {
                                Icons.Filled.Favorite
                            } else {
                                Icons.Outlined.FavoriteBorder
                            },
                            contentDescription = if (isSaved) "Saved" else "Save",
                            tint = heartTint,
                            modifier = Modifier
                                .size(Dimens.MiniPlayerHeartIconSize)
                                .scale(heartScale),
                        )
                    }

                    MorphingProgressIconButton(
                        progress = { if (isScrubbing) scrubFraction else animatedFraction },
                        indeterminate = player.isLoading,
                        onClick = onTogglePlayPause,
                        enabled = true,
                        backgroundColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.1f),
                        progressColor = MaterialTheme.colorScheme.primary,
                        buttonColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("mini_player_play_pause"),
                    ) {
                        AnimatedContent(
                            targetState = player.isPlaying,
                            transitionSpec = {
                                fadeIn(tween(120)) togetherWith fadeOut(tween(90))
                            },
                            label = "playPauseIcon",
                        ) { playing ->
                            Icon(
                                imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (player.isLoading) "Song loading" else if (playing) "Pause" else "Play",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(Dimens.MiniPlayerPlayPauseIconSize),
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(Dimens.MiniPlayerNextSize)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.tertiaryContainer)
                            .clickable(role = Role.Button, onClick = onNextTrack)
                            .testTag("mini_player_next"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SkipNext,
                            contentDescription = "Next track",
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(Dimens.MiniPlayerNextIconSize),
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(
                    animationSpec = spring(
                        dampingRatio = 0.6f,
                        stiffness = 900f,
                    ),
                ) + fadeIn(animationSpec = tween(140)),
                exit = shrinkVertically(
                    animationSpec = spring(
                        dampingRatio = 0.6f,
                        stiffness = 900f,
                    ),
                ) + fadeOut(animationSpec = tween(90)),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = Dimens.MiniPlayerStartPadding,
                            end = Dimens.MiniPlayerStartPadding,
                            bottom = 12.dp,
                        ),
                ) {
                    val currentFraction = if (isScrubbing) scrubFraction else fraction
                    val currentPositionMs = if (isScrubbing) {
                        (scrubFraction * progress.durationMs).toLong().coerceIn(0L, progress.durationMs)
                    } else {
                        progress.positionMs
                    }

                    val sliderInteractionSource = remember { MutableInteractionSource() }
                    Slider(
                        value = currentFraction,
                        onValueChange = {
                            isScrubbing = true
                            scrubFraction = it
                        },
                        onValueChangeFinished = {
                            val targetMs = (scrubFraction * progress.durationMs).toLong().coerceIn(0L, progress.durationMs)
                            onSeek(targetMs)
                            isScrubbing = false
                        },
                        enabled = progress.durationMs > 0,
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        interactionSource = sliderInteractionSource,
                        thumb = {
                            SliderDefaults.Thumb(
                                interactionSource = sliderInteractionSource,
                                colors = SliderDefaults.colors(
                                    thumbColor = MaterialTheme.colorScheme.primary,
                                ),
                                enabled = progress.durationMs > 0,
                                thumbSize = DpSize(width = 4.dp, height = 22.dp),
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("mini_player_seek_slider"),
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp)
                            .offset(y = (-6).dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = formatDuration(currentPositionMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = if (progress.durationMs > 0) formatDuration(progress.durationMs) else "0:00",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
