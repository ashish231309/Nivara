package com.nivara.app.ui.apphide.management

import com.nivara.app.R
import com.nivara.app.ui.components.NivaraMessage

/**
 * Copy for the hidden-application management screen.
 *
 * The wording lives here rather than in the state machine, so the view model deals in states and the
 * screen deals in text, and no string resource identifier ends up in a domain type. Every message
 * below is deliberately generic: none of them names a package, a file, a path or a platform
 * exception, because none of that belongs on a screen — or anywhere else in this feature.
 */

/** Shown when the last change could not be stored. */
internal fun hiddenChangeNotStoredMessage(): NivaraMessage =
    NivaraMessage(textRes = R.string.apphide_manage_error_change_not_stored)

/** Shown when a row was acted on for an application that is no longer installed. */
internal fun hiddenApplicationNotInstalledMessage(): NivaraMessage =
    NivaraMessage(textRes = R.string.apphide_manage_error_not_installed)

/** Shown when a change is attempted while the session gate is closed. */
internal fun hiddenLockedMessage(): NivaraMessage =
    NivaraMessage(textRes = R.string.apphide_manage_error_locked)

/** Shown when discovery failed while the screen already had a list to show. */
internal fun hiddenDiscoveryUnavailableMessage(): NivaraMessage =
    NivaraMessage(textRes = R.string.apphide_manage_error_discovery)

/**
 * Shown when a change is refused because the stored set exists but cannot be decoded.
 *
 * The sentence says what happened and what did not: nothing was changed, and nothing was removed —
 * because the one thing this screen must never do is turn damaged configuration into an empty set.
 */
internal fun hiddenStateUnreadableMessage(): NivaraMessage =
    NivaraMessage(textRes = R.string.apphide_manage_error_hidden_unreadable)

/** Shown when a change is refused because the stored set could not be reached at all. */
internal fun hiddenStateUnavailableMessage(): NivaraMessage =
    NivaraMessage(textRes = R.string.apphide_manage_error_hidden_unavailable)
