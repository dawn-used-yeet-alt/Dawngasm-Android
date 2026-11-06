package dev.dawngasm.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dawngasm.app.state.LibrarySubTab
import dev.dawngasm.app.ui.theme.Dimens

@Composable
fun PillTabs(
    selectedTab: LibrarySubTab,
    onTabSelected: (LibrarySubTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.PillTabsHeight)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(Dimens.PillTabsInnerPadding)
            .selectableGroup()
            .testTag("pill_tabs"),
    ) {
        val totalWidth = maxWidth
        val tabWidth = totalWidth / 3
        val targetIndex = when (selectedTab) {
            LibrarySubTab.History -> 0f
            LibrarySubTab.Saved -> 1f
            LibrarySubTab.Downloads -> 2f
        }
        val animatedIndex by animateFloatAsState(
            targetValue = targetIndex,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
            label = "tabIndicatorAnimation",
        )

        Box(
            modifier = Modifier
                .offset(x = tabWidth * animatedIndex)
                .width(tabWidth)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )

        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillTabItem(
                label = "History",
                icon = Icons.Outlined.History,
                isSelected = selectedTab == LibrarySubTab.History,
                onClick = { onTabSelected(LibrarySubTab.History) },
                testTag = "pill_tab_history",
                modifier = Modifier.weight(1f),
            )
            PillTabItem(
                label = "Saved",
                icon = if (selectedTab == LibrarySubTab.Saved) {
                    Icons.Filled.Favorite
                } else {
                    Icons.Outlined.FavoriteBorder
                },
                isSelected = selectedTab == LibrarySubTab.Saved,
                onClick = { onTabSelected(LibrarySubTab.Saved) },
                testTag = "pill_tab_saved",
                modifier = Modifier.weight(1f),
            )
            PillTabItem(
                label = "Downloads",
                icon = if (selectedTab == LibrarySubTab.Downloads) {
                    Icons.Outlined.DownloadDone
                } else {
                    Icons.Outlined.Download
                },
                isSelected = selectedTab == LibrarySubTab.Downloads,
                onClick = { onTabSelected(LibrarySubTab.Downloads) },
                testTag = "pill_tab_downloads",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PillTabItem(
    label: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(180),
        label = "pillTabColor",
    )

    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(CircleShape)
            .selectable(
                selected = isSelected,
                onClick = onClick,
                role = Role.Tab,
            )
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 3.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
