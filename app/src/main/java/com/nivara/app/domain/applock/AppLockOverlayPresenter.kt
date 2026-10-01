package com.nivara.app.domain.applock

import com.nivara.app.core.common.valueOrNull
import com.nivara.app.domain.credential.AuthenticationOutcome
import com.nivara.app.domain.credential.CredentialInput
import com.nivara.app.domain.credential.CredentialManager
import com.nivara.app.domain.credential.CredentialStatus
import com.nivara.app.domain.credential.PrimaryCredentialType
import com.nivara.app.domain.permissions.OverlayCapability
import com.nivara.app.domain.permissions.OverlayCapabilityRepository
import com.nivara.app.domain.security.BiometricAuthenticationOutcome
import com.nivara.app.domain.security.BiometricAuthenticator
import com.nivara.app.domain.security.BiometricStatus
import com.nivara.app.domain.security.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Decides whether the App Lock protection surface should be on screen, and routes authentication
 * through the layers that already own it.
 *
 * ### What it is
 *
 * The presentation half of App Lock. Detection (the monitor) answers "which application is in
 * front, and does it need authentication?"; this class answers "so what should be shown, and what
 * happened when the user tried?". It is the surface's state holder, because the surface has no view
 * model: it is not a screen, it has no navigation, and it exists for as long as a requirement does.
 * Everything it does is Android-free, which is what lets the rules below — request identity, session
 * ownership, de-duplication — be tested without a device.
 *
 * ### One way in, one decision
 *
 * Every wake-up (a requirement from the monitor, a change in detection's conclusion, a change in the
 * session) calls the same [reconcile], and [reconcile] decides from the *current* answers of the
 * monitor and the session gate. A wake-up that arrives twice therefore cannot produce two surfaces,
 * because the second one reaches the same conclusion: the same requirement for the same application
 * is already [AppLockOverlayState.Required] and nothing changes. That is the de-duplication
 * mechanism, and it is a property of the reconciliation rather than a set of flags guarding show
 * calls.
 *
 * ### What it refuses to do
 *
 * It never authenticates by itself: the primary credential is verified by [CredentialManager] and
 * biometrics by [BiometricAuthenticator], and the only thing this class does with either answer is
 * hand it to [SessionManager] and read what the gate says. It holds no unlocked flag, no per-app
 * state, no failure counter and no timer — the session is the only authority and it lives elsewhere.
 * It cannot launch an activity, show a prompt or draw anything: those are the window owner's job.
 *
 * ### Request identity
 *
 * An authentication result is used only if the request it was started for is still the current one.
 * A result for a superseded request is dropped, so an authentication that finished after the user
 * moved to another protected application cannot open the gate for a situation nobody asked about.
 * The session that a *used* success opens is the ordinary global session — App Lock has no
 * per-application unlock and never extends one.
 */
class AppLockOverlayPresenter(
    private val monitor: AppLockMonitor,
    private val sessionManager: SessionManager,
    private val credentialManager: CredentialManager,
    private val biometrics: BiometricAuthenticator,
    private val overlayCapability: OverlayCapabilityRepository,
    private val scope: CoroutineScope,
) {

    private val mutableState = MutableStateFlow<AppLockOverlayState>(AppLockOverlayState.Idle)

    /** What should be on screen. Read by the window owner and by the surface content. */
    val state: StateFlow<AppLockOverlayState> = mutableState.asStateFlow()

    /**
     * Guards the lifecycle and every publication a run makes.
     *
     * A collector that outlives its own run — cancellation is cooperative, and a capability check
     * may be in flight when a stop arrives — must not publish a decision after the surface has been
     * released. Each run carries the generation it started with, and only the current generation
     * may write.
     */
    private val lifecycleLock = Any()

    private var generation: Long = 0L

    /** The collectors started by [start], or `null` when the presenter is not running. */
    private var collectors: Job? = null

    /**
     * Serialises reconciliation and attempts, so two wake-ups cannot interleave into two
     * contradictory states and an attempt cannot be overtaken by a reconciliation that reads
     * half-updated state.
     */
    private val reconcileLock = Mutex()

    /** The next request identity. Monotonic, in memory, never persisted. */
    private var nextRequestId: Long = 0L

    /**
     * Starts following detection and the session.
     *
     * Idempotent: a second call while the presenter is running changes nothing.
     */
    fun start() {
        synchronized(lifecycleLock) {
            if (collectors?.isActive == true) return
            generation += 1
            val runGeneration = generation
            collectors = scope.launch {
                // Three wake-ups, one decision. The requirement stream is the signal Stage 7 exists
                // to raise; the state stream is the authoritative situation, and covers every other
                // way the situation can change (the user left the application, the prerequisite
                // disappeared, detection stopped); the session stream covers expiry and Quick Lock.
                launch { monitor.events.collect { reconcile(runGeneration) } }
                launch { monitor.state.collect { reconcile(runGeneration) } }
                launch { sessionManager.state.collect { reconcile(runGeneration) } }
                // A requirement may already be current when the surface starts: detection does not
                // wait for a fresh event, and neither does this.
                reconcile(runGeneration)
            }
        }
    }

    /**
     * Stops following, and releases the surface state.
     *
     * Safe to call without a matching [start], and safe to call twice. No later publication from a
     * run started before this call can be observed.
     */
    fun stop() {
        synchronized(lifecycleLock) {
            generation += 1
            collectors?.cancel()
            collectors = null
            mutableState.value = AppLockOverlayState.Idle
        }
    }

    /**
     * Attempts the primary credential for [requestId].
     *
     * The input is owned by the credential layer the moment it is handed over, and is cleared on
     * every path — including the paths that refuse the attempt, where nothing else would have
     * touched it. A rejection changes nothing about the session and is reported back through
     * [state]; the failure counting stays inside [CredentialManager], where it already lives.
     */
    fun submitCredential(requestId: Long, input: CredentialInput) {
        val runGeneration = synchronized(lifecycleLock) { generation }
        scope.launch {
            reconcileLock.withLock {
                val current = startableAttemptFor(requestId)
                if (current == null) {
                    input.clear()
                    return@withLock
                }
                publish(runGeneration, current.copy(phase = AppLockPhase.Authenticating, lastAttempt = null))

                val outcome = credentialManager.verify(input)

                // The request may have been superseded while the credential was being derived. A
                // result for a request that is no longer current opens nothing.
                val latest = currentRequiredFor(requestId) ?: return@withLock
                sessionManager.establish(outcome)
                publish(
                    runGeneration,
                    if (outcome is AuthenticationOutcome.Succeeded) {
                        AppLockOverlayState.Idle
                    } else {
                        latest.copy(
                            phase = AppLockPhase.AwaitingCredential,
                            lastAttempt = ProtectionAttemptOutcome.Credential(outcome),
                        )
                    },
                )
            }
        }
    }

    /**
     * Runs one biometric authentication for [requestId].
     *
     * The prompt, the matching and the platform lockout all belong to Android, through the existing
     * authenticator; this call adds no policy of its own. A success reaches the same gate a primary
     * success does, and every other outcome leaves the session exactly as it was and keeps the
     * primary credential available.
     */
    fun authenticateWithBiometric(requestId: Long) {
        val runGeneration = synchronized(lifecycleLock) { generation }
        scope.launch {
            reconcileLock.withLock {
                val current = startableAttemptFor(requestId) ?: return@withLock
                publish(runGeneration, current.copy(phase = AppLockPhase.Authenticating, lastAttempt = null))

                val outcome = biometrics.authenticate()

                val latest = currentRequiredFor(requestId) ?: return@withLock
                sessionManager.establish(outcome)
                publish(
                    runGeneration,
                    if (outcome is BiometricAuthenticationOutcome.Succeeded) {
                        AppLockOverlayState.Idle
                    } else {
                        latest.copy(
                            phase = AppLockPhase.AwaitingCredential,
                            lastAttempt = ProtectionAttemptOutcome.Biometric(outcome),
                        )
                    },
                )
            }
        }
    }

    /**
     * Reports that the window could not be attached for [requestId].
     *
     * Called by the component that owns the window, once per failed attempt. The requirement stays
     * real and is published as [AppLockOverlayState.Unpresentable] with [OverlayUnavailability.Failed]
     * — the one thing that must never happen is this call being swallowed and the user believing
     * their application is protected.
     */
    fun onSurfaceFailed(requestId: Long) {
        synchronized(lifecycleLock) {
            val current = mutableState.value
            val request = current.pendingRequest() ?: return
            if (request.id != requestId) return
            if (current is AppLockOverlayState.Unpresentable) return
            mutableState.value = AppLockOverlayState.Unpresentable(request, OverlayUnavailability.Failed)
        }
    }

    // ------------------------------------------------------------------ reconciliation

    /**
     * Brings the published state in line with the current situation.
     *
     * This is the only place that decides, and it always decides from fresh answers: the monitor's
     * latest conclusion and the session gate's latest state. Nothing is remembered from a previous
     * turn except the request currently on screen, which exists so that a superseded request can be
     * recognised.
     */
    private suspend fun reconcile(runGeneration: Long) {
        reconcileLock.withLock {
            // A valid session covers every requirement: nothing belongs on screen, and nothing new
            // is created. The gate applies its own timeout on read, so an expired session or a
            // Quick Lock is already visible here.
            if (sessionManager.currentState().isAuthenticated) {
                publish(runGeneration, AppLockOverlayState.Idle)
                return@withLock
            }

            when (val monitored = monitor.state.value) {
                // Protection itself is off, so there is nothing to present.
                AppLockState.Stopped -> publish(runGeneration, AppLockOverlayState.Idle)

                is AppLockState.Monitoring -> {
                    val required = monitored.decision as? ProtectionDecision.AuthenticationRequired
                    if (required == null) {
                        // A real observation that needs nothing: the user left the protected
                        // application, Nivara itself came forward, or the application stopped being
                        // protected.
                        publish(runGeneration, AppLockOverlayState.Idle)
                    } else {
                        present(runGeneration, required.application)
                    }
                }

                // Detection is blind, so no observation was made and no decision is made here
                // either. What is deliberately *not* done is the tempting thing: a surface that is
                // already up stays up, because removing it would turn "Nivara cannot see" into
                // "everything is fine" — the one reading this stage must never produce — and no
                // surface is created, because Nivara cannot name an application it cannot see.
                is AppLockState.Unavailable -> Unit
            }
        }
    }

    /**
     * Publishes [AppLockOverlayState.Required] for [application], or the reason it cannot be shown.
     *
     * A requirement for the application already on screen changes nothing — that is the whole of
     * the de-duplication, and it is why repeated events, repeated state emissions and repeated
     * lifecycle callbacks cannot produce a second surface.
     */
    private suspend fun present(runGeneration: Long, application: ProtectedApplication) {
        val current = mutableState.value
        val existing = current.pendingRequest()

        if (existing != null && existing.application == application) {
            // The same occasion. Only one thing can change: a requirement that could not be shown
            // may have become showable, because the user granted the capability in Android's
            // settings. Asking again costs one capability read and keeps a granted permission from
            // requiring a restart.
            if (current is AppLockOverlayState.Unpresentable &&
                overlayCapability.status() == OverlayCapability.Granted
            ) {
                publish(runGeneration, requiredState(existing, currentCredential(), currentBiometric()))
            }
            return
        }

        // A new occasion: this application has just come forward, or a different protected
        // application has replaced it on screen.
        val request = ProtectionRequest(id = nextRequestId + 1, application = application)
        nextRequestId += 1

        val capability = overlayCapability.status()
        publish(
            runGeneration,
            if (capability == OverlayCapability.Granted) {
                requiredState(request, currentCredential(), currentBiometric())
            } else {
                AppLockOverlayState.Unpresentable(request, capability.toUnavailability())
            },
        )
    }

    /** The state of [requestId], or `null` when it is not the request on screen. */
    private fun currentRequiredFor(requestId: Long): AppLockOverlayState.Required? {
        val current = mutableState.value as? AppLockOverlayState.Required ?: return null
        return if (current.request.id == requestId) current else null
    }

    /**
     * The state of [requestId] if an attempt may start for it.
     *
     * `null` covers both "this request is no longer current" and "an attempt is already running":
     * one attempt at a time, so a second tap cannot run two verifications for the same request.
     */
    private fun startableAttemptFor(requestId: Long): AppLockOverlayState.Required? {
        val current = currentRequiredFor(requestId) ?: return null
        return if (current.phase == AppLockPhase.Authenticating) null else current
    }

    private fun requiredState(
        request: ProtectionRequest,
        credential: PrimaryCredentialType?,
        biometric: BiometricStatus,
    ): AppLockOverlayState.Required = AppLockOverlayState.Required(
        request = request,
        phase = AppLockPhase.AwaitingCredential,
        credential = credential,
        biometric = biometric,
        lastAttempt = null,
    )

    /** The active credential method, or `null` when nothing is configured or it cannot be read. */
    private suspend fun currentCredential(): PrimaryCredentialType? =
        (credentialManager.status().valueOrNull() as? CredentialStatus.Configured)?.type

    /** What the secondary path currently offers. */
    private suspend fun currentBiometric(): BiometricStatus = biometrics.state().status

    /** Publishes [state], unless this run has been stopped or replaced by a newer one. */
    private fun publish(runGeneration: Long, state: AppLockOverlayState) {
        synchronized(lifecycleLock) {
            if (generation != runGeneration) return
            mutableState.value = state
        }
    }
}

/** The request a published state belongs to, or `null` when it belongs to none. */
private fun AppLockOverlayState.pendingRequest(): ProtectionRequest? = when (this) {
    AppLockOverlayState.Idle -> null
    is AppLockOverlayState.Required -> request
    is AppLockOverlayState.Unpresentable -> request
}

/**
 * How a capability answer reads as a reason the surface cannot be shown.
 *
 * Only asked when the answer is not [OverlayCapability.Granted]; a grant reaching here would be a
 * programming error, and it is mapped to [OverlayUnavailability.Unavailable] so that even then the
 * result is "cannot show", never a silent claim that the surface is up.
 */
private fun OverlayCapability.toUnavailability(): OverlayUnavailability = when (this) {
    OverlayCapability.NotGranted -> OverlayUnavailability.NotGranted
    OverlayCapability.Granted, OverlayCapability.Unavailable -> OverlayUnavailability.Unavailable
}
