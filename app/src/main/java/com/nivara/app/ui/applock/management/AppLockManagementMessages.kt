package com.nivara.app.ui.applock.management

import com.nivara.app.R
import com.nivara.app.ui.components.NivaraMessage

/**
 * Copy for the App Lock management screen.
 *
 * The wording lives here rather than in the state machine, so the view model deals in states and the
 * screen deals in text, and no string resource identifier ends up in a domain type. Every message
 * below is deliberately generic: none of them names a package, a file, a path or a platform
 * exception, because none of that belongs on a screen — or anywhere else in this feature.
 */

/** Shown when the last change could not be stored. */
internal fun changeNotStoredMessage(): NivaraMessage =
    NivaraMessage(textRes = R.string.applock_manage_error_change_not_stored)

/** Shown when a row was acted on for an application that is no longer installed. */
internal fun applicationNotInstalledMessage(): NivaraMessage =
    NivaraMessage(textRes = R.string.applock_manage_error_not_installed)

/** Shown when a change is attempted while the session gate is closed. */
internal fun lockedMessage(): NivaraMessage =
    NivaraMessage(textRes = R.string.applock_manage_error_locked)

/** Shown when discovery failed while the screen already had a list to show. */
internal fun discoveryUnavailableMessage(): NivaraMessage =
    NivaraMessage(textRes = R.string.applock_manage_error_discovery)

/** Shown when the stored protected set could not be read. Nothing on the screen may then claim anything. */
internal fun protectedSetUnreadableMessage(): NivaraMessage =
    NivaraMessage(textRes = R.string.applock_manage_error_protected_set)
