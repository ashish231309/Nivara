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

    /** First-time credential enrollment. */
    data object CredentialSetup : NivaraDestination {
        override val route: String = "credential-setup"
        override val titleRes: Int = R.string.credential_setup_title
    }

    /** Verifying the configured credential. */
    data object CredentialVerify : NivaraDestination {
        override val route: String = "credential-verify"
        override val titleRes: Int = R.string.credential_verify_title
    }

    /** Replacing the configured credential after authenticating. */
    data object CredentialChange : NivaraDestination {
        override val route: String = "credential-change"
        override val titleRes: Int = R.string.credential_change_title
    }

    /** Biometric unlock: status, enable, disable and authentication. */
    data object Biometric : NivaraDestination {
        override val route: String = "biometric"
        override val titleRes: Int = R.string.biometric_screen_title
    }

    /**
     * App Lock preparation: what Nivara can see on the device, which capabilities it holds, and
     * whether protection is running.
     */
    data object AppLockSetup : NivaraDestination {
        override val route: String = "applock-setup"
        override val titleRes: Int = R.string.applock_setup_title
    }

    /**
     * App Lock itself: the applications that are protected, and the controls that change that.
     *
     * Preparation is a prerequisite screen rather than the feature, so the entry point the home
     * screen offers leads here; preparation is reached from this screen when something is missing.
     */
    data object AppLock : NivaraDestination {
        override val route: String = "applock"
        override val titleRes: Int = R.string.applock_manage_title
    }

    /**
     * Hidden applications: the applications Nivara is asked to keep out of sight, and the controls
     * that change that.
     *
     * A destination of its own rather than a second control on the App Lock list, because the two
     * are separate dimensions: an application can be protected, hidden, both or neither, and a
     * screen that offered both would invite the reader to assume one implies the other.
     */
    data object HiddenApps : NivaraDestination {
        override val route: String = "apphide"
        override val titleRes: Int = R.string.apphide_manage_title
    }

    companion object {
        /**
         * All destinations that exist in the graph.
         *
         * Add the new entry here together with its `composable` block in `NivaraNavHost`.
         */
        val entries: List<NivaraDestination> =
            listOf(
                Home,
                About,
                CredentialSetup,
                CredentialVerify,
                CredentialChange,
                Biometric,
                AppLockSetup,
                AppLock,
                HiddenApps,
            )

        /** Resolves a navigation route back to its destination, or `null` when unknown. */
        fun fromRoute(route: String?): NivaraDestination? = entries.firstOrNull { it.route == route }
    }
}
