package com.nivara.app.ui.applock.overlay

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.nivara.app.domain.applock.AppLockOverlayPresenter
import com.nivara.app.ui.theme.NivaraTheme

/**
 * The Android window that carries the App Lock protection surface.
 *
 * ### What kind of window, and why
 *
 * A `TYPE_APPLICATION_OVERLAY` window added directly to the window manager. It is the only
 * mechanism Android offers for drawing above another application's own window, and it exists only
 * while the user has granted the overlay capability — which is exactly what the App Lock
 * preparation screen asks for. Alternatives are not merely different: a full-screen activity cannot
 * cover another application's window without stealing the task, is refused from the background on
 * API 29 and later, and can be dismissed by the system in ways an overlay cannot.
 *
 * ### What the window is configured with
 *
 * | Property | Value | Why |
 * | --- | --- | --- |
 * | type | `TYPE_APPLICATION_OVERLAY` | above other applications, below system windows |
 * | size | the whole display | the protected application must not remain reachable |
 * | `FLAG_SECURE` | set | the credential surface stays out of screenshots and recordings |
 * | focusable | yes | the password field needs the keyboard, and Back must reach the surface |
 * | `FLAG_NOT_TOUCHABLE` | not set | touches on the surface are consumed, not passed through |
 * | `PixelFormat.OPAQUE` | set | nothing of the protected application shows through |
 *
 * The surface is deliberately **not** `FLAG_NOT_TOUCH_MODAL`: everything inside the window belongs
 * to it, so a tap cannot land on the application underneath. The system's own windows — the status
 * bar, the keyboard, the Home gesture — still work, and that is intended: those are the ways out
 * that do not reveal the protected application.
 *
 * ### Lifecycle
 *
 * One window, created on attach and destroyed on detach, with a lifecycle of its own so that Compose
 * has an owner (an overlay window has no activity). Attaching twice is refused rather than
 * producing a second window, detaching is safe when nothing is attached, and both operations clean
 * up fully — composition, lifecycle and window — so nothing survives a stop and no callback can run
 * against a destroyed window.
 *
 * Nothing sensitive is held here: no credential, no session, no protected application identity. The
 * content reads the presenter's published state, and the only data in an intent out of this class
 * is Android's own "go home" action.
 */
internal class WindowManagerOverlaySurface(
    private val context: Context,
    private val presenter: AppLockOverlayPresenter,
) : OverlaySurface {

    private val windowManager: WindowManager? =
        context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    /** The attached window, or `null`. Its presence is the statement "a window exists". */
    private var attached: AttachedWindow? = null

    override fun attach(onUnexpectedDetach: () -> Unit): Boolean {
        if (attached != null) return true
        val windowManager = windowManager ?: return false

        var owner: OverlaySurfaceOwner? = null
        var root: AppLockOverlayRootView? = null
        return try {
            val createdOwner = OverlaySurfaceOwner().apply { create() }
            owner = createdOwner
            val createdRoot = AppLockOverlayRootView(context, onLeave = ::leaveToHome).apply {
                // The platform can take the window away without being asked — revoking the overlay
                // grant removes every window the application owns. Clearing the attached window
                // first keeps this object's answer in step with the screen: after this, detaching
                // is a no-op rather than a second removal.
                this.onUnexpectedDetach = {
                    attached = null
                    onUnexpectedDetach()
                }
            }
            root = createdRoot
            createdRoot.setViewTreeLifecycleOwner(createdOwner)
            createdRoot.setViewTreeViewModelStoreOwner(createdOwner)
            createdRoot.setViewTreeSavedStateRegistryOwner(createdOwner)
            createdRoot.addView(
                ComposeView(context).apply {
                    setContent {
                        val state by presenter.state.collectAsState()
                        NivaraTheme {
                            AppLockOverlaySurface(
                                state = state,
                                onCredential = presenter::submitCredential,
                                onBiometric = presenter::authenticateWithBiometric,
                                onLeave = ::leaveToHome,
                            )
                        }
                    }
                },
            )

            windowManager.addView(createdRoot, appLockOverlayLayoutParams())
            attached = AttachedWindow(windowManager = windowManager, root = createdRoot, owner = createdOwner)
            true
        } catch (refused: Exception) {
            // A refused window (the grant was revoked between the check and the call, or the window
            // manager rejected it) leaves nothing behind: whatever was created before the failure
            // is taken down here, so a `false` return can never mean a half-attached window.
            attached = null
            root?.let { createdRoot ->
                runCatching { windowManager.removeView(createdRoot) }
                createdRoot.removeAllViews()
            }
            owner?.destroy()
            false
        }
    }

    override fun detach() {
        val window = attached ?: return
        attached = null
        window.destroy()
    }

    /** Removes the window, destroys its composition, and makes the removal an expected one. */
    private fun AttachedWindow.destroy() {
        root.expectDetach()
        runCatching { windowManager.removeView(root) }
        root.removeAllViews()
        owner.destroy()
    }

    /**
     * The deliberate way out of the protection surface.
     *
     * Back, and the surface's own leave action, go to the device's home screen. That is the one
     * outcome that is neither a bypass nor a trap: the user is returned to a place where nothing is
     * protected, the launcher becomes the foreground application, detection sees it, and the surface
     * is removed by the same rule that removes it whenever the protected application is left.
     * Nothing is unlocked by leaving, and returning to the protected application raises the
     * requirement again.
     */
    private fun leaveToHome() {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

/** A window that is on screen, with everything needed to take it down again. */
private class AttachedWindow(
    val windowManager: WindowManager,
    val root: AppLockOverlayRootView,
    val owner: OverlaySurfaceOwner,
)

/**
 * The window's root view.
 *
 * Two platform behaviours are handled here and nowhere else: the Back key is intercepted so that it
 * can never fall through to the protected application, and a detach that the surface did not ask for
 * — the overlay grant being revoked removes every window the application owns — is reported instead
 * of leaving the surface believing it is still on screen.
 */
internal class AppLockOverlayRootView(
    context: Context,
    private val onLeave: () -> Unit,
) : FrameLayout(context) {

    /** Invoked when the window is detached without [expectDetach] having been called. */
    var onUnexpectedDetach: (() -> Unit)? = null

    private var detachExpected = false

    /** Marks the next detach as one this surface asked for. */
    fun expectDetach() {
        detachExpected = true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) onLeave()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (detachExpected) {
            detachExpected = false
        } else {
            onUnexpectedDetach?.invoke()
        }
    }
}

/**
 * The lifecycle, view-model store and saved-state registry a Compose view needs.
 *
 * An overlay window belongs to no activity, so there is nobody to provide these; this small owner
 * exists for exactly as long as one attached window does. It holds nothing: the store is empty
 * (the surface has no view model — its state belongs to the presenter) and the saved-state registry
 * is never asked to save anything, because nothing in the protection surface may outlive the
 * occasion it belongs to.
 */
private class OverlaySurfaceOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore get() = store

    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    /** Restores nothing and reports the owner as resumed, so whichever composition is set may run. */
    fun create() {
        // Nothing is restored: the protection surface is never restored, because a requirement
        // belongs to a moment and a restored surface would belong to none.
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    /** Ends the lifecycle and releases the store, in that order. */
    fun destroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}

/**
 * The layout of the protection window.
 *
 * Kept as a plain function so the configuration can be asserted on a device without a grant: the
 * instrumented test reads these values back and checks the type, the security flag, the size and the
 * absence of the pass-through flag, which is the part of "an overlay" that a test can honestly
 * verify without a person watching the screen.
 */
@Suppress("DEPRECATION")
internal fun appLockOverlayLayoutParams(): WindowManager.LayoutParams {
    val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // The credential surface is never in a screenshot, a screen recording or a mirror.
        WindowManager.LayoutParams.FLAG_SECURE,
        PixelFormat.OPAQUE,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Modern replacement for FLAG_LAYOUT_IN_SCREEN: fit no insets, so the surface covers
            // the whole display including the areas the system usually reserves.
            fitInsetsTypes = 0
        } else {
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }
    }
    return params
}
