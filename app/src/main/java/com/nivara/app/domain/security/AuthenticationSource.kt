package com.nivara.app.domain.security

/**
 * How the current session was established.
 *
 * This is not a credential type. [PrimaryCredentialType][com.nivara.app.domain.credential.PrimaryCredentialType]
 * says *what the user knows* — a PIN, a password or a pattern — and the session layer never needs
 * that: it only needs to say which of the two authentication paths opened the session. Keeping the
 * two apart is what stops the session from becoming a second credential store.
 */
enum class AuthenticationSource {

    /**
     * The primary credential was verified.
     *
     * This is the authoritative factor: a session established this way can be established even
     * when biometrics are unavailable, invalidated or throttled.
     */
    Primary,

    /**
     * Android's biometric prompt accepted the user.
     *
     * A valid factor for a session, and nothing more. It never replaces the primary credential,
     * never changes it, and cannot be used to keep a session alive past its expiry — a biometric
     * success is just another way of arriving at the same gate.
     */
    Biometric,
}
