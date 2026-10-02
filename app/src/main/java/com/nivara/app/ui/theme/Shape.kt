package com.nivara.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * The shape family of the application.
 *
 * One small family rather than a radius per component: cards, dialogs, fields and chips all draw
 * from the same five values, so a row and the card it sits in can never disagree about how
 * rounded they are. The sizes step gently — nothing is a sharp corner, and nothing becomes a
 * pill unless a component chooses the full rounding itself.
 */
internal val NivaraShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
