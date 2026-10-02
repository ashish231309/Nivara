package com.nivara.app.domain.permissions

/**
 * Android's answer to "may Nivara read usage statistics?".
 *
 * Usage Access is not a runtime permission. There is no dialog Nivara may show for it, and no call
 * that could turn it on: the user grants it in Android's own settings screen, and this type is what
 * that setting currently says.
 *
 * [Unavailable] is not a softer [NotGranted]. It means the question could not be asked — the
 * platform service was missing or the check failed — and it is kept separate so a screen can never
 * present an unknown capability as a refusal, or worse, as a grant.
 */
enum class UsageAccessStatus {

    /** Android reports the grant. */
    Granted,

    /** Android reports no grant. The user can change this in Android's Usage Access settings. */
    NotGranted,

    /** The grant state could not be read, so nothing is claimed about it. */
    Unavailable,
}
