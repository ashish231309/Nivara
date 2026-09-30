package com.nivara.app.ui.credential

import androidx.annotation.StringRes
import com.nivara.app.R
import com.nivara.app.domain.credential.AuthenticationOutcome
import com.nivara.app.domain.credential.CredentialFailure
import com.nivara.app.domain.credential.PrimaryCredentialType
import com.nivara.app.ui.components.NivaraMessage
import com.nivara.app.ui.components.secondsFromMillis

/** Display name of a credential method, shared by every screen that names one. */
@StringRes
fun credentialTypeNameRes(type: PrimaryCredentialType): Int = when (type) {
    PrimaryCredentialType.Pin -> R.string.credential_type_pin
    PrimaryCredentialType.Password -> R.string.credential_type_password
    PrimaryCredentialType.Pattern -> R.string.credential_type_pattern
}

/**
 * Maps a failure to its message.
 *
 * Every branch states what the user can do about the problem and nothing about how the layer
 * works internally: no key derivation, no verifier, no storage format, no provider detail.
 */
fun CredentialFailure.toMessage(): NivaraMessage = when (this) {
    is CredentialFailure.TooShort -> NivaraMessage(R.string.credential_error_too_short, minimumLength.toLong())
    is CredentialFailure.TooLong -> NivaraMessage(R.string.credential_error_too_long, maximumLength.toLong())
    is CredentialFailure.InvalidCharacters -> NivaraMessage(R.string.credential_error_invalid_characters)
    is CredentialFailure.TooCommon -> NivaraMessage(R.string.credential_error_too_common)
    is CredentialFailure.PatternTooShort ->
        NivaraMessage(R.string.credential_error_pattern_too_short, minimumPoints.toLong())
    is CredentialFailure.PatternInvalid -> NivaraMessage(R.string.credential_error_pattern_invalid)
    is CredentialFailure.ConfirmationMismatch -> NivaraMessage(R.string.credential_error_confirmation_mismatch)
    is CredentialFailure.ConfirmationTypeMismatch -> NivaraMessage(R.string.credential_error_confirmation_mismatch)
    is CredentialFailure.NotConfigured -> NivaraMessage(R.string.credential_error_not_configured)
    is CredentialFailure.AlreadyConfigured -> NivaraMessage(R.string.credential_error_already_configured)
    is CredentialFailure.CurrentCredentialIncorrect -> NivaraMessage(R.string.credential_error_failed)
    is CredentialFailure.InvalidConfiguration -> NivaraMessage(R.string.credential_error_invalid_configuration)
    is CredentialFailure.TemporarilyBlocked ->
        NivaraMessage(R.string.credential_error_blocked, secondsFromMillis(retryAfterMillis))
    is CredentialFailure.ProtectionFailed -> NivaraMessage(R.string.credential_error_generic)
    is CredentialFailure.StorageUnavailable -> NivaraMessage(R.string.credential_error_generic)
}

/**
 * The message for a rejected verification, or `null` when the outcome was a success.
 *
 * A rejected attempt reports the delay it triggered so the user learns that waiting is the way
 * forward, rather than retrying into a wall.
 */
fun AuthenticationOutcome.toFailureMessage(): NivaraMessage? = when (this) {
    is AuthenticationOutcome.Succeeded -> null
    is AuthenticationOutcome.Failed ->
        if (blockedForMillis > 0L) {
            NivaraMessage(R.string.credential_error_blocked, secondsFromMillis(blockedForMillis))
        } else {
            NivaraMessage(R.string.credential_error_failed)
        }
    is AuthenticationOutcome.TemporarilyBlocked ->
        NivaraMessage(R.string.credential_error_blocked, secondsFromMillis(retryAfterMillis))
    is AuthenticationOutcome.NotConfigured -> NivaraMessage(R.string.credential_error_not_configured)
    is AuthenticationOutcome.InvalidConfiguration -> NivaraMessage(R.string.credential_error_invalid_configuration)
}
