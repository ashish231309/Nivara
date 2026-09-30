package com.nivara.app.ui.navigation

import androidx.annotation.StringRes
import com.nivara.app.R

/**
 * Every screen the user can navigate to.
 *
 * Routes are plain strings: the graph is small, and keeping it dependency-free avoids pulling
 * a serialization runtime into the application for the sake of route arguments. Destinations
 * that need arguments will carry them as explicit route paths when they are added.
 */
sealed interface NivaraDestination {

    /** Stable identifier used in the navigation graph. */
    val route: String

    /** Title shown in the app bar for this destination. */
    @get:StringRes
    val titleRes: Int

    /** Initial screen shown when the app starts. */
    data object Home : NivaraDestination {
        override val route: String = "home"
        override val titleRes: Int = R.string.app_name
    }

    /** Informational screen describing the build and the app's privacy defaults. */
    data object About : NivaraDestination {
        override val route: String = "about"
        override val titleRes: Int = R.string.about_title
    }

    companion object {
        /**
         * All destinations that exist in the graph.
         *
         * Add the new entry here together with its `composable` block in `NivaraNavHost`.
         */
        val entries: List<NivaraDestination> = listOf(Home, About)

        /** Resolves a navigation route back to its destination, or `null` when unknown. */
        fun fromRoute(route: String?): NivaraDestination? = entries.firstOrNull { it.route == route }
    }
}
