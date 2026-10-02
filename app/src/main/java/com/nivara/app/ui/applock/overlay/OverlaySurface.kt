package com.nivara.app.ui.applock.overlay

/**
 * The window that carries the App Lock protection surface.
 *
 * This is the seam between the part of the presentation layer that decides *when* a surface is
 * needed and the part that knows *how* Android draws one. Keeping the two apart is what makes the
 * lifecycle rules testable: [AppLockSurfaceController] — one window at a time, removal on every
 * ending, idempotent cleanup — runs against this interface, and the Android implementation beneath
 * it is a thin translation of "attach" and "detach" into window-manager calls.
 *
 * The contract is about the window only. It says nothing about what the surface contains, which is
 * the content composable's business, and nothing about authentication, which is the presenter's.
 */
internal interface OverlaySurface {

    /**
     * Attaches the surface, or reports that the platform refused.
     *
     * Must never leave a partially attached window behind: a call that returns `false` has removed
     * whatever it managed to create.
     *
     * @param onUnexpectedDetach invoked when the window goes away without [detach] being called —
     *   the overlay grant was revoked, or the platform dropped the window. It is not invoked for a
     *   detach the caller asked for.
     * @return `true` when the surface is on screen.
     */
    fun attach(onUnexpectedDetach: () -> Unit): Boolean

    /** Removes the surface. Safe to call when nothing is attached, and safe to call twice. */
    fun detach()
}
