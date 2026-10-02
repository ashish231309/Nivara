package com.nivara.app.data.biometric

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import com.nivara.app.domain.security.BiometricAuthenticationOutcome
import com.nivara.app.domain.security.BiometricFailure
import com.nivara.app.domain.security.BiometricUnavailability

/**
 * What the platform's capability report means to Nivara.
 *
 * Returns `null` when the device can authenticate right now, otherwise the reason it cannot. The
 * codes are Android's own, so this is a translation and not a judgement: a device that reports no
 * hardware is reported as having no hardware, and Nivara does not try to work around it.
 *
 * Kept as a pure function of an `Int` so the mapping can be tested on the JVM, against the
 * platform constants it maps, without a device or an emulator.
 */
internal fun biometricAvailability(statusCode: Int): BiometricUnavailability? = when (statusCode) {
    BiometricManager.BIOMETRIC_SUCCESS -> null
    BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricUnavailability.NoHardware
    BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricUnavailability.HardwareUnavailable
    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricUnavailability.NotEnrolled
    BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED ->
        BiometricUnavailability.SecurityUpdateRequired
    BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> BiometricUnavailability.Unsupported
    else -> BiometricUnavailability.Unknown
}

/**
 * What an error from Android's prompt means to Nivara.
 *
 * The mapping keeps three things apart that Android also keeps apart: the user ending the prompt,
 * the platform's own lockout, and a sensor or configuration that cannot answer. The lockout cases
 * are reported as a lockout and never as a failed attempt, because Nivara cannot clear them and
 * will not pretend that it can.
 *
 * Pure by design: a test can drive every branch without a device.
 */
internal fun promptErrorOutcome(errorCode: Int): BiometricAuthenticationOutcome = when (errorCode) {
    // The user dismissed the prompt, pressed its negative button, or let it time out.
    BiometricPrompt.ERROR_USER_CANCELED,
    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
    BiometricPrompt.ERROR_CANCELED,
    BiometricPrompt.ERROR_TIMEOUT,
    -> BiometricAuthenticationOutcome.Cancelled

    // Android's own lockout. It is not Nivara's, it is not bypassable, and only the platform
    // clears it: the user either waits or unlocks the device with its screen lock. Nivara's own
    // delay is a separate state and is reported separately.
    BiometricPrompt.ERROR_LOCKOUT -> BiometricAuthenticationOutcome.SystemBlocked(permanent = false)
    BiometricPrompt.ERROR_LOCKOUT_PERMANENT ->
        BiometricAuthenticationOutcome.SystemBlocked(permanent = true)

    BiometricPrompt.ERROR_HW_UNAVAILABLE,
    BiometricPrompt.ERROR_UNABLE_TO_PROCESS,
    -> BiometricAuthenticationOutcome.Unavailable(BiometricUnavailability.HardwareUnavailable)

    BiometricPrompt.ERROR_NO_BIOMETRICS ->
        BiometricAuthenticationOutcome.Unavailable(BiometricUnavailability.NotEnrolled)

    BiometricPrompt.ERROR_HW_NOT_PRESENT ->
        BiometricAuthenticationOutcome.Unavailable(BiometricUnavailability.NoHardware)

    BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED ->
        BiometricAuthenticationOutcome.Unavailable(BiometricUnavailability.SecurityUpdateRequired)

    // Includes ERROR_NO_SPACE, ERROR_VENDOR and ERROR_NO_DEVICE_CREDENTIAL: all real failures, none
    // of them a mismatch, and none of them something Nivara can act on beyond telling the truth
    // that biometric authentication did not happen.
    else -> BiometricAuthenticationOutcome.Unavailable(BiometricUnavailability.Unknown)
}

/** The same mapping for the operations that report a [BiometricFailure] rather than an outcome. */
internal fun promptErrorFailure(errorCode: Int): BiometricFailure =
    when (val outcome = promptErrorOutcome(errorCode)) {
        is BiometricAuthenticationOutcome.Cancelled -> BiometricFailure.Cancelled
        is BiometricAuthenticationOutcome.SystemBlocked -> BiometricFailure.SystemBlocked(outcome.permanent)
        is BiometricAuthenticationOutcome.Unavailable -> BiometricFailure.Unavailable(outcome.reason)
        else -> BiometricFailure.Unavailable(BiometricUnavailability.Unknown)
    }
