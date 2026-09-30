package com.nivara.app.domain.credential

/**
 * Tracks rejected authentication attempts for the primary credential.
 *
 * The tracker is the foundation the later lockout policy builds on: it counts consecutive
 * rejections, applies the delay from a [ThrottlePolicy], and clears everything after a
 * successful authentication. It knows nothing about biometrics, sessions or app locking.
 *
 * Rate limiting is deliberately *not* a source of truth for security decisions. Implementations
 * therefore never fail an operation because their storage misbehaved: a read problem starts from
 * a clean state rather than locking the user out of their own device, and a write problem is
 * recorded in the state it returns. Nothing else may depend on the counter being intact.
 */
interface AttemptTracker {

    /** Consecutive failures and the end of the current delay window. */
    suspend fun currentState(): ThrottleState

    /** Milliseconds until the next attempt is allowed, or `0` when it is allowed now. */
    suspend fun remainingBlockMillis(): Long

    /**
     * Records a rejected attempt and returns the resulting state.
     *
     * The failure counter only ever grows here; it is reset by [recordSuccess].
     */
    suspend fun recordFailure(): ThrottleState

    /** Clears the failure counter and the delay window after a successful authentication. */
    suspend fun recordSuccess()
}
