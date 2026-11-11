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
