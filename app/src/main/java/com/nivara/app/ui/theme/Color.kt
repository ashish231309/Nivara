package com.nivara.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Nivara brand palette.
 *
 * The identity is a deep teal, a calm colour that reads as "protected" without looking like a
 * warning. The full Material 3 role set is defined for light and dark so screens never reach for
 * a raw colour: actions and accents are primary, positive/success states are tertiary, failures
 * are error, and quiet supporting text is onSurfaceVariant. Those meanings are the contract;
 * adding a colour means adding it here as a role, not inline in a screen.
 */
internal object NivaraColors {
    // Light
    val PrimaryLight = Color(0xFF0B6B60)
    val OnPrimaryLight = Color(0xFFFFFFFF)
    val PrimaryContainerLight = Color(0xFFA6F2E3)
    val OnPrimaryContainerLight = Color(0xFF00201C)
    val SecondaryLight = Color(0xFF4A635E)
    val OnSecondaryLight = Color(0xFFFFFFFF)
    val SecondaryContainerLight = Color(0xFFCCE8E2)
    val OnSecondaryContainerLight = Color(0xFF05201B)
    val TertiaryLight = Color(0xFF43607A)
    val OnTertiaryLight = Color(0xFFFFFFFF)
    val BackgroundLight = Color(0xFFFAFDFB)
    val OnBackgroundLight = Color(0xFF191C1B)
    val SurfaceLight = Color(0xFFFAFDFB)
    val OnSurfaceLight = Color(0xFF191C1B)
    val SurfaceVariantLight = Color(0xFFDAE5E1)
    val OnSurfaceVariantLight = Color(0xFF3F4946)
    val OutlineLight = Color(0xFF6F7976)
    val ErrorLight = Color(0xFFBA1A1A)
    val OnErrorLight = Color(0xFFFFFFFF)
    val ErrorContainerLight = Color(0xFFFFDAD6)
    val OnErrorContainerLight = Color(0xFF410002)

    // Dark
    val PrimaryDark = Color(0xFF89D5C7)
    val OnPrimaryDark = Color(0xFF003731)
    val PrimaryContainerDark = Color(0xFF005049)
    val OnPrimaryContainerDark = Color(0xFFA6F2E3)
    val SecondaryDark = Color(0xFFB1CCC6)
    val OnSecondaryDark = Color(0xFF1B3530)
    val SecondaryContainerDark = Color(0xFF324B46)
    val OnSecondaryContainerDark = Color(0xFFCCE8E2)
    val TertiaryDark = Color(0xFFABC9E8)
    val OnTertiaryDark = Color(0xFF123349)
    val BackgroundDark = Color(0xFF101413)
    val OnBackgroundDark = Color(0xFFE0E3E1)
    val SurfaceDark = Color(0xFF101413)
    val OnSurfaceDark = Color(0xFFE0E3E1)
    val SurfaceVariantDark = Color(0xFF3F4946)
    val OnSurfaceVariantDark = Color(0xFFBEC9C5)
    val OutlineDark = Color(0xFF89938F)
    val ErrorDark = Color(0xFFFFB4AB)
    val OnErrorDark = Color(0xFF690005)
    val ErrorContainerDark = Color(0xFF93000A)
    val OnErrorContainerDark = Color(0xFFFFDAD6)
}
