package com.nivara.app.domain.applock

/**
 * What App Lock detection is doing, and what it currently concludes.
 *
 * The three cases are the whole answer a presentation layer needs:
 *
 * ```
 *   Stopped     detection is not running
 *   Unavailable detection cannot run — a prerequisite is missing or the platform will not answer
 *   Monitoring  detection is running; here is the foreground application and the decision
 * ```
 *
 * [Unavailable] is not a quiet [Stopped] and not an empty [Monitoring]. It is the state that exists
 * because "Nivara may not look" must never be presented as "nothing needs protecting" — a screen
 * that shows protection as unnecessary while detection is blind would be telling the user something
 * Nivara does not know.
 *
 * The state carries no session, no credential and no history. It is replaced, not edited, on every
 * observation.
 */
sealed interface AppLockState {

    /** Detection is not running. No decision is being made and no observation is being taken. */
    data object Stopped : AppLockState

    /**
     * Detection is running but cannot decide anything.
     *
     * @param reason what is missing or unavailable. Nothing in this state says whether a protected
     *   application is in the foreground, because that is precisely what Nivara cannot tell.
     */
    data class Unavailable(val reason: DetectionUnavailability) : AppLockState

    /**
     * Detection is running and looking at the device.
     *
     * @param foreground the application in the foreground, or `null` when nothing is known to be
     *   there yet. A missing answer is not a claim that everything is safe; it is the absence of an
     *   observation, and the decision below is derived from it.
     * @param decision whether the application in the foreground needs authentication.
     */
    data class Monitoring(
        val foreground: ForegroundApplication?,
        val decision: ProtectionDecision,
    ) : AppLockState
}
