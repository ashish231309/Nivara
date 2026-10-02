package com.nivara.app.domain.security

import com.nivara.app.domain.credential.ThrottlePolicy

/**
 * The delay Nivara applies to biometric authentication after repeated failures.
 *
 * The rule is deliberately blunt: the first [DEFAULT_FREE_ATTEMPTS] failures cost nothing, because
 * a misplaced finger, a wet hand or a face mask is normal and should not penalise the user. From
 * there on every further attempt waits [DEFAULT_RETRY_DELAY_MILLIS] — roughly one attempt per half
 * minute, for as long as the failures continue.
 *
 * Three properties matter, and they are why this is its own type rather than a reuse of the
 * credential's schedule:
 *
 * - **It is Nivara's counter, not Android's.** The platform locks the sensor on its own terms.
 *   Nivara's delay runs beside that lockout and never replaces, shortens or bypasses it; when the
 *   platform reports a lockout, the platform's answer is the one that is reported to the user.
 * - **It cannot become permanent.** The count resets after a successful authentication, and
 *   Android itself bounds the sensor lockout, so a user who keeps failing waits — they are never
 *   locked out of their own data, and the primary credential remains available throughout.
 * - **It is deterministic.** The same failure count always produces the same delay, which makes
 *   the policy testable without a device and without a clock. This is Nivara's own fixed interval,
 *   not an exponential backoff: a biometric cannot be guessed by trying values, so the interval
 *   only has to make repeated attempts tedious and detectable while it stays predictable for the
 *   legitimate user who simply has a wet finger.
 *
 * The counter itself lives in a store of its own, so a biometric failure can never influence how
 * the primary credential behaves.
 */
class BiometricThrottlePolicy(
    /** Consecutive failures that are allowed before any delay applies. */
    val freeAttempts: Int = DEFAULT_FREE_ATTEMPTS,
    /** Delay applied to every attempt once the allowance is used up. */
    val retryDelayMillis: Long = DEFAULT_RETRY_DELAY_MILLIS,
) : ThrottlePolicy {

    init {
        require(freeAttempts > 0) { "the free attempt allowance must be positive" }
        require(retryDelayMillis > 0L) { "the retry delay must be positive" }
    }

    /**
     * Delays every attempt once [consecutiveFailures] has reached the allowance.
     *
     * The delay depends only on how many failures have been recorded: it is the same interval
     * every time rather than a growing one, and it never produces a permanent state. A delay that
     * has elapsed is not repeated for failures that already happened.
     */
    override fun delayMillisAfter(consecutiveFailures: Int): Long =
        if (consecutiveFailures < freeAttempts) 0L else retryDelayMillis

    override fun toString(): String =
        "BiometricThrottlePolicy(freeAttempts=$freeAttempts, retryDelayMillis=$retryDelayMillis)"

    companion object {
        /** Failures that cost nothing: a biometric is not a secret that can be guessed. */
        const val DEFAULT_FREE_ATTEMPTS: Int = 5

        /** One attempt per half minute once the free allowance is used up. */
        const val DEFAULT_RETRY_DELAY_MILLIS: Long = 30_000L

        /** The policy the application uses. */
        val Default: BiometricThrottlePolicy = BiometricThrottlePolicy()
    }
}
