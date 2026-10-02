package com.nivara.app.domain.applock

import com.nivara.app.core.common.NivaraResult

/**
 * Which application is in the foreground right now, and how that changed since the last answer.
 *
 * The detector is a *window* on the platform's usage events, not a history: it keeps only the
 * application currently in front, and each call returns the state after processing whatever
 * happened since the previous call. Nothing accumulates, and nothing is written down — a device's
 * usage record is exactly the kind of data App Lock must not hoard.
 *
 * Three outcomes, and they mean different things:
 *
 * - `Success(application)` — this application is in the foreground;
 * - `Success(null)` — nothing is known to be in the foreground (nobody has opened anything yet, or
 *   the last application was left), which is a real answer, not an error;
 * - `Failure` — the platform could not be asked at all. Missing Usage Access reaches the caller
 *   this way, so a caller can never mistake "Nivara may not look" for "nothing is running".
 */
interface ForegroundApplicationDetector {

    /**
     * Reads the platform for events since the previous call and answers with the application now
     * in the foreground.
     *
     * Successive calls are expected to be made by one monitor, in order: the detector's answer
     * depends on what it has already seen.
     */
    suspend fun foregroundApplication(): NivaraResult<ForegroundApplication?>
}
