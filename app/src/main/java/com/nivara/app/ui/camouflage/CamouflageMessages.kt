package com.nivara.app.ui.camouflage

import com.nivara.app.R
import com.nivara.app.ui.components.NivaraMessage

/**
 * Copy for the application-identity screen.
 *
 * As everywhere else in Nivara, the wording lives here rather than in the state machine, so the view
 * model deals in states and the screen deals in text. Every message is generic: none of them names a
 * component, a package or a platform exception, and none of them promises that anything became
 * invisible — the identity is presentation, and the messages say so.
 */

/**
 * Shown when the user asked for a change while the gate was closed.
 *
 * The same sentence the screen shows while it is locked: the refusal and the reason are one thing,
 * and two copies of it would be two chances to drift apart.
 */
internal fun camouflageLockedMessage(): NivaraMessage =
    NivaraMessage(textRes = R.string.camouflage_locked)

/** Shown when the platform refused the change. The device keeps whatever it was showing. */
internal fun camouflageChangeRefusedMessage(): NivaraMessage =
    NivaraMessage(textRes = R.string.camouflage_error_not_changed)
