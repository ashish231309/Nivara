package com.nivara.app.domain.applock

/**
 * Typed failures produced by the App Lock detection and configuration layers.
 *
 * Each case exists because a caller must behave differently, or because a person reading a report
 * must be able to tell two situations apart. Messages are fixed, non-secret strings: they carry no
 * package names, no paths and no platform detail, so they stay safe if they ever reach a crash
 * report. A diagnostic cause, where the platform offers one, is attached as `cause` and never shown
 * to the user.
 */
sealed class AppLockFailure(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** The stored set of protected applications could not be read. */
    data object ProtectedApplicationsUnreadable :
        AppLockFailure("the protected application set is not readable")

    /** A change to the protected application set could not be stored. */
    data object ProtectedApplicationsUnwritable :
        AppLockFailure("the protected application set could not be stored")

    /**
     * The platform could not report the foreground application.
     *
     * Used when the usage services are missing or refuse to answer. Ordinary "nothing is in the
     * foreground" is not this case — it is a successful, empty answer.
     */
    data object ForegroundUnavailable :
        AppLockFailure("the foreground application could not be determined")
}
