package com.nivara.app.domain.security

/**
 * Why the device cannot perform biometric authentication right now.
 *
 * These cases mirror Android's own capability report, because Nivara has no opinion about
 * hardware: if the platform says there is no sensor, no enrolment, or a sensor that is busy, that
 * is what the user is told, and the primary credential is the way forward in every one of them.
 */
enum class BiometricUnavailability {
    /** The device has no biometric sensor at all. */
    NoHardware,

    /** The sensor exists but cannot be used at the moment (busy, covered, temporarily locked). */
    HardwareUnavailable,

    /** The device can do biometrics, but the user has not enrolled a biometric. */
    NotEnrolled,

    /** The platform cannot run biometric authentication for this configuration. */
    Unsupported,

    /** The platform requires a security update before biometrics may be used. */
    SecurityUpdateRequired,

    /** The platform reported a state Nivara does not know. */
    Unknown,
}

/**
 * Nivara's biometric configuration: is the convenience path set up, and is it still usable?
 *
 * Biometrics are always secondary. No value of this type means "unlocked", and none of them
 * removes or weakens the primary credential, which remains the way into the application in every
 * case — including [Invalidated], where the stored key can no longer be used at all.
 */
sealed interface BiometricStatus {

    /** The device cannot perform biometric authentication right now. */
    data class Unavailable(val reason: BiometricUnavailability) : BiometricStatus

    /** The device can do it, and the user has not turned it on in Nivara. */
    data object Disabled : BiometricStatus

    /** Biometric unlock is set up and usable. */
    data object Enabled : BiometricStatus

    /**
     * Biometric unlock was set up, but the stored configuration can no longer authenticate
     * anything: the platform invalidated the key (the usual cause is a change in the device's
     * biometric enrolment), the key is gone, or the stored record is damaged.
     *
     * Nothing is recreated silently and the primary credential is untouched. The user may turn
     * biometric unlock on again deliberately.
     */
    data object Invalidated : BiometricStatus

    companion object {
        /**
         * Derives the status from three facts: whether a configuration is stored, whether its key
         * can still be used, and what the platform says about the hardware.
         *
         * Kept as a pure function so the rule can be tested without a device. The order matters:
         * hardware the platform will never support wins, because no stored configuration can make
         * an unsupported sensor work; then a stored configuration that cannot authenticate is
         * [Invalidated]; then the enrolment-less device is reported as [Invalidated] if Nivara had
         * turned it on, because the stored key is dead; and only then does the device's capability
         * decide between [Disabled], [Unavailable] and [Enabled].
         *
         * A sensor that is merely busy leaves a configured device [Enabled]: that is a temporary
         * condition of the prompt, not a problem with the configuration.
         *
         * @param configured whether a biometric record is stored.
         * @param keyUsable whether the stored key can still be used for an operation.
         * @param unavailability `null` when the device can authenticate right now, otherwise why it
         *   cannot.
         */
        fun resolve(
            configured: Boolean,
            keyUsable: Boolean,
            unavailability: BiometricUnavailability?,
        ): BiometricStatus {
            // Hardware the platform will never support is reported as itself, whatever is stored:
            // no configuration can make an unsupported sensor work.
            if (unavailability != null) {
                when (unavailability) {
                    BiometricUnavailability.NoHardware,
                    BiometricUnavailability.Unsupported,
                    BiometricUnavailability.SecurityUpdateRequired,
                    -> return Unavailable(unavailability)

                    BiometricUnavailability.HardwareUnavailable,
                    BiometricUnavailability.NotEnrolled,
                    BiometricUnavailability.Unknown,
                    -> Unit
                }
            }

            return when {
                configured && !keyUsable -> Invalidated
                configured && unavailability == BiometricUnavailability.NotEnrolled -> Invalidated
                configured -> Enabled
                unavailability != null -> Unavailable(unavailability)
                else -> Disabled
            }
        }
    }
}

/**
 * Everything a screen needs to describe biometric authentication.
 *
 * [retryAfterMillis] is Nivara's own delay after repeated failures, and only that. It is never
 * Android's lockout: when the platform locks biometrics, the prompt reports it as
 * [BiometricAuthenticationOutcome.SystemBlocked] and no application-side number can describe or
 * shorten it.
 */
data class BiometricState(
    val status: BiometricStatus,
    val retryAfterMillis: Long = 0L,
)

/**
 * The result of one biometric authentication.
 *
 * The caller cannot tell one non-matching biometric from another, and nothing here describes what
 * was compared: Android performs the match and answers yes or no.
 */
sealed interface BiometricAuthenticationOutcome {

    /** The platform accepted the user. */
    data object Succeeded : BiometricAuthenticationOutcome

    /**
     * A biometric was presented and not recognised.
     *
     * @param attemptsRemaining how many more failures Nivara allows before it starts delaying.
     * @param blockedForMillis how long the next attempt is delayed for, or `0` while the failures
     *   are still inside the free allowance.
     */
    data class Failed(
        val attemptsRemaining: Int,
        val blockedForMillis: Long,
    ) : BiometricAuthenticationOutcome

    /**
     * The user dismissed the prompt, or Android ended it without an answer.
     *
     * Cancellation is not a failed attempt: it is not counted against Nivara's failure allowance,
     * and it is not counted against the primary credential either.
     */
    data object Cancelled : BiometricAuthenticationOutcome

    /**
     * The attempt was refused without a prompt because Nivara's own delay is still running.
     *
     * This is an application-level pause, never a security lockout, and the primary credential
     * clears it.
     *
     * @param retryAfterMillis milliseconds left in the delay window.
     */
    data class TemporarilyBlocked(val retryAfterMillis: Long) : BiometricAuthenticationOutcome

    /**
     * Android's own biometric lockout is in force. It exists outside this application and cannot
     * be bypassed, shortened or cleared by Nivara; only the platform clears it, on its own terms.
     *
     * @param permanent `true` for the platform's permanent lockout, which the platform clears when
     *   the user unlocks the device with its own screen lock.
     */
    data class SystemBlocked(val permanent: Boolean) : BiometricAuthenticationOutcome

    /** Biometric unlock has not been turned on in Nivara, so there is nothing to authenticate. */
    data object NotEnabled : BiometricAuthenticationOutcome

    /**
     * Biometric unlock was turned on, but the stored configuration can no longer authenticate
     * anything.
     *
     * The primary credential is unchanged and remains the way in; turning biometric unlock on
     * again is what recreates the key, and nothing is recreated by Nivara on its own.
     */
    data object Invalidated : BiometricAuthenticationOutcome

    /** The device cannot perform biometric authentication right now. */
    data class Unavailable(val reason: BiometricUnavailability) : BiometricAuthenticationOutcome
}
