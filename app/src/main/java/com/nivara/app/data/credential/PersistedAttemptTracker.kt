package com.nivara.app.data.credential

import com.nivara.app.core.common.valueOrNull
import com.nivara.app.domain.credential.AttemptStore
import com.nivara.app.domain.credential.AttemptTracker
import com.nivara.app.domain.credential.ExponentialThrottlePolicy
import com.nivara.app.domain.credential.ThrottlePolicy
import com.nivara.app.domain.credential.ThrottleState
import com.nivara.app.domain.credential.TimeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Attempt tracking backed by a small file, so a delay survives the process being killed.
 *
 * Three decisions worth stating:
 *
 * - **The counter only resets on success.** A delay expiring does not forgive the failures that
 *   caused it, otherwise an attacker could wait out the first delay and start from a clean slate
 *   for free. Because the delay is capped, this cannot grow into a permanent lock.
 * - **Storage trouble is never fatal.** A read that fails is treated as "nothing recorded" and a
 *   write that fails still returns the state it computed, so throttling can never turn into a
 *   lockout. Nothing in the design depends on the counters being intact.
 * - **Time comes from a [TimeProvider].** The tests advance a clock instead of sleeping.
 */
internal class PersistedAttemptTracker(
    private val store: AttemptStore,
    private val timeProvider: TimeProvider,
    private val policy: ThrottlePolicy = ExponentialThrottlePolicy.Default,
) : AttemptTracker {

    override suspend fun currentState(): ThrottleState = loadState()

    override suspend fun remainingBlockMillis(): Long =
        loadState().remainingBlockMillis(timeProvider.nowMillis())

    override suspend fun recordFailure(): ThrottleState {
        val failures = loadState().consecutiveFailures + 1
        val next = ThrottleState(
            consecutiveFailures = failures,
            blockedUntilMillis = timeProvider.nowMillis() + policy.delayMillisAfter(failures),
        )
        // The result is intentionally not propagated: a failed write may make throttling more
        // forgiving, but it must not fail the authentication the caller is performing.
        withContext(Dispatchers.IO) { store.save(next) }
        return next
    }

    override suspend fun recordSuccess() {
        withContext(Dispatchers.IO) { store.save(ThrottleState.Clear) }
    }

    private suspend fun loadState(): ThrottleState = withContext(Dispatchers.IO) {
        store.load().valueOrNull() ?: ThrottleState.Clear
    }
}
