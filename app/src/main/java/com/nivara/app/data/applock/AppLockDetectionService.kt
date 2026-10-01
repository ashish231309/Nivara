package com.nivara.app.data.applock

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.nivara.app.NivaraApplication
import com.nivara.app.domain.applock.AppLockMonitor
import com.nivara.app.domain.applock.AppLockOverlayHost

/**
 * The Android component that lets App Lock detection run away from Nivara's own screens.
 *
 * It is a thin owner: it starts and stops the application-scoped [AppLockMonitor] and the
 * application-scoped protection surface, and it does nothing else. No detector, no decision, no
 * window and no state lives here, so there is exactly one monitoring loop and at most one surface in
 * the process no matter how often the service is started — and a service that goes away cannot leave
 * a phantom monitor or an orphaned window behind, because [onDestroy] stops both.
 *
 * Detection comes first and presentation second: the surface is started after the monitor so that a
 * requirement raised by the monitor's very first turn is already published by the time the surface
 * looks, and it is stopped first so that no window outlives the observations that justify it.
 *
 * ### Why not a foreground service
 *
 * A foreground service would keep this running when the device is otherwise idle, and it is what a
 * later stage may need once something is actually presented on screen. It is not added here,
 * deliberately:
 *
 * - nothing consumes a protection decision yet, so a permanent notification would be a cost with no
 *   visible benefit — a notification the user cannot connect to anything is worse than none;
 * - it requires permissions and a service type that must be justified by the feature they serve
 *   (`FOREGROUND_SERVICE` with a declared type, and a notification the user must be able to see);
 * - a plain started service already covers what this stage can honestly claim. Android stops a
 *   background service some minutes after the application leaves the foreground, and Nivara does
 *   not pretend otherwise.
 *
 * The upgrade is therefore a decision for the stage that draws the authentication prompt, recorded
 * in `docs/applock/README.md` together with what it must answer first.
 *
 * ### Starting it
 *
 * [start] and [stop] are explicit and take a `Context`. From Android 8 a background application may
 * not start a background service, so detection is started while Nivara is visible — which is how it
 * is used: App Lock is turned on, and the service runs from there until the platform decides to stop
 * it. `START_NOT_STICKY` is deliberate: Android will not recreate the service on its own, so
 * "detection is running" is never a surprise, and a stage that needs it to survive being killed
 * changes this line and documents the reason with it.
 *
 * The service is not exported and declares no permissions. Nothing outside Nivara can start it,
 * stop it or observe it.
 */
class AppLockDetectionService : Service() {

    /** The one monitor in the process, or `null` when the application is not Nivara. */
    private val monitor: AppLockMonitor?
        get() = (application as? NivaraApplication)?.container?.appLockMonitor

    /**
     * The one protection surface in the process.
     *
     * The service owns its lifetime and nothing else about it: what is shown, when and with what
     * result belongs to the presentation layer.
     */
    private val overlayHost: AppLockOverlayHost?
        get() = (application as? NivaraApplication)?.container?.appLockOverlayHost

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        monitor?.start()
        overlayHost?.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // However this service ended — a stop command, the platform reclaiming it, or the task
        // being removed — detection and the surface it drives both end with it. The order is the
        // reverse of the one they were started in, so nothing is left waiting for an observation
        // that will not arrive.
        overlayHost?.stop()
        monitor?.stop()
        super.onDestroy()
    }

    /** Nothing binds to this service; it is started and stopped by command. */
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {

        /**
         * Starts detection.
         *
         * Call while Nivara is visible; starting a background service from the background is
         * refused by the platform and that refusal is left to surface rather than hidden.
         */
        fun start(context: Context) {
            context.startService(Intent(context, AppLockDetectionService::class.java))
        }

        /**
         * Stops the service; its destruction stops detection.
         *
         * Safe to call when it is not running, in which case there is nothing to stop. The monitor
         * is not stopped directly here — the service owns that, so that the two cannot disagree.
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, AppLockDetectionService::class.java))
        }
    }
}
