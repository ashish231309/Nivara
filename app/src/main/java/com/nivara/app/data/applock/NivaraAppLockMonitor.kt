package com.nivara.app.data.applock

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.domain.applock.AppLockDetectionPolicy
import com.nivara.app.domain.applock.AppLockMonitor
import com.nivara.app.domain.applock.AppLockState
import com.nivara.app.domain.applock.DetectionUnavailability
import com.nivara.app.domain.applock.ForegroundApplicationDetector
import com.nivara.app.domain.applock.ProtectedApplicationRepository
import com.nivara.app.domain.applock.ProtectionDecisionEngine
import com.nivara.app.domain.applock.ProtectionEvent
import com.nivara.app.domain.permissions.UsageAccessRepository
import com.nivara.app.domain.permissions.UsageAccessStatus
import com.nivara.app.domain.security.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The production [AppLockMonitor]: one loop, one state, no history.
 *
 * Each turn of the loop asks four questions in a fixed order and publishes one answer:
 *
 * ```
 *   Usage Access?            missing or unreadable → Unavailable, and the loop keeps checking
 *   protected applications?  unreadable            → Unavailable (fail closed, never "none")
 *   foreground application?  platform refused      → Unavailable
 *   otherwise                → the decision engine's answer for the application in front
 * ```
 *
 * ### Why it keeps looking when it cannot decide
 *
 * The loop does not stop when a prerequisite is missing. Usage Access is granted in Android's own
 * settings screen, which means the user can fix it without returning to Nivara, and a monitor that
 * gave up would leave protection off until something restarted it. The interval is the same as
 * when detection succeeds, so the cost of waiting to recover is one cheap check per interval.
 *
 * ### What it does not do
 *
 * It does not authenticate, lock, prompt, draw, start an activity, or write anything anywhere. It
 * does not hold a session: every turn reads the session gate, so an expired session or a Quick Lock
 * is reflected on the next observation with nothing to invalidate here. It does not hold the
 * protected set either — the repository is asked again each turn, which is how a change made while
 * the monitor is running takes effect without a notification mechanism.
 *
 * ### One loop, whoever asks
 *
 * [start] is idempotent, and [stop] cancels the loop and returns the state to
 * [AppLockState.Stopped]. A loop that is still unwinding when the monitor is stopped or restarted
 * cannot publish: each run carries a generation, and only the current generation is allowed to
 * write the state or raise an event. That is what stops a stopped monitor from reporting a decision
 * it took a moment earlier.
 */
internal class NivaraAppLockMonitor(
    private val detector: ForegroundApplicationDetector,
    private val protectedApplications: ProtectedApplicationRepository,
    private val usageAccess: UsageAccessRepository,
    private val sessionManager: SessionManager,
    private val decisions: ProtectionDecisionEngine,
    private val policy: AppLockDetectionPolicy = AppLockDetectionPolicy.Default,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AppLockMonitor {

    private val mutableState = MutableStateFlow<AppLockState>(AppLockState.Stopped)

    /**
     * Requirements as they appear.
     *
     * One slot, newest wins: the state carries the current truth, so an event that cannot be
     * delivered immediately is not lost information — a consumer reads the state anyway.
     */
    private val mutableEvents = MutableSharedFlow<ProtectionEvent>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val state: StateFlow<AppLockState> = mutableState.asStateFlow()

    override val events: Flow<ProtectionEvent> = mutableEvents.asSharedFlow()

    /** Guards the loop's lifetime and every publication that loop makes. */
    private val lifecycleLock = Any()

    private var loop: Job? = null

    /**
     * Identifies the current run.
     *
     * Bumped on every start and every stop, so a coroutine that outlives its own run — cancellation
     * is cooperative, and an observation may be in flight when it arrives — is recognised as stale
     * and can neither publish a state nor raise an event.
     */
    private var generation: Long = 0L

    override fun start() {
        synchronized(lifecycleLock) {
            if (loop?.isActive == true) return
            generation += 1
            // Read once, here: the coroutine must carry the generation it was started with, not
            // whatever the field holds by the time it happens to be scheduled.
            val runGeneration = generation
            // A fresh run saw nothing yet, so conditions that were already true are new to it.
            decisions.reset()
            loop = scope.launch { observeUntilStopped(runGeneration) }
        }
    }

    override fun stop() {
        synchronized(lifecycleLock) {
            loop?.cancel()
            loop = null
            generation += 1
            mutableState.value = AppLockState.Stopped
        }
    }

    private suspend fun observeUntilStopped(runGeneration: Long) {
        while (true) {
            observeOnce(runGeneration)
            delay(policy.pollIntervalMillis)
        }
    }

    /** One turn of the loop: gather the four answers and publish what they mean. */
    private suspend fun observeOnce(runGeneration: Long) {
        val usageAccessStatus = usageAccess.status()
        if (usageAccessStatus != UsageAccessStatus.Granted) {
            publish(
                runGeneration,
                AppLockState.Unavailable(
                    when (usageAccessStatus) {
                        UsageAccessStatus.NotGranted -> DetectionUnavailability.UsageAccessNotGranted
                        else -> DetectionUnavailability.UsageAccessUnavailable
                    },
                ),
            )
            return
        }

        val protected = when (val result = protectedApplications.protectedApplications()) {
            is NivaraResult.Success -> result.value
            is NivaraResult.Failure -> {
                publish(
                    runGeneration,
                    AppLockState.Unavailable(DetectionUnavailability.ProtectedApplicationsUnreadable),
                )
                return
            }
        }

        val foreground = when (val result = detector.foregroundApplication()) {
            is NivaraResult.Success -> result.value
            is NivaraResult.Failure -> {
                publish(
                    runGeneration,
                    AppLockState.Unavailable(DetectionUnavailability.ForegroundUnavailable),
                )
                return
            }
        }

        // Read through the gate, so a session that ran out since the last turn is already closed
        // by the time the decision is made.
        val session = sessionManager.currentState()
        val evaluation = decisions.observe(foreground, protected, session)

        publish(runGeneration, AppLockState.Monitoring(foreground, evaluation.decision))
        evaluation.events.forEach { event ->
            synchronized(lifecycleLock) {
                if (generation == runGeneration) mutableEvents.tryEmit(event)
            }
        }
    }

    /** Publishes [state], unless this run has been stopped or replaced by a newer one. */
    private fun publish(runGeneration: Long, state: AppLockState) {
        synchronized(lifecycleLock) {
            if (generation != runGeneration) return
            mutableState.value = state
        }
    }
}