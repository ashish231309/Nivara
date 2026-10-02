package com.nivara.app.domain.credential

/**
 * Typed failures of the credential layer.
 *
 * Messages are fixed, non-secret strings: they never contain the credential, the derived key,
 * the stored verifier, a salt or provider detail, so they stay safe if one ever reaches a crash
 * report. The only numbers they carry are policy values — a minimum length or a retry delay —
 * which are configuration, not secrets.
 *
 * Callers distinguish "the user's credential was wrong" from "the stored configuration could
 * not be used", because those need different messages and different recovery. They deliberately
 * cannot tell *why* a credential was rejected: a wrong credential, a credential of the wrong
 * type and a relabelled record all produce the same outcome.
 */
sealed class CredentialFailure(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** The credential is shorter than its policy allows. */
    data class TooShort(val minimumLength: Int) :
        CredentialFailure("credential is shorter than the minimum length")

    /** The credential is longer than its policy allows. */
    data class TooLong(val maximumLength: Int) :
        CredentialFailure("credential is longer than the maximum length")

    /** The credential contains a character that is not allowed for its type. */
    data object InvalidCharacters : CredentialFailure("credential contains invalid characters")

    /** The credential is one of the deterministic weak choices the policy rejects. */
    data object TooCommon : CredentialFailure("credential is too easy to guess")

    /** The pattern connects fewer points than the policy requires. */
    data class PatternTooShort(val minimumPoints: Int) :
        CredentialFailure("pattern connects too few points")

    /** The pattern cannot be drawn on the grid: it re-visits a point or leaves the grid. */
    data object PatternInvalid : CredentialFailure("pattern is not a valid drawing")

    /** The confirmation entry does not match the credential. */
    data object ConfirmationMismatch : CredentialFailure("the two entries do not match")

    /** The two entries are of different types, which means the caller mixed up its state. */
    data object ConfirmationTypeMismatch : CredentialFailure("the two entries are not the same kind of credential")

    /** No primary credential is configured yet. */
    data object NotConfigured : CredentialFailure("no primary credential is configured")

    /** A primary credential is already configured; the change flow must be used instead. */
    data object AlreadyConfigured : CredentialFailure("a primary credential is already configured")

    /** The credential offered as the current one was rejected. */
    data object CurrentCredentialIncorrect : CredentialFailure("the current credential was rejected")

    /** The stored record is missing, corrupt or unusable. */
    data object InvalidConfiguration : CredentialFailure("the stored credential configuration is not usable")

    /** The next attempt is refused until the current block expires. */
    data class TemporarilyBlocked(val retryAfterMillis: Long) :
        CredentialFailure("authentication is temporarily blocked")

    /** Protection for the credential could not be computed, e.g. key derivation failed. */
    data object ProtectionFailed : CredentialFailure("credential protection could not be computed")

    /** The credential record could not be read or written. */
    data object StorageUnavailable : CredentialFailure("credential storage is unavailable")
}
