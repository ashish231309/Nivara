package com.nivara.app.domain.permissions

/**
 * Android's answer to "may Nivara draw above another application?".
 *
 * The App Lock protection surface is an overlay window, and an overlay window is a capability the
 * user grants in Android's own settings — not a runtime permission with a dialog. This type is what
 * that setting currently says.
 *
 * ### Why the surface needs it
 *
 * Android has no unprivileged way to put a window above another application's window. An overlay
 * window is the mechanism the platform provides for exactly this case, and it exists only with this
 * grant. Alternatives are worse, not merely different: an activity started from the background is
 * refused on API 29 and later unless the app holds this grant anyway, an accessibility service is a
 * far broader capability (it reads the screen), and a notification cannot block anything.
 *
 * ### Three answers, never two
 *
 * [Unavailable] is not a softer [NotGranted]. It means the question could not be asked, or the
 * platform cannot support the capability — and it is kept separate so that a screen never presents
 * an unknown capability as a refusal, and never presents either as a grant. In particular, a
 * missing grant is never the same statement as "nothing needs protecting": detection decides what
 * needs protecting, and this capability decides whether Nivara may show it.
 */
enum class OverlayCapability {

    /** Android reports that Nivara may draw above other applications. */
    Granted,

    /** Android reports no grant. The user changes this in Android's overlay settings. */
    NotGranted,

    /** The grant state could not be read, so nothing is claimed about it. */
    Unavailable,
}
