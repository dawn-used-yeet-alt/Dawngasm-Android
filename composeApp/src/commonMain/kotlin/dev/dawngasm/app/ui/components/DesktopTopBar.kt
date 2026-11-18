package dev.dawngasm.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dawngasm.app.state.NavigationTab
import dev.dawngasm.app.ui.theme.Dimens

/**
 * Top bar used by the desktop shell. The leading slot holds the search field,
 * the library tabs or the settings title, while the tab strip stays on the
 * trailing edge. Keeping one row means the header never shifts when switching
 * tabs.
 */
@Composable
fun DesktopTopBar(
    selectedTab: NavigationTab,
    onTabSelect: (NavigationTab) -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = Dimens.DesktopHorizontalPadding,
                end = Dimens.DesktopHorizontalPadding,
                top = Dimens.DesktopTopBarTopPadding,
                bottom = Dimens.DesktopTopBarBottomPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = Dimens.DesktopTopBarHeight),
            contentAlignment = Alignment.CenterStart,
        ) {
            leading()
        }
        Spacer(modifier = Modifier.width(Dimens.DesktopNavGap))
        DesktopNav(
            selectedTab = selectedTab,
            onTabSelect = onTabSelect,
        )
    }
}

@Composable
fun DesktopNav(
    selectedTab: NavigationTab,
    onTabSelect: (NavigationTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        DesktopNavItem(
            selected = selectedTab == NavigationTab.Home,
            label = "Home",
            selectedIcon = Icons.Filled.Home,
            unselectedIcon = Icons.Outlined.Home,
            onClick = { onTabSelect(NavigationTab.Home) },
            testTag = "top_nav_home",
        )
        DesktopNavItem(
            selected = selectedTab == NavigationTab.Library,
            label = "Library",
            selectedIcon = Icons.Filled.LibraryMusic,
            unselectedIcon = Icons.Outlined.LibraryMusic,
            onClick = { onTabSelect(NavigationTab.Library) },
            testTag = "top_nav_library",
        )
        DesktopNavItem(
            selected = selectedTab == NavigationTab.Settings,
            label = "Settings",
            selectedIcon = Icons.Filled.Settings,
            unselectedIcon = Icons.Outlined.Settings,
            onClick = { onTabSelect(NavigationTab.Settings) },
            testTag = "top_nav_settings",
        )
    }
}

@Composable
private fun DesktopNavItem(
    selected: Boolean,
    label: String,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
    onClick: () -> Unit,
    testTag: String,
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(180),
        label = "desktopNavContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(180),
        label = "desktopNavContent",
    )

    Row(
        modifier = Modifier
            .height(Dimens.DesktopNavItemHeight)
            .clip(CircleShape)
            .background(containerColor)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = Dimens.DesktopNavItemPadding)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(
            imageVector = if (selected) selectedIcon else unselectedIcon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(21.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
