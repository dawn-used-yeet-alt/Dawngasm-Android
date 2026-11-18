package dev.dawngasm.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val DawngasmShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

object Dimens {
    val ScreenHorizontalPadding = 16.dp

    // Desktop shell: top navigation, generous gutters and a centred content column.
    val DesktopMaxContentWidth = 1600.dp
    val DesktopHorizontalPadding = 48.dp
    val DesktopTopBarHeight = 46.dp
    val DesktopTopBarTopPadding = 12.dp
    val DesktopTopBarBottomPadding = 8.dp
    val DesktopNavItemHeight = 46.dp
    val DesktopNavItemPadding = 16.dp
    val DesktopNavGap = 18.dp
    val DesktopSectionGap = 20.dp

    // Desktop search stays compact: the field does not stretch across the
    // whole header, and the panel is narrower than the field it opens under.
    val DesktopSearchFieldHeight = 46.dp
    val DesktopSearchFieldMaxWidth = 760.dp
    val DesktopSearchPanelWidth = 680.dp
    val DesktopSearchPanelTopPadding = 78.dp
    val DesktopSearchPanelBottomPadding = 24.dp

    // Material 3 Expressive grouped cards.
    val CardCornerRadiusLarge = 28.dp
    val CardCornerRadiusSmall = 5.dp
    val CardConnectedGap = 3.dp
    val CardGroupGap = 12.dp

    val CardInnerStartPadding = 20.dp
    val CardInnerEndPadding = 8.dp
    val CardInnerVerticalPadding = 15.dp

    val SearchRowHeight = 52.dp
    val SearchRowTopPadding = 12.dp
    val SearchRowHorizontalPadding = 20.dp
    val SearchIconSize = 24.dp

    val FabSize = 68.dp
    val FabIconSize = 28.dp

    val MiniPlayerHeight = 72.dp
    val MiniPlayerExpandedHeight = 148.dp
    val MiniPlayerHorizontalMargin = 12.dp
    val MiniPlayerBottomMargin = 8.dp
    val MiniPlayerElevation = 8.dp
    val MiniPlayerStartPadding = 20.dp
    val MiniPlayerEndPadding = 8.dp
    val MiniPlayerActionsGap = 7.dp

    val MiniPlayerHeartSize = 46.dp
    val MiniPlayerHeartIconSize = 23.dp
    val MiniPlayerPlayPauseSize = 46.dp
    val MiniPlayerPlayPauseIconSize = 24.dp
    val MiniPlayerNextSize = 52.dp
    val MiniPlayerNextIconSize = 27.dp

    val PillTabsHeight = 56.dp
    val PillTabsInnerPadding = 4.dp
    val PillTabsIconSize = 20.dp

    val MinTouchTarget = 48.dp
}
