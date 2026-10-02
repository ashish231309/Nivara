package com.nivara.app.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The spacing tokens of the application.
 *
 * Every screen takes its margins, gaps and paddings from here rather than choosing values of its
 * own, which is what keeps the application reading as one product instead of one folder per
 * screen. The scale is deliberately short: four, eight, twelve, sixteen, twenty-four — enough to
 * separate tight, ordinary and generous without offering an opinion for every pixel.
 */
object NivaraSpacing {

    /** The smallest gap in use: related pieces, like a checkbox and its label. */
    val tight: Dp = 4.dp

    /** The hairline gap: a title and the subtitle that belongs to it. */
    val hairline: Dp = 2.dp

    /** The ordinary small gap: icon to text, rows inside a dense group. */
    val small: Dp = 8.dp

    /** The ordinary gap between rows and between a row's own pieces. */
    val row: Dp = 12.dp

    /** The margin at a screen's edges and the padding inside a card. */
    val screen: Dp = 16.dp

    /** The gap between sections of a screen. */
    val section: Dp = 24.dp
}

/**
 * The sizes the application draws with.
 *
 * Touch targets follow the platform's own minimum: nothing the user is asked to press is smaller
 * than forty-eight density pixels in either direction.
 */
object NivaraSize {

    /** The platform's minimum comfortable touch target. */
    val touchTarget: Dp = 48.dp

    /** The size an application icon is drawn at in rows. */
    val rowIcon: Dp = 40.dp

    /** The size an application icon is drawn at in the launcher grid. */
    val gridIcon: Dp = 56.dp
}
