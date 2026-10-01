package com.nivara.app.domain.security

/**
 * Whether Nivara currently considers the user authenticated.
 *
 * A session is an **authorization state, not a secret**. Nothing here is a credential, a key, a
 * token or something that could be replayed: there is no value in this type that grants access on
 * its own, and no value that is worth stealing. What it records is only what a later feature
 * (App Lock, the vault, hidden apps) has to ask before it shows anything protected.
 *
 * The state is deliberately immutable: a session is created, and it ends. It is never edited in
 * place, and no screen ever moves its expiry — [SessionManager] owns every transition.
 */
sealed interface SessionState {

    /** No valid session. Sensitive content must not be shown, and authentication is required. */
    data object Unauthenticated : SessionState

    /**
     * A valid session, until it expires.
     *
     * @param source which authentication path opened it.
     * @param startedAtMillis when it was established, on the same clock as
     *   [com.nivara.app.domain.credential.TimeProvider].
     * @param expiresAtMillis the instant it stops being valid. The session is valid **before**
     *   this value and invalid **at** it: the interval is half-open, so an expiry boundary is a
     *   single, testable instant rather than a matter of rounding.
     */
    data class Authenticated(
        val source: AuthenticationSource,
        val startedAtMillis: Long,
        val expiresAtMillis: Long,
    ) : SessionState {

        init {
            require(expiresAtMillis >= startedAtMillis) {
                "a session cannot expire before it started"
            }
        }

        /** Whether this session still authorizes anything at [nowMillis]. */
        fun isValidAt(nowMillis: Long): Boolean = nowMillis < expiresAtMillis

        /** Milliseconds left, or `0` once the session has expired. */
        fun remainingMillis(nowMillis: Long): Long = (expiresAtMillis - nowMillis).coerceAtLeast(0L)
    }

    /** `true` only for [Authenticated]; a convenience for the gate's callers. */
    val isAuthenticated: Boolean
        get() = this is Authenticated
}
