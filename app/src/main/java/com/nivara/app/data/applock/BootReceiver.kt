package com.nivara.app.data.applock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restores App Lock protection after the device restarts, when the user had left it on.
 *
 * A restart ends the service, the monitor and the surface like any other process death; the
 * user's decision, however, is durable, in [ProtectionRunStateStore]. This receiver is the one
 * place that turns that decision back into a running component: on boot (and on the package
 * being replaced by an update) it reads the store and, only when it says "on", starts the
 * detection service. Nothing is ever started on a guess: a missing store, a corrupt store or an
 * "off" store all mean silence.
 *
 * The receiver can do exactly this one thing. It holds no state, reads no permission of its
 * own, starts nothing else, and a platform refusal to start from the background is swallowed on
 * purpose here — the next time the user opens Nivara, the activity's resume path restarts
 * protection from the foreground, so a refused boot start is a delay, never a loss.
 *
 * It is exported because Android delivers the boot broadcast from outside the application; the
 * broadcast itself is protected by the platform, and the component it reaches can only start
 * Nivara's own private service.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        if (!ProtectionRunStateStore(context).isEnabled()) return

        runCatching { AppLockDetectionService.start(context) }
    }
}
