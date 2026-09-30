package com.nivara.app.domain.credential

/**
 * What the attempt tracker remembers.
 *
 * Two numbers, both non-secret: how many attempts in a row have been rejected, and when the
 * current delay window ends. There is no history — no timestamps of individual attempts, no
 * counts per credential, nothing that would describe the user's behaviour beyond what the
 * throttling rule needs.
 */
data class ThrottleState(
    val consecutiveFailures: Int = 0,
    val blockedUntilMillis: Long = 0L,
) {
    init {
        require(consecutiveFailures >= 0) { "consecutive failures cannot be negative" }
        require(blockedUntilMillis >= 0L) { "block end cannot be negative" }
    }

    /** Milliseconds until the next attempt is allowed, or `0` when it is allowed now. */
    fun remainingBlockMillis(nowMillis: Long): Long = (blockedUntilMillis - nowMillis).coerceAtLeast(0L)

    override fun toString(): String =
        "ThrottleState(consecutiveFailures=$consecutiveFailures, blockedUntilMillis=$blockedUntilMillis)"

    companion object {
        /** Nothing recorded: no failures, no delay. */
        val Clear: ThrottleState = ThrottleState()
    }
}

/**
 * How long the next attempt is refused after [consecutiveFailures] rejections.
 *
 * Separated from the tracker so the schedule is a pure, testable decision and so a later stage
 * can replace it — the biometric lockout policy and the session rules are Stage 5's business,
 * and they will need their own numbers.
 */
fun interface ThrottlePolicy {

    /**
     * Delay in milliseconds, or `0` when the next attempt is free.
     *
     * @param consecutiveFailures how many attempts in a row have been rejected, including the
     *   one that just failed.
     */
    fun delayMillisAfter(consecutiveFailures: Int): Long
}

/**
 * The delay schedule Nivara starts from: two free attempts, then a delay that doubles from five
 * seconds and never exceeds five minutes.
 *
 * The properties that matter:
 *
 * - **It is a delay, not a lockout.** Nothing here can permanently lock a user out of their own
 *   data, and the delay is capped, so a legitimate user who mistypes five times waits minutes,
 *   not forever. A permanent lock belongs to a recovery design, not to a rate limiter.
 * - **It is deterministic.** The same failure count always produces the same delay, which makes
 *   it testable without a clock.
 * - **It is a deterrent, not a defence in depth.** The cost that actually protects the credential
 *   is the key-derivation work factor; throttling raises the price of guessing at the UI. An
 *   attacker who can already read and write Nivara's private files can clear the counter, so
 *   nothing else in the design depends on it.
 */
class ExponentialThrottlePolicy(
    private val freeAttempts: Int = DEFAULT_FREE_ATTEMPTS,
    private val baseDelayMillis: Long = DEFAULT_BASE_DELAY_MILLIS,
    private val maximumDelayMillis: Long = DEFAULT_MAXIMUM_DELAY_MILLIS,
) : ThrottlePolicy {

    init {
        require(freeAttempts >= 0) { "free attempts cannot be negative" }
        require(baseDelayMillis > 0L) { "base delay must be positive" }
        require(maximumDelayMillis >= baseDelayMillis) { "maximum delay cannot be below the base delay" }
    }

    override fun delayMillisAfter(consecutiveFailures: Int): Long {
        if (consecutiveFailures <= freeAttempts) return 0L
        val step = (consecutiveFailures - freeAttempts - 1).coerceAtMost(MAXIMUM_STEP)
        return (baseDelayMillis shl step).coerceIn(0L, maximumDelayMillis)
    }

    override fun toString(): String =
        "ExponentialThrottlePolicy(freeAttempts=$freeAttempts, baseDelayMillis=$baseDelayMillis, " +
            "maximumDelayMillis=$maximumDelayMillis)"

    companion object {
        /** Attempts that are free, because mistyping twice is normal. */
        const val DEFAULT_FREE_ATTEMPTS: Int = 2

        const val DEFAULT_BASE_DELAY_MILLIS: Long = 5_000L

        /** Five minutes. Long enough to make guessing tedious, short enough to live with. */
        const val DEFAULT_MAXIMUM_DELAY_MILLIS: Long = 300_000L

        /** Caps the shift so a large counter cannot overflow before the maximum is applied. */
        private const val MAXIMUM_STEP: Int = 16

        val Default: ExponentialThrottlePolicy = ExponentialThrottlePolicy()
    }
}

/**
 * The current time, injected instead of read from the platform.
 *
 * Attempt throttling needs a clock, and security code that calls `System.currentTimeMillis()`
 * directly cannot be tested for its behaviour over time — a test would have to sleep. Passing
 * the clock in keeps the tracker deterministic under test and keeps the platform call in one
 * place.
 *
 * The value is wall-clock time, not elapsed time, because a delay window has to survive a
 * restart. The trade-off is honest and documented: a user who changes the device clock can cut
 * a delay short, or extend one. That is acceptable for a rate limiter whose purpose is to make
 * guessing tedious, and it is the reason nothing treats a block as a security boundary.
 */
fun interface TimeProvider {

    /** Milliseconds since the Unix epoch. */
    fun nowMillis(): Long
}
