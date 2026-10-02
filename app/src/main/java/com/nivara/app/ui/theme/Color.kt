package com.nivara.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Nivara brand palette.
 *
 * The identity is a clear, confident blue on white: a calm colour that reads as "protected"
 * without looking like a warning, on surfaces that stay light and uncluttered. The full
 * Material 3 role set is defined for light and dark so screens never reach for a raw colour:
 * actions and accents are primary, positive/success states are tertiary, failures are error,
 * and quiet supporting text is onSurfaceVariant. Those meanings are the contract; adding a
 * colour means adding it here as a role, not inline in a screen.
 *
 * The header gradient roles are the one deliberate exception to "a single flat primary": the
 * home screen's banner is the product's front door, and it draws the two brand blues as a
 * gradient. Every other screen uses the flat roles.
 */
internal object NivaraColors {
    // Light
    val PrimaryLight = Color(0xFF2456E5)
    val OnPrimaryLight = Color(0xFFFFFFFF)
    val PrimaryContainerLight = Color(0xFFDCE3FF)
    val OnPrimaryContainerLight = Color(0xFF001847)
    val SecondaryLight = Color(0xFF575E71)
    val OnSecondaryLight = Color(0xFFFFFFFF)
    val SecondaryContainerLight = Color(0xFFDBE2F9)
    val OnSecondaryContainerLight = Color(0xFF141B2C)
    val TertiaryLight = Color(0xFF6E5676)
    val OnTertiaryLight = Color(0xFFFFFFFF)
    val BackgroundLight = Color(0xFFFBFCFF)
    val OnBackgroundLight = Color(0xFF1A1B1F)
    val SurfaceLight = Color(0xFFFBFCFF)
    val OnSurfaceLight = Color(0xFF1A1B1F)
    val SurfaceVariantLight = Color(0xFFE1E2EC)
    val OnSurfaceVariantLight = Color(0xFF44464F)
    val OutlineLight = Color(0xFF747680)
    val ErrorLight = Color(0xFFBA1A1A)
    val OnErrorLight = Color(0xFFFFFFFF)
    val ErrorContainerLight = Color(0xFFFFDAD6)
    val OnErrorContainerLight = Color(0xFF410002)

    // Dark — deep navy with light-blue accents, for the rare caller that opts into dark.
    val PrimaryDark = Color(0xFFB6C5FF)
    val OnPrimaryDark = Color(0xFF002A71)
    val PrimaryContainerDark = Color(0xFF003E9E)
    val OnPrimaryContainerDark = Color(0xFFDCE3FF)
    val SecondaryDark = Color(0xFFBFC6DC)
    val OnSecondaryDark = Color(0xFF293041)
    val SecondaryContainerDark = Color(0xFF3F4659)
    val OnSecondaryContainerDark = Color(0xFFDBE2F9)
    val TertiaryDark = Color(0xFFDEC0E4)
    val OnTertiaryDark = Color(0xFF3F2B47)
    val BackgroundDark = Color(0xFF111318)
    val OnBackgroundDark = Color(0xFFE2E2E9)
    val SurfaceDark = Color(0xFF111318)
    val OnSurfaceDark = Color(0xFFE2E2E9)
    val SurfaceVariantDark = Color(0xFF44464F)
    val OnSurfaceVariantDark = Color(0xFFC5C6D0)
    val OutlineDark = Color(0xFF8E9099)
    val ErrorDark = Color(0xFFFFB4AB)
    val OnErrorDark = Color(0xFF690005)
    val ErrorContainerDark = Color(0xFF93000A)
    val OnErrorContainerDark = Color(0xFFFFDAD6)

    // The brand gradient of the home header: two blues, top to bottom.
    val HeaderGradientStartLight = Color(0xFF2B4BFF)
    val HeaderGradientEndLight = Color(0xFF4A6CF7)
    val HeaderGradientStartDark = Color(0xFF003E9E)
    val HeaderGradientEndDark = Color(0xFF002A71)

    // The four home tiles, each its own two-colour gradient. A tile's colour is identity, not
    // state: it never changes with anything the application does.
    val TileVaultStart = Color(0xFF38B6FF)
    val TileVaultEnd = Color(0xFF2E8EF7)
    val TileAppLockStart = Color(0xFFFF8A5C)
    val TileAppLockEnd = Color(0xFFF4574D)
    val TileHideStart = Color(0xFFD966F0)
    val TileHideEnd = Color(0xFF9B4DEB)
    val TileAllStart = Color(0xFF35D0BA)
    val TileAllEnd = Color(0xFF1FB6D0)
}
