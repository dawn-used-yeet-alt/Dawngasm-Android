package dev.dawngasm.app.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
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
