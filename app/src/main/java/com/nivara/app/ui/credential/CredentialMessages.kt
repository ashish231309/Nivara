package com.nivara.app.ui.credential

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.nivara.app.R
import com.nivara.app.domain.credential.AuthenticationOutcome
import com.nivara.app.domain.credential.CredentialFailure
import com.nivara.app.domain.credential.PrimaryCredentialType

/**
 * A message shown to the user: a string resource plus, for the few messages that need one, the
 * number to format into it.
 *
 * Deliberately not a `String`. Turning failures into text here keeps every credential message in
 * one place, and keeps the typed domain failures free of user-facing wording — the domain never
 * needs to know how a length or a delay should be phrased.
 */
data class CredentialMessage(
    @StringRes val textRes: Int,
    val argument: Long? = null,
)

/** Display name of a credential method, shared by every screen that names one. */
@StringRes
fun credentialTypeNameRes(type: PrimaryCredentialType): Int = when (type) {
    PrimaryCredentialType.Pin -> R.string.credential_type_pin
    PrimaryCredentialType.Password -> R.string.credential_type_password
    PrimaryCredentialType.Pattern -> R.string.credential_type_pattern
}

/** Renders a [CredentialMessage], formatting [CredentialMessage.argument] when it has one. */
@Composable
fun CredentialMessageText(
    message: CredentialMessage,
    modifier: Modifier = Modifier,
) {
    val text = message.argument?.let { argument -> stringResource(id = message.textRes, argument) }
        ?: stringResource(id = message.textRes)
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier,
    )
}

/**
 * Maps a failure to its message.
 *
 * Every branch states what the user can do about the problem and nothing about how the layer
 * works internally: no key derivation, no verifier, no storage format, no provider detail.
 */
fun CredentialFailure.toMessage(): CredentialMessage = when (this) {
    is CredentialFailure.TooShort -> CredentialMessage(R.string.credential_error_too_short, minimumLength.toLong())
    is CredentialFailure.TooLong -> CredentialMessage(R.string.credential_error_too_long, maximumLength.toLong())
    is CredentialFailure.InvalidCharacters -> CredentialMessage(R.string.credential_error_invalid_characters)
    is CredentialFailure.TooCommon -> CredentialMessage(R.string.credential_error_too_common)
    is CredentialFailure.PatternTooShort ->
        CredentialMessage(R.string.credential_error_pattern_too_short, minimumPoints.toLong())
    is CredentialFailure.PatternInvalid -> CredentialMessage(R.string.credential_error_pattern_invalid)
    is CredentialFailure.ConfirmationMismatch -> CredentialMessage(R.string.credential_error_confirmation_mismatch)
    is CredentialFailure.ConfirmationTypeMismatch -> CredentialMessage(R.string.credential_error_confirmation_mismatch)
    is CredentialFailure.NotConfigured -> CredentialMessage(R.string.credential_error_not_configured)
    is CredentialFailure.AlreadyConfigured -> CredentialMessage(R.string.credential_error_already_configured)
    is CredentialFailure.CurrentCredentialIncorrect -> CredentialMessage(R.string.credential_error_failed)
    is CredentialFailure.InvalidConfiguration -> CredentialMessage(R.string.credential_error_invalid_configuration)
    is CredentialFailure.TemporarilyBlocked ->
        CredentialMessage(R.string.credential_error_blocked, secondsFrom(retryAfterMillis))
    is CredentialFailure.ProtectionFailed -> CredentialMessage(R.string.credential_error_generic)
    is CredentialFailure.StorageUnavailable -> CredentialMessage(R.string.credential_error_generic)
}

/**
 * The message for a rejected verification, or `null` when the outcome was a success.
 *
 * A rejected attempt reports the delay it triggered so the user learns that waiting is the way
 * forward, rather than retrying into a wall.
 */
fun AuthenticationOutcome.toFailureMessage(): CredentialMessage? = when (this) {
    is AuthenticationOutcome.Succeeded -> null
    is AuthenticationOutcome.Failed ->
        if (blockedForMillis > 0L) {
            CredentialMessage(R.string.credential_error_blocked, secondsFrom(blockedForMillis))
        } else {
            CredentialMessage(R.string.credential_error_failed)
        }
    is AuthenticationOutcome.TemporarilyBlocked ->
        CredentialMessage(R.string.credential_error_blocked, secondsFrom(retryAfterMillis))
    is AuthenticationOutcome.NotConfigured -> CredentialMessage(R.string.credential_error_not_configured)
    is AuthenticationOutcome.InvalidConfiguration -> CredentialMessage(R.string.credential_error_invalid_configuration)
}

/** Seconds to display for a delay, rounded up and never below one. */
private fun secondsFrom(millis: Long): Long = if (millis <= 0L) 1L else (millis + MILLIS_PER_SECOND - 1L) / MILLIS_PER_SECOND

private const val MILLIS_PER_SECOND = 1_000L
