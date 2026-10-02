package com.nivara.app.ui.launcher

import com.nivara.app.R
import com.nivara.app.ui.components.NivaraMessage

/**
 * Copy for the launcher.
 *
 * As everywhere else in Nivara, the wording lives here rather than in the state machine, so the view
 * model deals in states and the screen deals in text. Every message is generic: none of them names a
 * package, a path or a platform exception, and none of them says anything about an application the
 * user cannot already see.
 */

/** Shown when a row the user tapped is no longer installed or has no launcher entry. */
internal fun launcherApplicationGoneMessage(): NivaraMessage =
    NivaraMessage(textRes = R.string.launcher_error_not_installed)

/** Shown when the platform refused to start an application. */
internal fun launcherLaunchRefusedMessage(): NivaraMessage =
    NivaraMessage(textRes = R.string.launcher_error_launch_failed)

/** Shown when the device's applications could not be listed while a list was already on screen. */
internal fun launcherDiscoveryUnavailableMessage(): NivaraMessage =
    NivaraMessage(textRes = R.string.launcher_error_discovery)
