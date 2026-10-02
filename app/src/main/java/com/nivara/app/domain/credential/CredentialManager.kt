package com.nivara.app.domain.credential

import com.nivara.app.core.common.NivaraResult

/**
 * Enrols, verifies and replaces the user's primary credential.
 *
 * This is the only way the rest of the application touches the credential: no screen derives a
 * key, compares a verifier or writes a record itself. The manager builds on the Stage 2
 * cryptography — the same [com.nivara.app.domain.security.KeyDerivationService] used everywhere
 * else, the same salt generation — and adds no cryptographic primitive of its own.
 *
 * ### Ownership of inputs
 *
 * Every method takes ownership of the [CredentialInput] values it is given and clears them
 * before returning, on success, on failure and on cancellation. A caller must not read an input
 * after handing it over. This is the difference between "the credential lives until the next
 * garbage collection" and "the credential lives for the duration of one call".
 *
 * ### What the manager does not do
 *
 * It does not unlock anything, start a session, or decide how long an unlock lasts. Those are
 * session concerns and belong to a later stage. It does not provide a reset: recovery is a
 * separate mechanism and will be designed with its own threat model.
 */
interface CredentialManager {

    /** Whether a credential is configured, and which method is active. */
    suspend fun status(): NivaraResult<CredentialStatus>

    /**
     * Enrols the first primary credential.
     *
     * Fails with [CredentialFailure.AlreadyConfigured] when a credential already exists, so
     * enrolment cannot be used to replace a credential without authenticating: replacing goes
     * through [change], which requires the current credential.
     */
    suspend fun enroll(
        credential: CredentialInput,
        confirmation: CredentialInput,
    ): NivaraResult<Unit>

    /**
     * Verifies [credential] against the configured primary credential.
     *
     * Returns an [AuthenticationOutcome] rather than throwing, because rejection is an expected,
     * ordinary result. A wrong credential is never distinguishable from any other rejection, and
     * the attempt counter is updated as part of the call.
     */
    suspend fun verify(credential: CredentialInput): AuthenticationOutcome

    /**
     * Replaces the primary credential after authenticating with the current one.
     *
     * The new credential may be of a different type than the current one: PIN, password and
     * pattern are interchangeable and exactly one of them is active afterwards. The previous
     * credential stops working the moment the record is replaced.
     */
    suspend fun change(
        current: CredentialInput,
        credential: CredentialInput,
        confirmation: CredentialInput,
    ): NivaraResult<Unit>
}
