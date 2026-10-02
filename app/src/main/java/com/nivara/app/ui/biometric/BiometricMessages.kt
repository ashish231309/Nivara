package com.nivara.app.ui.biometric

import androidx.annotation.StringRes
import com.nivara.app.R
import com.nivara.app.domain.security.BiometricAuthenticationOutcome
import com.nivara.app.domain.security.BiometricFailure
import com.nivara.app.domain.security.BiometricStatus
import com.nivara.app.domain.security.BiometricUnavailability
import com.nivara.app.ui.components.NivaraMessage
import com.nivara.app.ui.components.secondsFromMillis

/**
 * Maps biometric failures to user-facing messages.
 *
 * Nothing here describes the platform's internals, and nothing states or implies that Nivara can
 * override Android's own lockout: when the platform locks biometrics, the message says so and
 * points at the primary credential.
 */
fun BiometricFailure.toMessage(): NivaraMessage = when (this) {
    is BiometricFailure.PrimaryCredentialRequired -> NivaraMessage(R.string.biometric_error_primary_required)
    is BiometricFailure.Unavailable -> NivaraMessage(biometricUnavailabilityRes(reason))
    is BiometricFailure.SystemBlocked -> if (permanent) {
        NivaraMessage(R.string.biometric_error_system_locked_permanent)
    } else {
        NivaraMessage(R.string.biometric_error_system_locked)
    }
    is BiometricFailure.Cancelled -> NivaraMessage(R.string.biometric_error_cancelled)
    is BiometricFailure.NotEnabled -> NivaraMessage(R.string.biometric_error_not_enabled)
    is BiometricFailure.KeyGenerationFailed -> NivaraMessage(R.string.biometric_error_key_unavailable)
    is BiometricFailure.KeyStoreUnavailable -> NivaraMessage(R.string.biometric_error_key_unavailable)
    is BiometricFailure.StorageUnavailable -> NivaraMessage(R.string.biometric_error_generic)
}

/** The message for a biometric attempt, or `null` when it succeeded. */
fun BiometricAuthenticationOutcome.toMessage(): NivaraMessage? = when (this) {
    is BiometricAuthenticationOutcome.Succeeded -> null
    is BiometricAuthenticationOutcome.Failed -> NivaraMessage(R.string.biometric_error_failed)
    is BiometricAuthenticationOutcome.Cancelled -> NivaraMessage(R.string.biometric_error_cancelled)
    is BiometricAuthenticationOutcome.TemporarilyBlocked ->
        NivaraMessage(R.string.biometric_error_app_blocked, secondsFromMillis(retryAfterMillis))
    is BiometricAuthenticationOutcome.SystemBlocked -> if (permanent) {
        NivaraMessage(R.string.biometric_error_system_locked_permanent)
    } else {
        NivaraMessage(R.string.biometric_error_system_locked)
    }
    is BiometricAuthenticationOutcome.NotEnabled -> NivaraMessage(R.string.biometric_error_not_enabled)
    is BiometricAuthenticationOutcome.Invalidated -> NivaraMessage(R.string.biometric_error_invalidated)
    is BiometricAuthenticationOutcome.Unavailable -> NivaraMessage(biometricUnavailabilityRes(reason))
}

/** The headline for a biometric state. */
@StringRes
fun biometricStatusRes(status: BiometricStatus): Int = when (status) {
    is BiometricStatus.Enabled -> R.string.biometric_status_enabled
    is BiometricStatus.Disabled -> R.string.biometric_status_disabled
    is BiometricStatus.Invalidated -> R.string.biometric_status_invalidated
    is BiometricStatus.Unavailable -> biometricUnavailabilityRes(status.reason)
}

/** The one-line explanation that goes with [biometricStatusRes]. */
@StringRes
fun biometricStatusSummaryRes(status: BiometricStatus): Int = when (status) {
    is BiometricStatus.Enabled -> R.string.biometric_status_enabled_summary
    is BiometricStatus.Disabled -> R.string.biometric_status_disabled_summary
    is BiometricStatus.Invalidated -> R.string.biometric_status_invalidated_summary
    is BiometricStatus.Unavailable -> R.string.biometric_status_unavailable_summary
}

/** Why the device cannot use biometrics, in the platform's own terms. */
@StringRes
fun biometricUnavailabilityRes(reason: BiometricUnavailability): Int = when (reason) {
    BiometricUnavailability.NoHardware -> R.string.biometric_status_unavailable_no_hardware
    BiometricUnavailability.HardwareUnavailable -> R.string.biometric_status_unavailable_hardware
    BiometricUnavailability.NotEnrolled -> R.string.biometric_status_unavailable_not_enrolled
    BiometricUnavailability.Unsupported -> R.string.biometric_status_unavailable_unsupported
    BiometricUnavailability.SecurityUpdateRequired -> R.string.biometric_status_unavailable_security_update
    BiometricUnavailability.Unknown -> R.string.biometric_status_unavailable_unknown
}
