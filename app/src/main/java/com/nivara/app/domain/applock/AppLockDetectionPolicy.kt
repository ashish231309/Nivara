package com.nivara.app.domain.applock

/**
 * How often App Lock looks, and how far back it looks when it starts.
 *
 * Android publishes usage events, but it does not offer an unprivileged callback for them: there is
 * no "an application came to the foreground" notification an ordinary application can subscribe to.
 * Any App Lock that is not an accessibility service therefore has to ask, and asking costs the
 * device work — so the interval is a decision, it is made once, and it is recorded here rather
 * than appearing as a literal in the code that polls.
 *
 * ```
 *   poll interval   1 s   worst case between an application coming forward and Nivara noticing it
 *   minimum        250 ms  the floor: anything faster is a busy loop, not detection
 *   initial window  30 s   how far back a cold detector reads to find where the user already is
 * ```
 *
 * The interval is a latency trade, not a guarantee. A slower device, a suspended process or a
 * battery manager that defers the work can all take longer, and Nivara does not claim otherwise.
 */
data class AppLockDetectionPolicy(
    val pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS,
    val initialLookbackMillis: Long = DEFAULT_INITIAL_LOOKBACK_MILLIS,
) {

    init {
        require(pollIntervalMillis >= MINIMUM_POLL_INTERVAL_MILLIS) {
            "a poll interval below $MINIMUM_POLL_INTERVAL_MILLIS ms is a busy loop, not detection"
        }
        require(initialLookbackMillis >= pollIntervalMillis) {
            "the initial window must cover at least one poll interval"
        }
    }

    companion object {

        /** Nothing faster than this is justified; four checks a second is already generous. */
        const val MINIMUM_POLL_INTERVAL_MILLIS: Long = 250L

        /** One check per second: a protected application is noticed within about that long. */
        const val DEFAULT_POLL_INTERVAL_MILLIS: Long = 1_000L

        /**
         * How far back a cold detector reads to find the application already in the foreground.
         *
         * This is also the widest window a single query may cover, which bounds the work after the
         * process has been suspended: the detector re-reads the recent past instead of walking
         * through minutes of events.
         */
        const val DEFAULT_INITIAL_LOOKBACK_MILLIS: Long = 30_000L

        /** The policy the application runs with. */
        val Default: AppLockDetectionPolicy = AppLockDetectionPolicy()
    }
}
