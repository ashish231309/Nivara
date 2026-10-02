package com.nivara.app.data.session

import com.nivara.app.domain.credential.AuthenticationOutcome
import com.nivara.app.domain.credential.TimeProvider
import com.nivara.app.domain.security.AuthenticationSource
import com.nivara.app.domain.security.BiometricAuthenticationOutcome
import com.nivara.app.domain.security.SessionManager
import com.nivara.app.domain.security.SessionState
import com.nivara.app.domain.security.SessionTimeoutPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The application's session gate, held entirely in memory.
 *
 * There is no file, no preference and no database here — and that is the design, not an
 * unfinished part of it. The session exists only for as long as the process does, so a killed
 * process leaves nothing behind to expire, to clean up or to steal. Two consequences follow, and
 * both are intended:
 *
 * - a recreated process is unauthenticated, whatever the user was doing before;
 * - nothing has to be encrypted and nothing can be replayed, because no session value is ever
 *   written anywhere.
 *
 * ### Two ways the timeout is applied
 *
 * The expiry rule is enforced from both directions, because either one alone has a hole:
 *
 * - **On read** — every [currentState] call checks the deadline first, so no caller can ever act
 *   on a session that has run out, even if the process was frozen, throttled or suspended.
 * - **On a timer** — when a session is established, one job is scheduled for its deadline, so an
 *   observer that is simply *watching* (a screen showing protected content) sees the gate close on
 *   its own instead of waiting for something to ask.
 *
 * The timer never lengthens anything: when it wakes it re-reads the clock through [timeProvider]
 * and ends the session only if the deadline has really passed. A clock that was moved backwards
 * therefore leaves the session alone, and a clock that was moved forwards cannot keep a session
 * alive past a read.
 *
 * ### Concurrency
 *
 * Every transition happens under one lock, and the observable state is a [MutableStateFlow], so a
 * screen that reads the session while the timer or a Quick Lock is ending it sees either the
 * session or its end, never a half-applied change. The manager is created once per process by the
 * composition root, and no screen is ever given a way to write to it.
 */
class InMemorySessionManager(
    private val timeProvider: TimeProvider,
    private val policy: SessionTimeoutPolicy = SessionTimeoutPolicy.Default,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : SessionManager {

    private val lock = Any()

    private val mutableState = MutableStateFlow<SessionState>(SessionState.Unauthenticated)

    override val state: StateFlow<SessionState> = mutableState.asStateFlow()

    /** The timer for the current session, or `null` when there is none. */
    private var expiryJob: Job? = null

    override fun establish(outcome: AuthenticationOutcome): SessionState =
        when (outcome) {
            is AuthenticationOutcome.Succeeded -> start(AuthenticationSource.Primary)
            // A rejection, a refusal while a delay runs, a missing credential or an unusable
            // record: none of these is an authentication, so none of them opens anything.
            is AuthenticationOutcome.Failed,
            is AuthenticationOutcome.TemporarilyBlocked,
            is AuthenticationOutcome.NotConfigured,
            is AuthenticationOutcome.InvalidConfiguration,
            -> currentState()
        }

    override fun establish(outcome: BiometricAuthenticationOutcome): SessionState =
        when (outcome) {
            is BiometricAuthenticationOutcome.Succeeded -> start(AuthenticationSource.Biometric)
            // Cancelling the prompt, failing a match, Nivara's own delay, Android's lockout, an
            // invalidated key, a device that cannot authenticate: all leave the gate as it was.
            is BiometricAuthenticationOutcome.Failed,
            is BiometricAuthenticationOutcome.Cancelled,
            is BiometricAuthenticationOutcome.TemporarilyBlocked,
            is BiometricAuthenticationOutcome.SystemBlocked,
            is BiometricAuthenticationOutcome.NotEnabled,
            is BiometricAuthenticationOutcome.Invalidated,
            is BiometricAuthenticationOutcome.Unavailable,
            -> currentState()
        }

    override fun currentState(): SessionState {
        val now = timeProvider.nowMillis()
        return synchronized(lock) {
            val current = mutableState.value
            // The authoritative read: an expired session ends here, whether or not the timer has
            // already run.
            if (current is SessionState.Authenticated && !current.isValidAt(now)) {
                end()
            } else {
                current
            }
        }
    }

    override fun lockNow(): SessionState = synchronized(lock) {
        end()
    }

    /**
     * Opens a session for [source] and schedules its expiry.
     *
     * A previous session is replaced rather than extended: the new session gets the new deadline,
     * and the old timer is cancelled so it cannot end the session that replaced it.
     */
    private fun start(source: AuthenticationSource): SessionState {
        val now = timeProvider.nowMillis()
        val session = SessionState.Authenticated(
            source = source,
            startedAtMillis = now,
            expiresAtMillis = policy.expiryFor(now),
        )

        return synchronized(lock) {
            expiryJob?.cancel()
            mutableState.value = session
            expiryJob = scheduleExpiry(session)
            session
        }
    }

    /** Ends the current session and cancels its timer. Idempotent. */
    private fun end(): SessionState {
        expiryJob?.cancel()
        expiryJob = null
        mutableState.value = SessionState.Unauthenticated
        return SessionState.Unauthenticated
    }

    /**
     * Schedules the one wake-up that a session gets.
     *
     * `delay` measures elapsed time while the deadline is a wall-clock instant, so the job treats
     * its own wake-up as a hint: it ends the session only if [timeProvider] agrees the deadline has
     * passed. If the clock moved backwards, the session stays valid and the timer is simply not
     * rescheduled — every later read still applies the deadline, so the session cannot outlive it.
     */
    private fun scheduleExpiry(session: SessionState.Authenticated): Job = scope.launch {
        val wait = session.expiresAtMillis - timeProvider.nowMillis()
        if (wait > 0L) {
            delay(wait)
        }
        synchronized(lock) {
            if (mutableState.value == session && !session.isValidAt(timeProvider.nowMillis())) {
                end()
            }
        }
    }
}
