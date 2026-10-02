package com.nivara.app.domain.permissions

/**
 * Android's answer to "is Nivara exempt from battery optimization?".
 *
 * App Lock's protection service only survives while the device is otherwise busy when the
 * platform is told not to defer Nivara's background work. The exemption is granted by the user —
 * either through Android's own confirmation dialog or its battery settings — never by Nivara.
 *
 * [Unavailable] is not a softer [NotGranted]. It means the question could not be asked — the
 * platform service was missing or the check failed — and it is kept separate so a screen can
 * never present an unknown state as a refusal, or worse, as a grant.
 */
enum class BatteryOptimizationStatus {
    /** Android reports that Nivara is exempt from battery optimization. */
    Granted,

    /** Android reports no exemption. The user can change this in Android's battery settings. */
    NotGranted,

    /** The exemption state could not be read, so nothing is claimed about it. */
    Unavailable,
}
