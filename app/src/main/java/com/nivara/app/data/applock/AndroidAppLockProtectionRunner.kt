package com.nivara.app.data.applock

import android.content.Context
import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.nivaraRunCatching
import com.nivara.app.domain.applock.AppLockProtectionRunner

/**
 * [AppLockProtectionRunner] backed by the platform component that owns detection.
 *
 * The runner is deliberately this small. Protection is a foreground service, and starting or
 * stopping it from a visible screen is permitted; doing so from the background is restricted,
 * which is why the service is started when the user is looking at Nivara, on boot through the
 * boot receiver, and on every return to the activity — never from anywhere else. All three
 * starts are idempotent at the platform level: starting a running service delivers another
 * start command to the same instance, and stopping a stopped one does nothing.
 *
 * The runner is also the one writer of the durable run-state: the user's on/off decision is
 * recorded through [ProtectionRunStateStore] exactly when it is made, so a restarted device
 * knows what to restore without guessing.
 *
 * The application context is held, never an activity: the service must outlive whichever screen
 * asked for it.
 *
 * A platform refusal — a restricted background start, a component an OEM build has disabled — is
 * reported as a failure rather than thrown at a click handler or swallowed. Either would be worse
 * than useless here: one takes the screen down, and the other would let the switch claim protection
 * that is not running.
 */
internal class AndroidAppLockProtectionRunner(
    private val context: Context,
    private val runStateStore: ProtectionRunStateStore,
) : AppLockProtectionRunner {

    override fun start(): NivaraResult<Unit> = nivaraRunCatching {
        runStateStore.setEnabled(true)
        AppLockDetectionService.start(context)
    }

    override fun stop(): NivaraResult<Unit> = nivaraRunCatching {
        runStateStore.setEnabled(false)
        AppLockDetectionService.stop(context)
    }
}
