package com.nivara.app.domain.applock

/**
 * Why App Lock detection is not running.
 *
 * Every case here means the same thing for the user's protection: Nivara cannot tell which
 * application is in front, so it cannot tell whether anything needs protecting. None of them means
 * "nothing is in the foreground" and none of them means "nothing is protected" — the two mistakes
 * this type exists to prevent.
 *
 * The reasons are kept apart because the remedy differs: a grant can be given in Android's
 * settings, an unreadable configuration waits for the settings screen's repair, and a platform that
 * will not answer is nobody's mistake.
 */
enum class DetectionUnavailability {

    /** Android has not granted Usage Access. The user can grant it in Android's own settings. */
    UsageAccessNotGranted,

    /** The grant state itself could not be read, so nothing is claimed about it. */
    UsageAccessUnavailable,

    /** The stored set of protected applications could not be read. */
    ProtectedApplicationsUnreadable,

    /** The platform could not report usage events. */
    ForegroundUnavailable,
}
