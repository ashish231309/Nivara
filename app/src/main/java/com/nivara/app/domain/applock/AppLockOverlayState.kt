package com.nivara.app.domain.applock

import com.nivara.app.domain.credential.AuthenticationOutcome
import com.nivara.app.domain.credential.PrimaryCredentialType
import com.nivara.app.domain.security.BiometricAuthenticationOutcome
import com.nivara.app.domain.security.BiometricStatus

/**
 * What the App Lock protection surface is doing.
 *
 * This is the whole state model, and it is deliberately small: one value says whether anything needs
 * to be on screen, one says what it is waiting for, and one says why it cannot be shown. The
 * *physical* lifecycle of the window — attached, being removed, gone — belongs to the component that
 * owns the window, not here, because nothing above this layer may assume a window exists.
 *
 * The states are published by [AppLockOverlayPresenter] and read by two consumers: the component
 * that attaches the window (which acts on [Required] and [Unpresentable]) and the surface's own
 * content (which renders [Required]).
 */
sealed interface AppLockOverlayState {

    /** Nothing to protect, or nothing detected — no surface should be on screen. */
    data object Idle : AppLockOverlayState

    /**
     * A protected application needs authentication, and the surface should be visible.
     *
     * @param request the occasion this state belongs to. Everything the surface does is bound to
     *   this value, so a result that arrives for a superseded request is ignored rather than acted
     *   on.
     * @param phase whether the surface is waiting for an attempt or running one.
     * @param credential which method the stored configuration says is active, or `null` when no
     *   credential is configured — in which case the surface offers no entry field at all rather
     *   than a field that could never succeed.
     * @param biometric what the secondary path currently offers. [BiometricStatus.Disabled] and the
     *   unavailable cases mean the surface shows the primary path alone; they never mean "less
     *   protection".
     * @param lastAttempt the most recent attempt, kept so the surface can explain what happened.
     *   Cleared when a new attempt starts.
     */
    data class Required(
        val request: ProtectionRequest,
        val phase: AppLockPhase,
        val credential: PrimaryCredentialType?,
        val biometric: BiometricStatus,
        val lastAttempt: ProtectionAttemptOutcome? = null,
    ) : AppLockOverlayState

    /**
     * A protected application needs authentication, but the surface cannot be shown.
     *
     * Detection did its work and the requirement is real; only the presentation is missing. The
     * state exists so that this situation is a visible fact rather than a silent failure — the App
     * Lock preparation screen reports the missing prerequisite, and Nivara never behaves as though
     * the application were protected when it cannot show the requirement.
     */
    data class Unpresentable(
        val request: ProtectionRequest,
        val reason: OverlayUnavailability,
    ) : AppLockOverlayState
}

/** How far the surface has got with one requirement. */
enum class AppLockPhase {

    /** Waiting for the user to attempt authentication. */
    AwaitingCredential,

    /** An attempt is running. A second one is refused while this holds. */
    Authenticating,
}

/**
 * The outcome of one attempt, as the surface needs to describe it.
 *
 * Only failures and cancellations are interesting — a success removes the surface — but both
 * authentication paths report a success through the same route, and modelling the success keeps the
 * two consumers honest: the surface renders whatever this says, and the presenter never has to
 * explain a state it is not in.
 *
 * The wrapped values are the existing domain outcomes of the primary and biometric paths. Wrapping
 * rather than copying is what stops a third vocabulary of failures from appearing, and it is what
 * lets the surface reuse the same message mapping the credential and biometric screens use.
 */
sealed interface ProtectionAttemptOutcome {

    /** The primary credential was attempted, and was not accepted. */
    data class Credential(val outcome: AuthenticationOutcome) : ProtectionAttemptOutcome

    /** The platform biometric prompt was attempted, and did not open anything. */
    data class Biometric(val outcome: BiometricAuthenticationOutcome) : ProtectionAttemptOutcome
}
