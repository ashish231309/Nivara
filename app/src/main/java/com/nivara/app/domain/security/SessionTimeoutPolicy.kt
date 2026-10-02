package com.nivara.app.domain.security

/**
 * How long an authenticated session lasts.
 *
 * ### The rule, in one sentence
 *
 * A session expires a fixed interval after it is established. Nothing extends it — not screen
 * navigation, not sensitive activity, not a biometric success — because extending a session is
 * exactly the kind of decision that should need the user's credential rather than their attention.
 * A second authentication establishes a second session with its own deadline.
 *
 * ### Why it is a value and not a number in the UI
 *
 * The timeout is a security decision, so it lives in the domain layer as a pure, deterministic
 * function of the clock: [expiryFor] answers when a session that started at a given instant ends,
 * and nothing else in the application computes or stores that instant. It is deliberately *not*
 * related to the attempt-throttling policies: those delay a *failed* attempt, this ends a
 * *successful* one, and the two must never share a counter or a value.
 *
 * ### What it is not
 *
 * It is not a lockout, and it is not persistence. When a session expires, the user simply
 * authenticates again; no credential, key or record is touched, and nothing is written to disk.
 *
 * The clock is the wall clock supplied by [com.nivara.app.domain.credential.TimeProvider], for the
 * same reason throttling uses it: a surviving process is the only thing that holds a session, and
 * using one clock everywhere keeps the behaviour testable. A user who changes the device clock can
 * therefore distort a session's remaining time — the honest trade-off is documented in
 * `docs/session/README.md`; nothing in the session is a boundary against someone who already
 * controls the unlocked device.
 */
class SessionTimeoutPolicy(

    /** How long a session stays valid after it is established, in milliseconds. */
    val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {

    init {
        require(timeoutMillis > 0L) { "the session timeout must be positive" }
    }

    /** The instant a session established at [startedAtMillis] stops being valid. */
    fun expiryFor(startedAtMillis: Long): Long = startedAtMillis + timeoutMillis

    /**
     * Whether a session established at [startedAtMillis] is still valid at [nowMillis].
     *
     * Valid strictly before the expiry instant, invalid at it: the boundary belongs to the expired
     * side, so "has it expired?" has one answer at every instant.
     */
    fun isValidAt(startedAtMillis: Long, nowMillis: Long): Boolean =
        nowMillis < expiryFor(startedAtMillis)

    override fun toString(): String = "SessionTimeoutPolicy(timeoutMillis=$timeoutMillis)"

    companion object {

        /**
         * Five minutes.
         *
         * Chosen as the starting point for a privacy application: long enough that a user working
         * through a task is not asked for a credential again mid-flow, short enough that a phone
         * left on a table is not a signed-in vault. It is a value, not a constant compiled into
         * screens, so a later stage can make it configurable without touching any UI.
         */
        const val DEFAULT_TIMEOUT_MILLIS: Long = 5L * 60L * 1_000L

        /** The policy the application uses. */
        val Default: SessionTimeoutPolicy = SessionTimeoutPolicy()
    }
}
