package com.nivara.app.domain.applock

/**
 * The component that puts the App Lock protection surface on screen.
 *
 * It is the presentation layer's own component, and it is defined here as a contract for one
 * reason: the platform component that keeps detection alive has to start and stop it, and that
 * component must not know how a window is drawn. Everything below the contract — the window, its
 * flags, its content and its lifecycle — belongs to the implementation.
 *
 * ### What the implementation must guarantee
 *
 * - **At most one surface.** However many times [start] is called, and however often detection
 *   repeats itself, there is never more than one window.
 * - **It follows the presenter rather than deciding.** The surface is shown while a requirement
 *   needs it and removed when that requirement ends; the implementation never invents a reason to
 *   show or hide one.
 * - **Cleanup is deterministic and idempotent.** [stop] removes the surface and releases the window,
 *   and calling it again — or calling it without [start] — does nothing and cannot throw. A stopped
 *   host leaves no window behind and no callback that could touch one.
 * - **Failure is reported, never swallowed.** If the window cannot be attached, the presenter is
 *   told, so the requirement is published as one that could not be presented instead of silently
 *   not being presented.
 */
interface AppLockOverlayHost {

    /**
     * Starts following the protection requirements and showing the surface when one needs it.
     *
     * Idempotent: a second call while it is running changes nothing.
     */
    fun start()

    /**
     * Removes the surface, if any, and stops following.
     *
     * Safe to call repeatedly and safe to call when nothing was started.
     */
    fun stop()
}
