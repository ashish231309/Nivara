package com.nivara.app.data.applock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.nivara.app.NivaraApplication
import com.nivara.app.R
import com.nivara.app.domain.applock.AppLockMonitor
import com.nivara.app.domain.applock.AppLockOverlayHost

/**
 * The Android component that lets App Lock detection run away from Nivara's own screens.
 *
 * It is a thin owner: it starts and stops the application-scoped [AppLockMonitor] and the
 * application-scoped protection surface, and it does nothing else. No detector, no decision,
 * no window and no state lives here, so there is exactly one monitoring loop and at most one
 * surface in the process no matter how often the service is started — and a service that goes
 * away cannot leave a phantom monitor or an orphaned window behind, because [onDestroy] stops
 * both.
 *
 * Detection comes first and presentation second: the surface is started after the monitor so
 * that a requirement raised by the monitor's very first turn is already published by the time
 * the surface looks, and it is stopped first so that no window outlives the observations that
 * justify it.
 *
 * ### Why a foreground service now
 *
 * Protection only means something while the user is inside the protected application — exactly
 * when Nivara is not on screen — and the platform stops a plain background service within
 * minutes of that, and always on a restart. The service therefore runs as a foreground service
 * of type `specialUse`, announced by one quiet, low-importance notification the user can see
 * and dismiss the channel of. This is the justification the earlier stage deferred: something
 * is now presented on screen (the protection surface), so the notification has a visible
 * meaning — "App Lock is watching" — and the battery exemption the onboarding asks for keeps
 * the platform from deferring it anyway. `START_NOT_STICKY` remains: Android never recreates
 * the service on its own; the boot receiver and the activity's resume path restore it from the
 * durable run-state instead, so "detection is running" is always a decision, never a surprise.
 *
 * If the platform refuses the foreground promotion — an OEM build, a start racing a
 * restriction — the refusal is swallowed inside [promoteToForeground] and the service keeps
 * running as the plain started service it was before: some protection is honestly better than
 * none, and the next start from the foreground promotes it again.
 *
 * ### Starting it
 *
 * [start] and [stop] are explicit and take a `Context`. Starting a foreground service from the
 * background is restricted, which is why the three callers are all foreground or exempt paths:
 * a visible screen turning protection on, the activity's resume, and the boot receiver under
 * the battery exemption. A refusal surfaces to the caller as a platform exception, except at
 * boot, where it is a delay rather than a loss.
 *
 * The service is not exported and declares no permissions of its own. Nothing outside Nivara
 * can start it, stop it or observe it.
 */
class AppLockDetectionService : Service() {

    /** The one monitor in the process, or `null` when the application is not Nivara. */
    private val monitor: AppLockMonitor?
        get() = (application as? NivaraApplication)?.container?.appLockMonitor

    /**
     * The one protection surface in the process.
     *
     * The service owns its lifetime and nothing else about it: what is shown, when and with
     * what result belongs to the presentation layer.
     */
    private val overlayHost: AppLockOverlayHost?
        get() = (application as? NivaraApplication)?.container?.appLockOverlayHost

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        monitor?.start()
        overlayHost?.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // However this service ended — a stop command, the platform reclaiming it, or the task
        // being removed — detection and the surface it drives both end with it. The order is
        // the reverse of the one they were started in, so nothing is left waiting for an
        // observation that will not arrive.
        overlayHost?.stop()
        monitor?.stop()
        super.onDestroy()
    }

    /** Nothing binds to this service; it is started and stopped by command. */
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Promotes the service to the foreground with the one notification App Lock shows.
     *
     * The channel is created here, idempotently, right before it is used, so no other component
     * has to know about notifications. A platform refusal degrades to the plain started
     * service rather than taking protection down entirely.
     */
    private fun promoteToForeground() {
        runCatching {
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            notificationManager.createNotificationChannel(protectionChannel())

            val notification = Notification.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shield_check)
                .setContentTitle(getString(R.string.protection_notification_title))
                .setContentText(getString(R.string.protection_notification_text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()

            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun protectionChannel(): NotificationChannel = NotificationChannel(
        CHANNEL_ID,
        getString(R.string.protection_channel_name),
        NotificationManager.IMPORTANCE_LOW,
    ).apply {
        description = getString(R.string.protection_channel_description)
    }

    companion object {
        private const val CHANNEL_ID = "nivara-protection"
        private const val NOTIFICATION_ID = 1

        /**
         * Starts detection as a foreground service.
         *
         * `startForegroundService` is what the platform expects for a service that promotes
         * itself; on a device that refuses the foreground start from the calling context, the
         * plain started service is tried so that a restriction degrades protection rather than
         * removing it. A remaining refusal is left to surface to the caller.
         */
        fun start(context: Context) {
            val intent = Intent(context, AppLockDetectionService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (refused: IllegalStateException) {
                context.startService(intent)
            }
        }

        /**
         * Stops the service; its destruction stops detection.
         *
         * Safe to call when it is not running, in which case there is nothing to stop. The
         * monitor is not stopped directly here — the service owns that, so the two cannot
         * disagree.
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, AppLockDetectionService::class.java))
        }
    }
}
