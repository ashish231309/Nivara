package com.nivara.app.ui.applock.overlay

import com.nivara.app.domain.applock.AppLockOverlayHost
import com.nivara.app.domain.applock.AppLockOverlayPresenter
import com.nivara.app.domain.applock.AppLockOverlayState
import com.nivara.app.domain.applock.ProtectionRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The lifecycle of the App Lock surface, expressed as one state machine.
 *
 * ```
 *   Idle ──(a requirement needs the surface)──▶ Showing ──(it ends)──▶ Dismissing ──▶ Idle
 *     ▲                                            │
 *     └──────────────(the platform dropped the window)───────────────┘
 * ```
 *
 * Everything here is about the *window*: whether one exists, which occasion it belongs to, and what
 * happens when it goes away. What the surface says and what an attempt means belong to the
 * [AppLockOverlayPresenter] it follows.
 *
 * ### Why a state machine rather than a flag
 *
 * "Is an overlay showing?" is the kind of question that invites `if (!isShowing) show()`, which
 * silently creates a second window the moment two callbacks arrive together. Here the answer is the
 * current value itself: [Showing] carries the occasion the window belongs to, so a repeated
 * requirement for the same occasion is recognised and changes nothing, and a requirement for a new
 * occasion reuses the window that is already there instead of adding another one.
 *
 * ### Ending, in every direction
 *
 * The surface is removed when the requirement ends (the user authenticated, left the application, or
 * the application stopped being protected), when the presenter stops, and when the platform drops
 * the window. Each of those paths goes through [dismiss], which is safe to run twice and safe to run
 * when nothing is attached — a cleanup that can be called any number of times is the only kind that
 * can be called from a service being destroyed.
 *
 * When the platform drops the window the controller does not try again by itself. A window that the
 * system removed means either the grant is gone or the window manager refused, and re-attaching in a
 * loop would be exactly the tight loop this design avoids; instead the presenter is told, the
 * requirement is published as one that could not be presented, and the next occasion — the user
 * leaving the application and coming back — is what tries again.
 */
internal class AppLockSurfaceController(
    private val surface: OverlaySurface,
    private val presenter: AppLockOverlayPresenter,
    private val scope: CoroutineScope,
) : AppLockOverlayHost {

    private val mutableState = MutableStateFlow<AppLockSurfaceState>(AppLockSurfaceState.Idle)

    /** The window's lifecycle, for tests and for anything that needs to observe it. */
    val state: StateFlow<AppLockSurfaceState> = mutableState.asStateFlow()

    /** Guards the lifecycle and every window operation. */
    private val lifecycleLock = Any()

    private var generation: Long = 0L

    /** The collector started by [start], or `null` when the controller is not running. */
    private var collector: Job? = null

    /**
     * The occasion the attached window belongs to, or `null` when no window is attached.
     *
     * A non-null value *is* the statement "there is a window"; the platform's detach callback is
     * told apart from our own removal by whether this value was already cleared.
     */
    private var attachedRequest: ProtectionRequest? = null

    override fun start() {
        synchronized(lifecycleLock) {
            if (collector?.isActive == true) return
            generation += 1
            val runGeneration = generation
            presenter.start()
            collector = scope.launch {
                presenter.state.collect { overlayState -> apply(runGeneration, overlayState) }
            }
        }
    }

    override fun stop() {
        synchronized(lifecycleLock) {
            generation += 1
            collector?.cancel()
            collector = null
            attachedRequest = null
            mutableState.value = AppLockSurfaceState.Idle
        }
        // Outside the lock: the window manager takes the main thread, and the platform's detach
        // callback must not be able to run into a lock we are holding.
        surface.detach()
        presenter.stop()
    }

    /** Reacts to one published state of the presenter. */
    private fun apply(runGeneration: Long, overlayState: AppLockOverlayState) {
        when (overlayState) {
            is AppLockOverlayState.Required -> show(runGeneration, overlayState.request)
            AppLockOverlayState.Idle,
            is AppLockOverlayState.Unpresentable,
            -> dismiss(runGeneration)
        }
    }

    /** Ensures a window is attached for [request], without ever attaching a second one. */
    private fun show(runGeneration: Long, request: ProtectionRequest) {
        val attach = synchronized(lifecycleLock) {
            if (generation != runGeneration) return
            val current = mutableState.value
            if (current is AppLockSurfaceState.Showing && current.request == request) {
                // The same occasion, already on screen. This is the de-duplication: a repeated
                // requirement cannot create a second window because there is nothing to create.
                return
            }
            if (attachedRequest != null) {
                // The window stays; only the occasion it belongs to changed, and the content
                // follows the presenter rather than the window being rebuilt.
                attachedRequest = request
                mutableState.value = AppLockSurfaceState.Showing(request)
                return
            }
            true
        }
        if (!attach) return

        val attached = surface.attach(onUnexpectedDetach = ::onSurfaceDetached)
        synchronized(lifecycleLock) {
            if (generation != runGeneration) {
                // The controller was stopped while the window was being created. Nothing may be
                // left behind, so the window that was just attached is removed again.
                if (attached) surface.detach()
                return
            }
            if (attached) {
                attachedRequest = request
                mutableState.value = AppLockSurfaceState.Showing(request)
            }
        }
        if (!attached) {
            // Reported outside the lock: the presenter publishes a state, which this controller is
            // collecting, and that collection must not run into a lock held here.
            presenter.onSurfaceFailed(request.id)
        }
    }

    /** Removes the window if one is attached. Idempotent. */
    private fun dismiss(runGeneration: Long) {
        synchronized(lifecycleLock) {
            if (generation != runGeneration) return
            val pending = mutableState.value.pendingRequest() ?: return
            mutableState.value = AppLockSurfaceState.Dismissing(pending)
            // Cleared before the window is touched, so the platform's detach callback recognises
            // this as the removal we asked for rather than as a window that went away on its own.
            attachedRequest = null
        }
        surface.detach()
        synchronized(lifecycleLock) {
            if (generation == runGeneration && mutableState.value is AppLockSurfaceState.Dismissing) {
                mutableState.value = AppLockSurfaceState.Idle
            }
        }
    }

    /** The platform removed the window without being asked. */
    private fun onSurfaceDetached() {
        val request = synchronized(lifecycleLock) {
            val attached = attachedRequest ?: return
            attachedRequest = null
            mutableState.value = AppLockSurfaceState.Dismissing(attached)
            attached
        }
        synchronized(lifecycleLock) {
            if (mutableState.value is AppLockSurfaceState.Dismissing) {
                mutableState.value = AppLockSurfaceState.Idle
            }
        }
        presenter.onSurfaceFailed(request.id)
    }

    /** The request a state belongs to, or `null` for [AppLockSurfaceState.Idle]. */
    private fun AppLockSurfaceState.pendingRequest(): ProtectionRequest? = when (this) {
        AppLockSurfaceState.Idle -> null
        is AppLockSurfaceState.Showing -> request
        is AppLockSurfaceState.Dismissing -> request
    }
}

/**
 * The window's lifecycle.
 *
 * Three values, because that is how many the controller can be in: nothing on screen, a window on
 * screen for an occasion, and a window being removed. "Authenticating" is deliberately not here —
 * that is what the surface is *waiting for*, which the presenter publishes, and a second copy of it
 * in the window's lifecycle would be a second answer to one question.
 */
internal sealed interface AppLockSurfaceState {

    /** No window is attached. */
    data object Idle : AppLockSurfaceState

    /** A window is attached and belongs to [request]. */
    data class Showing(val request: ProtectionRequest) : AppLockSurfaceState

    /** A window is being removed; it belonged to [request]. */
    data class Dismissing(val request: ProtectionRequest) : AppLockSurfaceState
}
