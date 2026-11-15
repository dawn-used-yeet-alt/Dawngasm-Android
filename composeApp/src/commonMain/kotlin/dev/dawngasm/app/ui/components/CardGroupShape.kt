package dev.dawngasm.app.ui.components

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.dawngasm.app.ui.theme.Dimens

enum class CardGroupPosition { Top, Middle, Bottom, Standalone }

object ExpressiveCardShapes {
    val Top: Shape = RoundedCornerShape(
        topStart = Dimens.CardCornerRadiusLarge,
        topEnd = Dimens.CardCornerRadiusLarge,
        bottomStart = Dimens.CardCornerRadiusSmall,
        bottomEnd = Dimens.CardCornerRadiusSmall,
    )

    val Middle: Shape = RoundedCornerShape(Dimens.CardCornerRadiusSmall)

    val Bottom: Shape = RoundedCornerShape(
        topStart = Dimens.CardCornerRadiusSmall,
        topEnd = Dimens.CardCornerRadiusSmall,
        bottomStart = Dimens.CardCornerRadiusLarge,
        bottomEnd = Dimens.CardCornerRadiusLarge,
    )

    val Standalone: Shape = RoundedCornerShape(Dimens.CardCornerRadiusLarge)

    fun forPosition(position: CardGroupPosition): Shape = when (position) {
        CardGroupPosition.Top -> Top
        CardGroupPosition.Middle -> Middle
        CardGroupPosition.Bottom -> Bottom
        CardGroupPosition.Standalone -> Standalone
    }
}

/** Expressive pill shapes for menu items when selected in contiguous groups. */
object ExpressiveMenuItemShapes {
    val LargeRadius = 22.dp
    val SmallRadius = 4.dp

    val Top: Shape = RoundedCornerShape(
        topStart = LargeRadius,
        topEnd = LargeRadius,
        bottomStart = SmallRadius,
        bottomEnd = SmallRadius,
    )

    val Middle: Shape = RoundedCornerShape(SmallRadius)

    val Bottom: Shape = RoundedCornerShape(
        topStart = SmallRadius,
        topEnd = SmallRadius,
        bottomStart = LargeRadius,
        bottomEnd = LargeRadius,
    )

    val Standalone: Shape = CircleShape

    fun forPosition(position: CardGroupPosition): Shape = when (position) {
        CardGroupPosition.Top -> Top
        CardGroupPosition.Middle -> Middle
        CardGroupPosition.Bottom -> Bottom
        CardGroupPosition.Standalone -> Standalone
    }
}

/**
 * Computes the [CardGroupPosition] for an item in a list where only selected items
 * form connected groups (e.g. multi-select dropdown menus).
 */
fun computeSelectionGroupPosition(index: Int, isSelectedList: List<Boolean>): CardGroupPosition {
    if (index !in isSelectedList.indices || !isSelectedList[index]) {
        return CardGroupPosition.Standalone
    }
    val prevSelected = index > 0 && isSelectedList[index - 1]
    val nextSelected = index < isSelectedList.size - 1 && isSelectedList[index + 1]
    return when {
        prevSelected && nextSelected -> CardGroupPosition.Middle
        !prevSelected && nextSelected -> CardGroupPosition.Top
        prevSelected && !nextSelected -> CardGroupPosition.Bottom
        else -> CardGroupPosition.Standalone
    }
}

