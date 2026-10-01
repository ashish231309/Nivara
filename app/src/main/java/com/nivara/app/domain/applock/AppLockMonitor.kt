package com.nivara.app.domain.applock

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The one component that watches the device on App Lock's behalf.
 *
 * One instance per process, owned by the composition root, started and stopped explicitly. Two
 * monitors would be two answers to the same question and two sets of observations racing each
 * other, so the platform service that drives this in the background drives *this* object rather
 * than creating one of its own.
 *
 * ### What it owns and what it does not
 *
 * It owns the detection loop, the current [state] and the requirement [events]. It does not own
 * anything else: not the session (the gate does), not the protected set (the repository does), not
 * the foreground rule (the decision engine does) and not the way a requirement is presented (a
 * later stage does). Nothing here authenticates anybody, and nothing here locks anything.
 *
 * ### Lifecycle
 *
 * [start] is idempotent — calling it twice leaves exactly one loop running. [stop] cancels the
 * loop and releases what it holds, and state returns to [AppLockState.Stopped]. Starting again
 * after stopping produces one valid monitor and treats the conditions it finds as new, so a
 * requirement that is still true is reported again rather than swallowed.
 */
interface AppLockMonitor {

    /** The current state, always available, from [AppLockState.Stopped] before the first start. */
    val state: StateFlow<AppLockState>

    /**
     * Requirements as they appear, for a presentation layer that reacts to them.
     *
     * The stream is a trigger, not a record: [state] is the authoritative answer, and a consumer
     * that needs to know the current situation reads it rather than waiting for an event. Events
     * are never repeated while a requirement still holds.
     */
    val events: Flow<ProtectionEvent>

    /** Starts detection, or does nothing when it is already running. */
    fun start()

    /** Stops detection and releases the monitoring loop. Safe to call when it is not running. */
    fun stop()
}
