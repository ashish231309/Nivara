package com.nivara.app.domain.credential

/**
 * Whether a primary credential is configured, and which method is active.
 *
 * This is the state the rest of the application should ask for; it carries no credential
 * material and nothing that could be used to authenticate.
 */
sealed interface CredentialStatus {

    /** No primary credential has been enrolled yet. */
    data object NotConfigured : CredentialStatus

    /** Exactly one primary method is active. */
    data class Configured(val type: PrimaryCredentialType) : CredentialStatus
}

/**
 * The outcome of an authentication attempt.
 *
 * This is the boundary future stages consume: session management, app locking and quick lock
 * will all branch on this type and on nothing else. It is deliberately coarse.
 *
 * - A wrong credential, a credential of the wrong type and a record whose type byte was changed
 *   all produce [Failed]; the caller cannot tell them apart, and neither can an attacker
 *   watching the response.
 * - Internal trouble (an unreadable or unusable stored record) produces [InvalidConfiguration],
 *   which is a statement about Nivara's own data, not about the credential that was entered.
 * - [TemporarilyBlocked] means the attempt was never evaluated because an earlier run of
 *   failures is still in its delay window.
 *
 * Nothing in this type reveals the credential, the derived key or the stored verifier.
 */
sealed interface AuthenticationOutcome {

    /** The credential was accepted. */
    data object Succeeded : AuthenticationOutcome

    /**
     * The credential was rejected.
     *
     * @param blockedForMillis how long the next attempt will be refused, or `0` when attempts
     *   are still free. Reporting the delay is intentional: it tells the person who mistyped to
     *   wait rather than to keep trying, and it tells anyone guessing that guessing is expensive.
     */
    data class Failed(val blockedForMillis: Long) : AuthenticationOutcome

    /**
     * The attempt was refused without being evaluated.
     *
     * @param retryAfterMillis milliseconds remaining in the current delay window.
     */
    data class TemporarilyBlocked(val retryAfterMillis: Long) : AuthenticationOutcome

    /** No credential is configured, so there is nothing to authenticate against. */
    data object NotConfigured : AuthenticationOutcome

    /** The stored credential record is missing, corrupt or not usable by this build. */
    data object InvalidConfiguration : AuthenticationOutcome
}
