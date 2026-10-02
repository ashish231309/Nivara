package com.nivara.app.ui.camouflage

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.nivara.app.R
import com.nivara.app.domain.camouflage.CamouflageProfile

/**
 * How each identity is drawn.
 *
 * The mapping lives in the presentation layer because it is presentation: a resource identifier is
 * not a fact about an identity, it is the name of the picture and the words this build uses for it.
 * Keeping it here also keeps the domain free of Android resource types, which is the rule every
 * domain package follows.
 *
 * The icon is drawn as the foreground vector over the identity's background colour rather than as
 * the adaptive icon resource: an `adaptive-icon` is a container the platform composes for a
 * launcher, and it cannot be rendered by a screen. Each foreground is a white glyph on a coloured
 * circle, which is also what the launcher will compose, so the preview and the home screen agree.
 */
@StringRes
internal fun CamouflageProfile.labelRes(): Int = when (this) {
    CamouflageProfile.Nivara -> R.string.app_name
    CamouflageProfile.Notes -> R.string.camouflage_profile_notes_label
    CamouflageProfile.Calculator -> R.string.camouflage_profile_calculator_label
    CamouflageProfile.Weather -> R.string.camouflage_profile_weather_label
}

/** The glyph this identity's launcher icon draws. */
@DrawableRes
internal fun CamouflageProfile.foregroundRes(): Int = when (this) {
    CamouflageProfile.Nivara -> R.drawable.ic_launcher_foreground
    CamouflageProfile.Notes -> R.drawable.ic_camouflage_notes
    CamouflageProfile.Calculator -> R.drawable.ic_camouflage_calculator
    CamouflageProfile.Weather -> R.drawable.ic_camouflage_weather
}

/** The colour behind the glyph, matching the adaptive icon the launcher composes. */
@ColorRes
internal fun CamouflageProfile.backgroundRes(): Int = when (this) {
    CamouflageProfile.Nivara -> R.color.ic_launcher_background
    CamouflageProfile.Notes -> R.color.ic_camouflage_notes_background
    CamouflageProfile.Calculator -> R.color.ic_camouflage_calculator_background
    CamouflageProfile.Weather -> R.color.ic_camouflage_weather_background
}
