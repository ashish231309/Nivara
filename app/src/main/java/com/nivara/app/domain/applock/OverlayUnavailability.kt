package com.nivara.app.domain.applock

/**
 * Why a protection requirement cannot be presented.
 *
 * A requirement exists as soon as detection says a protected application is in front and the
 * session does not cover it. Whether Nivara can *show* that requirement is a second question, and
 * this type is its answer. The two are never conflated:
 *
 * - a requirement whose surface cannot be shown is [AppLockOverlayState.Unpresentable], never
 *   [AppLockOverlayState.Idle] — the application is still protected, and saying "nothing is
 *   happening" would be a lie about the user's security;
 * - and the user is told what to fix. Only one of these reasons is under the user's control, and it
 *   is the same one they gave in the first place: Android's overlay setting.
 */
enum class OverlayUnavailability {

    /**
     * Android reports no overlay grant, so Nivara may not draw above another application.
     *
     * Nothing is silently skipped: the requirement is published as unpresentable, and the App Lock
     * preparation screen shows this as the missing prerequisite with the way to Android's setting.
     */
    NotGranted,

    /** The grant state could not be read, so Nivara cannot claim the surface is available. */
    Unavailable,

    /**
     * The grant exists, but the platform refused the window.
     *
     * Reported after a real attempt: a window manager error, or an overlay the system removed
     * without being asked. The reason is kept distinct from a missing grant because the remedy is
     * different — there is nothing for the user to switch on, and Nivara does not pretend there is.
     */
    Failed,
}
