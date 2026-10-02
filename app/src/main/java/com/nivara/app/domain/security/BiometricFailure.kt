package com.nivara.app.domain.security

/**
 * Typed failures of the biometric operations that can fail outright.
 *
 * Authentication itself reports a [BiometricAuthenticationOutcome] rather than throwing, because
 * rejection is an ordinary result. These failures cover the operations around it: turning
 * biometric unlock on or off.
 *
 * Like every other failure in Nivara, these carry no platform message, no identifier and no
 * biometric data, so they stay safe wherever they end up. They are never a way to bypass the
 * primary credential: the only failure a user can be sent to is
 * [BiometricFailure.PrimaryCredentialRequired], which sends them *towards* it.
 */
sealed class BiometricFailure(message: String) : Exception(message) {

    /**
     * No primary credential is configured.
     *
     * Biometric unlock is a convenience for an existing credential and never a way to create an
     * account state of its own, so there is nothing for it to stand in for.
     */
    data object PrimaryCredentialRequired : BiometricFailure("a primary credential is required")

    /** The device cannot perform the operation right now. */
    data class Unavailable(val reason: BiometricUnavailability) :
        BiometricFailure("biometric authentication is unavailable")

    /** The user dismissed the platform prompt, so the operation did not happen. */
    data object Cancelled : BiometricFailure("the biometric prompt was cancelled")

    /**
     * Android's own biometric lockout is in force, so the operation could not be carried out.
     *
     * The lockout belongs to the platform and cannot be cleared by Nivara; only the platform clears
     * it, and the user is pointed at the primary credential in the meantime.
     */
    data class SystemBlocked(val permanent: Boolean) :
        BiometricFailure("the platform locked biometric authentication")

    /** There is nothing enabled to turn off. */
    data object NotEnabled : BiometricFailure("biometric unlock is not enabled")

    /** The platform refused to create or use the biometric key. */
    data object KeyStoreUnavailable : BiometricFailure("the platform key store is unavailable")

    /** The platform rejected the key parameters Nivara asked for. */
    data object KeyGenerationFailed : BiometricFailure("the platform refused to create the key")

    /** The non-secret biometric record could not be written or removed. */
    data object StorageUnavailable : BiometricFailure("the biometric record could not be stored")
}
