package com.nivara.app.ui.components

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlin.math.roundToInt

/**
 * The motion language of the application.
 *
 * Motion here is short and purposeful: it tells the user where a screen came from, that a change
 * landed, or that a section opened — it never decorates for its own sake and it never delays an
 * action. Three durations cover everything the application does, and every one of them is scaled
 * by the device's own animator setting, so a person who has asked the platform for less motion
 * gets less motion from Nivara too.
 *
 * Nothing about security is carried by motion: a state that changed is already changed, and an
 * animation finishing is never what makes it true.
 */
object NivaraMotion {

    /** No motion at all: what remains when the platform's animator scale is zero. */
    const val INSTANT_MILLIS: Int = 0

    /** The quick beat: pressed feedback, a small section appearing. */
    const val QUICK_MILLIS: Int = 150

    /** The ordinary beat: screens arriving, content replacing progress. */
    const val STANDARD_MILLIS: Int = 250

    /** The generous beat: the largest surface the application moves. */
    const val EMPHASIZED_MILLIS: Int = 350
}

/**
 * The device's animator duration scale, as the user set it.
 *
 * `0f` means the user asked the platform for no animation; anything unreadable falls back to the
 * ordinary `1f`, because failing to read a preference must never remove the experience of a
 * device that animates.
 */
fun animatorScale(context: Context): Float = try {
    Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ).coerceAtLeast(0f)
} catch (ignored: Exception) {
    1f
}

/**
 * Applies the device's animator scale to a base duration.
 *
 * A scale of zero yields zero — the animation resolves to its end state immediately rather than
 * being a different, shorter animation. Any other scale shortens or lengthens proportionally,
 * and a positive base never rounds down to nothing.
 */
fun scaledDurationMillis(baseMillis: Int, animatorScale: Float): Int {
    require(baseMillis >= 0) { "a duration is never negative" }
    if (baseMillis == 0 || animatorScale <= 0f) return NivaraMotion.INSTANT_MILLIS
    return (baseMillis * animatorScale).roundToInt().coerceAtLeast(1)
}

/**
 * The animator scale of the device this composition is running on, remembered for the lifetime
 * of the composition.
 *
 * This is the only way screens and transitions should ask for motion: one read, one place, and
 * the platform's accessibility setting respected wherever the value is applied.
 */
@Composable
fun rememberNivaraMotionScale(): Float {
    val context = LocalContext.current
    return remember(context) { animatorScale(context) }
}
