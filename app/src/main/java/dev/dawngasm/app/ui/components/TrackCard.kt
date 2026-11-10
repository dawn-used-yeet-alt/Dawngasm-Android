package dev.dawngasm.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dawngasm.app.model.Track
import dev.dawngasm.app.ui.theme.Dimens
import dev.dawngasm.app.util.compactScore
import dev.dawngasm.app.util.relativeTime

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackCard(
    track: Track,
    isSaved: Boolean,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    onTrackClick: (Track) -> Unit,
    onToggleSave: () -> Unit,
    onToggleDownload: () -> Unit,
    modifier: Modifier = Modifier,
    cardPosition: CardGroupPosition = CardGroupPosition.Standalone,
    showSaveAction: Boolean = true,
    onSimilarClick: () -> Unit = {},
) {
    var isExpanded by rememberSaveable(track.id) { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "cardPressScale",
    )
    val heartScale by animateFloatAsState(
        targetValue = if (isSaved) 1.12f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "heartScale",
    )
    val expandProgress by animateFloatAsState(
        targetValue = if (isExpanded) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "trackCardExpandProgress",
    )
    val clampedProgress = expandProgress.coerceIn(0f, 1f)

    val shape = ExpressiveCardShapes.forPosition(cardPosition)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = 0.6f,
                    stiffness = 900f,
                ),
            )
            .scale(scale)
            .clip(shape)
            .combinedClickable(
                interactionSource = interaction,
                indication = ripple(),
                role = Role.Button,
                onClickLabel = "Play ${track.title}",
                onLongClickLabel = if (isExpanded) "Collapse title" else "Expand title",
                onClick = { onTrackClick(track) },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    isExpanded = !isExpanded
                },
            )
            .testTag("track_card_${track.id}"),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = Dimens.CardInnerStartPadding,
                    end = Dimens.CardInnerEndPadding,
                    top = Dimens.CardInnerVerticalPadding,
                    bottom = Dimens.CardInnerVerticalPadding,
                ),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp),
            ) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (isExpanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.size(3.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(
                        text = buildString {
                            append(track.displayAuthor)
                            val age = relativeTime(track.createdUtc)
                            if (age.isNotBlank()) append(" · $age")
                            if (track.score > 0) append(" · ${compactScore(track.score)}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            TrackCardActions(
                isExpanded = isExpanded,
                clampedProgress = clampedProgress,
                showSaveAction = showSaveAction,
                isSaved = isSaved,
                heartScale = heartScale,
                onToggleSave = onToggleSave,
                isDownloaded = isDownloaded,
                isDownloading = isDownloading,
                onToggleDownload = onToggleDownload,
                onSimilarClick = onSimilarClick,
                trackId = track.id,
            )
        }
    }
}

@Composable
private fun TrackCardActions(
    isExpanded: Boolean,
    clampedProgress: Float,
    showSaveAction: Boolean,
    isSaved: Boolean,
    heartScale: Float,
    onToggleSave: () -> Unit,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    onToggleDownload: () -> Unit,
    onSimilarClick: () -> Unit,
    trackId: String,
) {
    val buttonSize = 48.dp

    val containerWidth = if (showSaveAction) {
        buttonSize * (2f - clampedProgress)
    } else {
        buttonSize
    }

    val containerHeight = if (showSaveAction) {
        buttonSize * (1f + 2f * clampedProgress)
    } else {
        buttonSize * (1f + clampedProgress)
    }

    val heartTint by animateColorAsState(
        targetValue = if (isSaved) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(160),
        label = "heartTint",
    )

    val downloadTint by animateColorAsState(
        targetValue = if (isDownloaded || isDownloading) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(160),
        label = "downloadTint",
    )

    Box(
        modifier = Modifier.size(width = containerWidth, height = containerHeight),
    ) {
        if (showSaveAction) {
            IconButton(
                onClick = onToggleSave,
                modifier = Modifier
                    .offset(x = 0.dp, y = 0.dp)
                    .size(buttonSize)
                    .testTag("save_track_$trackId"),
            ) {
                Icon(
                    imageVector = if (isSaved) {
                        Icons.Filled.Favorite
                    } else {
                        Icons.Outlined.FavoriteBorder
                    },
                    contentDescription = if (isSaved) "Saved" else "Save",
                    tint = heartTint,
                    modifier = Modifier.scale(heartScale),
                )
            }
        }

        val downloadOffsetX = if (showSaveAction) {
            buttonSize * (1f - clampedProgress)
        } else {
            0.dp
        }
        val downloadOffsetY = if (showSaveAction) {
            buttonSize * clampedProgress
        } else {
            0.dp
        }

        IconButton(
            onClick = onToggleDownload,
            enabled = !isDownloading,
            modifier = Modifier
                .offset(x = downloadOffsetX, y = downloadOffsetY)
                .size(buttonSize)
                .testTag("download_track_$trackId"),
        ) {
            if (isDownloading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(21.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Icon(
                    imageVector = if (isDownloaded) {
                        Icons.Outlined.DownloadDone
                    } else {
                        Icons.Outlined.Download
                    },
                    contentDescription = if (isDownloaded) "Downloaded" else "Download",
                    tint = downloadTint,
                )
            }
        }

        val similarOffsetY = if (showSaveAction) {
            buttonSize * (1f + clampedProgress)
        } else {
            buttonSize * clampedProgress
        }
        val similarAlpha = clampedProgress
        val similarScale = 0.5f + 0.5f * clampedProgress

        if (clampedProgress > 0.01f) {
            IconButton(
                onClick = onSimilarClick,
                enabled = isExpanded && clampedProgress > 0.7f,
                modifier = Modifier
                    .offset(x = 0.dp, y = similarOffsetY)
                    .size(buttonSize)
                    .graphicsLayer {
                        alpha = similarAlpha
                        scaleX = similarScale
                        scaleY = similarScale
                    }
                    .testTag("similar_track_$trackId"),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Hub,
                    contentDescription = "Related posts",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A soft skeleton used while the first feed page is loading. */
@Composable
fun TrackSkeleton(
    modifier: Modifier = Modifier,
    cardPosition: CardGroupPosition = CardGroupPosition.Standalone,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = ExpressiveCardShapes.forPosition(cardPosition),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(
                start = Dimens.CardInnerStartPadding,
                end = 64.dp,
                top = 18.dp,
                bottom = 18.dp,
            ),
        ) {
            SkeletonBar(widthFraction = 0.72f, height = 16.dp)
            Spacer(modifier = Modifier.size(9.dp))
            SkeletonBar(widthFraction = 0.42f, height = 11.dp)
        }
    }
}

@Composable
private fun SkeletonBar(widthFraction: Float, height: androidx.compose.ui.unit.Dp) {
    val transition = rememberInfiniteTransition(label = "skeletonShimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.16f,
        targetValue = 0.42f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeletonAlpha",
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(height),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
    ) {}
}
