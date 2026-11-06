package dev.dawngasm.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dawngasm.app.model.Track
import dev.dawngasm.app.ui.theme.Dimens
import dev.dawngasm.app.util.compactScore
import dev.dawngasm.app.util.relativeTime

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
) {
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

    Surface(
        onClick = { onTrackClick(track) },
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .testTag("track_card_${track.id}"),
        shape = ExpressiveCardShapes.forPosition(cardPosition),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        interactionSource = interaction,
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
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
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

            if (showSaveAction) {
                val heartTint by animateColorAsState(
                    targetValue = if (isSaved) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    animationSpec = tween(160),
                    label = "heartTint",
                )
                IconButton(
                    onClick = onToggleSave,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("save_track_${track.id}"),
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

            val downloadTint by animateColorAsState(
                targetValue = if (isDownloaded || isDownloading) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                animationSpec = tween(160),
                label = "downloadTint",
            )
            IconButton(
                onClick = onToggleDownload,
                enabled = !isDownloading,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("download_track_${track.id}"),
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
