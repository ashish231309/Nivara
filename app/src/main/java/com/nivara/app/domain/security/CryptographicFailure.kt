package com.nivara.app.domain.security

/**
 * Typed failures produced by the cryptographic layer.
 *
 * Cryptography fails loudly. Every condition that a caller must be able to tell apart gets its
 * own case, so no code path can quietly continue with weaker security after a failure.
 *
 * Messages are fixed, non-secret strings. They carry no key material, no plaintext, no
 * credential and no provider detail, so they stay safe if they ever reach a crash report.
 * Diagnostic causes are attached as the `cause` of the underlying exception where the platform
 * provides one; the cause is never shown to users.
 */
sealed class CryptographicFailure(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** The envelope is structurally invalid: too short, truncated, or has a set reserved byte. */
    data object MalformedEnvelope : CryptographicFailure("envelope is malformed or truncated")

    /** The envelope does not start with Nivara's magic bytes. */
    data object UnsupportedEnvelope : CryptographicFailure("envelope is not in a supported format")

    /** The envelope was written by a format version this build cannot read. */
    data object UnsupportedVersion : CryptographicFailure("envelope version is not supported")

    /** The envelope names a key scheme this build cannot use. */
    data object UnsupportedKeyScheme : CryptographicFailure("envelope key scheme is not supported")

    /** The envelope names an encryption algorithm this build cannot use. */
    data object UnsupportedAlgorithm : CryptographicFailure("envelope algorithm is not supported")

    /** The envelope declares a purpose tag that is not defined. */
    data object UnsupportedContext : CryptographicFailure("envelope purpose is not supported")

    /** The envelope is authentic but was created for a different purpose than the caller asked for. */
    data object ContextMismatch : CryptographicFailure("envelope was created for a different purpose")

    /**
     * The authentication tag did not verify. This is the expected outcome for a wrong key, an
     * edited nonce, edited ciphertext, edited header, or edited associated data.
     */
    data object AuthenticationFailed : CryptographicFailure("authentication tag did not verify")

    /** The supplied key cannot be used: wrong algorithm, wrong size, or unexportable material. */
    data object InvalidKey : CryptographicFailure("key is not usable for this operation")

    /** Parameters are unusable (empty credential, salt that is too short, unsupported size). */
    data object InvalidParameters : CryptographicFailure("parameters are not usable")

    /** No key exists under the requested alias. */
    data object KeyUnavailable : CryptographicFailure("key is not available")

    /** The key exists but the platform permanently invalidated it and it can never be used again. */
    data object KeyInvalidated : CryptographicFailure("key was permanently invalidated by the platform")

    /** The platform key store rejected key generation. */
    data object KeyGenerationFailed : CryptographicFailure("key generation failed")

    /** The platform key store could not be reached or is unavailable on this device. */
    data object KeyStoreUnavailable : CryptographicFailure("platform key store is unavailable")
}
