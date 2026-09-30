package com.nivara.app.domain.security

import com.nivara.app.core.common.NivaraResult

/**
 * Biometric authentication, as the rest of Nivara sees it.
 *
 * Biometrics are a **secondary** path. They make the primary credential faster to use and nothing
 * else: nothing here can be enabled without a configured primary credential, nothing here can
 * replace one, and no outcome of this interface means that the application is unlocked. Sessions
 * belong to a later stage.
 *
 * The implementation delegates every decision that matters to Android. Matching, template storage,
 * hardware lockout and the prompt itself are the platform's; Nivara decides only *when* to ask,
 * what to do with the answer, and how long to wait after its own failures. No biometric data ever
 * reaches the application — Android compares the fingerprint or face and answers yes or no.
 *
 * The prompt is the platform's own, never a Nivara screen. Android attaches it to the activity in
 * front of the user, which is why a host is registered through [attachHost] and released through
 * [detachHost]: the domain cannot name an Android type, so the UI hands its host over as an opaque
 * marker that only the platform implementation interprets.
 */
interface BiometricAuthenticator {

    /**
     * Registers the UI host that Android's biometric prompt should be attached to.
     *
     * Called by the screen that is in front of the user, typically when the activity is created.
     * The value is a [BiometricPromptHost]; only the platform implementation looks at it, and it
     * must be the application's activity.
     */
    fun attachHost(host: BiometricPromptHost)

    /**
     * Releases [host], for example when its screen is destroyed.
     *
     * Only the host that is currently registered is released, so a screen that is being recreated
     * cannot release the host that replaced it.
     */
    fun detachHost(host: BiometricPromptHost)

    /** Whether biometric unlock is usable, set up, or broken, plus Nivara's temporary delay. */
    suspend fun state(): BiometricState

    /**
     * Turns biometric unlock on.
     *
     * Requires a configured primary credential: biometrics are a shortcut for a credential, and
     * turning them on can never be the act that creates an account state. Android is asked for a
     * fresh authentication as part of this, so the key Nivara creates is only ever authorized by a
     * biometric the user presented deliberately.
     *
     * Fails with [BiometricFailure.PrimaryCredentialRequired] when there is no primary credential,
     * and with [BiometricFailure.Unavailable] when the device cannot do it at all. Turning it on
     * when it is already on changes nothing and reports success.
     */
    suspend fun enable(): NivaraResult<Unit>

    /**
     * Runs one biometric authentication through Android's own prompt.
     *
     * Returns a typed outcome rather than throwing, because a non-matching biometric and a
     * cancelled prompt are ordinary results. A failure here is counted against Nivara's biometric
     * allowance and never against the primary credential: the two counters are separate stores and
     * separate rules.
     */
    suspend fun authenticate(): BiometricAuthenticationOutcome

    /**
     * Removes Nivara's biometric unlock path and its key.
     *
     * The primary credential is not touched. The caller is expected to have authenticated the user
     * first — with the primary credential, so that removal works even when the stored key is
     * already unusable — which is why this is never reachable from a background or unauthenticated
     * action.
     */
    suspend fun disable(): NivaraResult<Unit>

    /**
     * Forgets Nivara's biometric failures and the delay they caused.
     *
     * Called after the user has authenticated with the primary credential, which is the documented
     * way out of a throttled biometric path. It does not and cannot clear Android's own lockout,
     * which the platform owns.
     */
    suspend fun clearFailures()
}

/**
 * Marks the UI host that Android's biometric prompt is attached to.
 *
 * The platform prompt is owned by Android and must be hosted by a foreground screen, and the
 * domain layer cannot name an Android type. The UI therefore implements this marker and hands
 * itself over through [BiometricAuthenticator.attachHost]; only the data-layer implementation
 * looks at the value, and it expects the platform activity. Nivara never draws a biometric prompt
 * of its own, and nothing else in the application may interpret this value.
 */
interface BiometricPromptHost
