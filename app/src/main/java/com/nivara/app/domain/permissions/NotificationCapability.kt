package com.nivara.app.domain.permissions

/**
 * The state of the one runtime permission Nivara may ask for: notifications.
 *
 * The permission serves the quiet notification App Lock shows while protection runs, and it is
 * optional: protection works without it, Android simply silences the notice.
 */
enum class NotificationCapability {
    /** The permission is granted, or the device predates the permission entirely. */
    Granted,

    /** The permission exists and is not granted. The user may grant it through a dialog. */
    NotGranted,

    /** The device predates the permission (Android 12 and older); notifications always show. */
    NotRequestable,

    /** The permission state could not be read, so nothing is claimed about it. */
    Unavailable,
}
