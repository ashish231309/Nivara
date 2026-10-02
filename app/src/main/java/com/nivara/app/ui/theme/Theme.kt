package com.nivara.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = NivaraColors.PrimaryLight,
    onPrimary = NivaraColors.OnPrimaryLight,
    primaryContainer = NivaraColors.PrimaryContainerLight,
    onPrimaryContainer = NivaraColors.OnPrimaryContainerLight,
    secondary = NivaraColors.SecondaryLight,
    onSecondary = NivaraColors.OnSecondaryLight,
    secondaryContainer = NivaraColors.SecondaryContainerLight,
    onSecondaryContainer = NivaraColors.OnSecondaryContainerLight,
    tertiary = NivaraColors.TertiaryLight,
    onTertiary = NivaraColors.OnTertiaryLight,
    background = NivaraColors.BackgroundLight,
    onBackground = NivaraColors.OnBackgroundLight,
    surface = NivaraColors.SurfaceLight,
    onSurface = NivaraColors.OnSurfaceLight,
    surfaceVariant = NivaraColors.SurfaceVariantLight,
    onSurfaceVariant = NivaraColors.OnSurfaceVariantLight,
    outline = NivaraColors.OutlineLight,
    error = NivaraColors.ErrorLight,
    onError = NivaraColors.OnErrorLight,
    errorContainer = NivaraColors.ErrorContainerLight,
    onErrorContainer = NivaraColors.OnErrorContainerLight,
)

private val DarkColorScheme = darkColorScheme(
    primary = NivaraColors.PrimaryDark,
    onPrimary = NivaraColors.OnPrimaryDark,
    primaryContainer = NivaraColors.PrimaryContainerDark,
    onPrimaryContainer = NivaraColors.OnPrimaryContainerDark,
    secondary = NivaraColors.SecondaryDark,
    onSecondary = NivaraColors.OnSecondaryDark,
    secondaryContainer = NivaraColors.SecondaryContainerDark,
    onSecondaryContainer = NivaraColors.OnSecondaryContainerDark,
    tertiary = NivaraColors.TertiaryDark,
    onTertiary = NivaraColors.OnTertiaryDark,
    background = NivaraColors.BackgroundDark,
    onBackground = NivaraColors.OnBackgroundDark,
    surface = NivaraColors.SurfaceDark,
    onSurface = NivaraColors.OnSurfaceDark,
    surfaceVariant = NivaraColors.SurfaceVariantDark,
    onSurfaceVariant = NivaraColors.OnSurfaceVariantDark,
    outline = NivaraColors.OutlineDark,
    error = NivaraColors.ErrorDark,
    onError = NivaraColors.OnErrorDark,
    errorContainer = NivaraColors.ErrorContainerDark,
    onErrorContainer = NivaraColors.OnErrorContainerDark,
)

/**
 * Material 3 theme for Nivara.
 *
 * The product's visual language is the light white-and-blue scheme: it is what every entry
 * point uses by default, regardless of the system's light/dark setting, because a bright,
 * uncluttered surface is what makes the application readable at a glance. [darkTheme] remains
 * for the few surfaces that may deliberately opt into the navy variant; nothing follows the
 * system setting on its own any more.
 *
 * The colour scheme, the type scale and the shape family are the application's one visual
 * language: every screen takes its colours, text styles and corner radii from here, and motion
 * is scaled by the device's own animator setting wherever it appears.
 */
@Composable
fun NivaraTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = NivaraTypography,
        shapes = NivaraShapes,
        content = content,
    )
}

/** The two brand blues a header gradient is drawn with, for the current scheme. */
@Composable
fun nivaraHeaderGradientColors(darkTheme: Boolean = false): Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color> =
    if (darkTheme) {
        NivaraColors.HeaderGradientStartDark to NivaraColors.HeaderGradientEndDark
    } else {
        NivaraColors.HeaderGradientStartLight to NivaraColors.HeaderGradientEndLight
    }
