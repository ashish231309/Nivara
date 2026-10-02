package com.nivara.app.domain.applock

import com.nivara.app.core.common.NivaraResult

/**
 * Turns App Lock protection on and off for the device, not just for the screen the user is looking
 * at.
 *
 * Detection has to keep working while the user is inside the application being protected, which is
 * exactly when Nivara is not on screen. That is why protection is carried by a platform component
 * with its own lifecycle, and why the screen that offers the switch talks to this contract instead
 * of to that component: what starts and stops protection is a decision, and how it survives
 * Nivara's absence is an implementation detail.
 *
 * The implementation starts and stops the same single background component either way, so calling
 * [start] twice leaves one of it and calling [stop] twice leaves none. Neither call can grant a
 * permission, change a setting or ask the user for anything: protection that cannot run because a
 * prerequisite is missing reports that through detection's own state rather than by quietly doing
 * something else.
 *
 * Both calls report what the platform did rather than assuming it worked. Starting a component can
 * be refused — the platform restricts background starts, and an OEM build may disable the component
 * — and a refusal that is swallowed would leave a switch claiming protection that is not running.
 * A failure here is an honest answer for the screen: the prerequisite is fine, the request was made,
 * and the device did not accept it.
 */
interface AppLockProtectionRunner {

    /** Starts protection. Idempotent. Fails only when the platform refused the start. */
    fun start(): NivaraResult<Unit>

    /** Stops protection. Idempotent, and safe to call when nothing is running. */
    fun stop(): NivaraResult<Unit>
}
