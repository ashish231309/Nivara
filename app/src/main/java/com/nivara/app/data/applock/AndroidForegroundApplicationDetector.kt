package com.nivara.app.data.applock

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.nivaraRunCatching
import com.nivara.app.domain.applock.AppLockDetectionPolicy
import com.nivara.app.domain.applock.AppLockFailure
import com.nivara.app.domain.applock.ForegroundApplication
import com.nivara.app.domain.applock.ForegroundApplicationDetector
import com.nivara.app.domain.credential.TimeProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [ForegroundApplicationDetector] built on Android's usage events.
 *
 * ### Why events and not a status query
 *
 * `UsageStatsManager` offers two ways to look: aggregate statistics, which answer "which
 * applications were used over this interval" and are a summary of the device's history, and usage
 * events, which are the individual transitions. Only the second one can say *when* an application
 * came to the front, which is exactly the question App Lock asks — so this class reads events and
 * never reads aggregate statistics.
 *
 * ### Why asking repeatedly
 *
 * Android has no unprivileged callback for usage events: an ordinary application cannot subscribe
 * to "an application came to the foreground", and the APIs that would offer one belong to
 * privileged or accessibility-level components that Nivara does not use. The detector is therefore
 * called on the interval in [AppLockDetectionPolicy], and each call reads only what happened since
 * the previous one.
 *
 * ### What is kept
 *
 * The application currently in front, and the timestamp the last query reached. Nothing else — no
 * event history, no per-application state, no file, no preference. A window that is skipped, because
 * the process was suspended or the clock moved, is recovered by re-reading the recent past rather
 * than by growing a buffer.
 *
 * ### When it cannot answer
 *
 * Without Usage Access the platform returns no events, which is indistinguishable from a quiet
 * device. The detector does not pretend otherwise: the monitor asks for the grant first, and a
 * failure to reach the services is reported as [AppLockFailure.ForegroundUnavailable] rather than
 * as an empty observation.
 */
class AndroidForegroundApplicationDetector(
    private val context: Context,
    private val timeProvider: TimeProvider,
    private val policy: AppLockDetectionPolicy = AppLockDetectionPolicy.Default,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ForegroundApplicationDetector {

    /** The end of the previous query, or `null` before the first one. */
    private var lastQueryEndMillis: Long? = null

    /** The application currently believed to be in front. The only state the detector keeps. */
    private var currentApplication: ForegroundApplication? = null

    override suspend fun foregroundApplication(): NivaraResult<ForegroundApplication?> =
        withContext(dispatcher) {
            nivaraRunCatching {
                val now = timeProvider.nowMillis()
                val usageManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                    ?: throw AppLockFailure.ForegroundUnavailable
                val events: UsageEvents? = usageManager.queryEvents(windowStart(now), now)
                if (events == null) throw AppLockFailure.ForegroundUnavailable
                lastQueryEndMillis = now
                currentApplication = readForeground(events)
                currentApplication
            }
        }

    /**
     * Where this query starts: the end of the last one, or the recent past on a cold start.
     *
     * The window is never wider than the policy's lookback, so a process that was suspended for a
     * long time re-reads a bounded slice of the recent past instead of walking through everything
     * that happened meanwhile. A clock that moved backwards cannot produce a reversed window
     * either: an unusable cursor is simply replaced by the lookback.
     */
    private fun windowStart(nowMillis: Long): Long {
        val earliest = nowMillis - policy.initialLookbackMillis
        val cursor = lastQueryEndMillis
        return if (cursor != null && cursor in earliest..nowMillis) cursor else earliest
    }

    /**
     * Folds the events in the window into the application now in front.
     *
     * Events are read out first and then applied in timestamp order, so the newest relevant
     * transition decides the answer even if the platform ever returned a window out of order. Only
     * the two foreground transitions are kept, so the list that is sorted is a handful of entries
     * from one interval — not a device history.
     */
    private fun readForeground(events: UsageEvents): ForegroundApplication? {
        val transitions = ArrayList<ForegroundTransition>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val signal = foregroundSignalForEventType(event.eventType)
            if (signal == ForegroundSignal.Ignored) continue
            transitions += ForegroundTransition(
                timeStampMillis = event.timeStamp,
                signal = signal,
                packageName = event.packageName,
            )
        }
        return resolveForeground(currentApplication, transitions)
    }
}
