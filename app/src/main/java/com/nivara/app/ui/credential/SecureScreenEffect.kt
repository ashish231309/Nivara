package com.nivara.app.ui.credential

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Marks the current window as sensitive while this composable is on screen.
 *
 * Setting `FLAG_SECURE` asks the system to keep the window out of screenshots, out of screen
 * recordings and off the recents thumbnail — which is where a typed PIN most often escapes.
 *
 * What it does and does not cover, stated plainly:
 *
 * - On most devices it blocks the platform screenshot and screen-record paths, and blanks the
 *   window in the recent-tasks preview. Some manufacturers ignore parts of it, and a device that
 *   is rooted, or running a custom system image, can bypass it entirely.
 * - It does **not** stop a second camera pointed at the screen, an accessibility service that
 *   reads the view hierarchy, or a keyboard (IME) that logs what was typed. Nivara avoids the
 *   last one for the PIN by never showing a keyboard for it.
 * - The flag is applied and removed by the screen itself, so it is exactly as long-lived as the
 *   credential UI.
 *
 * This is the practical mitigation, not a guarantee, and it is documented as such.
 */
@Composable
fun SecureScreenEffect() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

/** Walks up the context wrappers to the hosting activity, or `null` when there is none. */
private fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
