package com.nivara.app.domain.security

import com.nivara.app.domain.credential.AuthenticationOutcome
import kotlinx.coroutines.flow.StateFlow

/**
 * The one place a Nivara session is created, read or ended.
 *
 * ```
 * primary credential success ─┐
 *                             ├─→ SessionManager ─→ SessionState.Authenticated
 * biometric success ──────────┘
 * ```
 *
 * Every protected feature — App Lock, the vault, hidden apps, and whatever follows — asks this
 * gate before it shows anything. Authentication itself stays where it already lives: this
 * interface never derives a key, compares a verifier, prompts for a biometric or counts a failure.
 * It receives the outcome those layers already produced, and decides only whether that outcome
 * opens a session.
 *
 * ### What the session is not
 *
 * The state is memory-only. There is no file, no preference and no database behind it, so an
 * application process that ends takes the session with it: a restarted process is unauthenticated
 * with nothing to clean up. Nothing in [SessionState] is a credential, a key or a token, and no
 * secret is ever handed to this interface.
 *
 * ### What it must never do
 *
 * It does not touch the primary credential, the biometric key or either failure counter. Locking a
 * session (or letting one expire) is not an authentication failure: the credential keeps working,
 * biometrics keep working, and neither throttling policy is reset or advanced by anything here.
 */
interface SessionManager {

    /**
     * The current session, observable by the UI.
     *
     * The value is always a state the gate would return from [currentState], including after a
     * timeout: when a session expires, the gate publishes [SessionState.Unauthenticated] itself
     * rather than waiting to be asked, so a screen can never be left showing protected content for
     * a session that has already ended.
     */
    val state: StateFlow<SessionState>

    /**
     * Applies a finished primary-credential authentication to the gate.
     *
     * A session is established **only** for [AuthenticationOutcome.Succeeded]. Every other outcome
     * — a rejected credential, a refusal while a delay is running, a missing or unusable stored
     * configuration — leaves the gate exactly as it was, so "the authentication failed" can never
     * accidentally mean "the application is open".
     *
     * @return the gate's state after the call.
     */
    fun establish(outcome: AuthenticationOutcome): SessionState

    /**
     * Applies a finished biometric authentication to the gate.
     *
     * As above, only [BiometricAuthenticationOutcome.Succeeded] establishes a session. A
     * cancellation, a rejected biometric, Nivara's own delay and Android's lockout all leave the
     * session untouched — none of them is an authentication, and none of them can open anything.
     *
     * The session records [AuthenticationSource.Biometric], which says how it was opened and
     * nothing else: the primary credential remains the application's authoritative credential and
     * is still the fallback whenever biometrics cannot answer.
     *
     * @return the gate's state after the call.
     */
    fun establish(outcome: BiometricAuthenticationOutcome): SessionState

    /**
     * The authoritative read: the current session, with the timeout rule already applied.
     *
     * An expired session is ended as part of this call, so a caller can never observe a session
     * that has run out. This is the method a protected feature should use to decide whether it may
     * show anything.
     */
    fun currentState(): SessionState

    /**
     * Whether a session is currently valid.
     *
     * The convenience form of [currentState] for gates that only need yes or no. `false` means
     * "authenticate first", never "locked out": the primary credential and biometrics are both
     * untouched, and a fresh authentication opens a new session immediately.
     */
    fun isAuthenticated(): Boolean = currentState().isAuthenticated

    /**
     * Ends the session immediately — the Quick Lock operation.
     *
     * This is the one canonical way to lock Nivara. It discards the in-memory session, so nothing
     * that authorized protected content survives the call, and the next sensitive action requires a
     * fresh authentication. It is idempotent: locking an already-locked application is not an error
     * and changes nothing.
     *
     * It does **not** delete the credential, disable biometric unlock, remove the biometric key, or
     * record a failure anywhere. Locking is not punishment for anything; it is simply the end of an
     * authorization. Later features (App Lock, the vault) call this same method rather than
     * inventing their own lock.
     *
     * @return the gate's state after the call, always [SessionState.Unauthenticated].
     */
    fun lockNow(): SessionState
}
