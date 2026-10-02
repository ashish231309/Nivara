package com.nivara.app.domain.applock

/**
 * What App Lock should do about the application that is in the foreground.
 *
 * The decision is the whole of App Lock's judgement, and it is deliberately small: it says whether
 * something needs authenticating, and which application does. It does not say how — no overlay, no
 * prompt, no screen — because presenting an authentication is a later stage's work and a decision
 * that could launch a window would be a decision that is hard to test.
 *
 * [AuthenticationRequired] is not a failure and not an alarm. It means the user is looking at an
 * application they asked Nivara to protect, and Nivara is not currently unlocked, so the
 * presentation layer should ask them to authenticate. Nothing is blocked, hidden or recorded by
 * this value; it is information for the layer above.
 *
 * The decision never carries a session, a credential or a timestamp. Whether authentication is
 * required is derived from the session gate each time it is asked, never remembered here.
 */
sealed interface ProtectionDecision {

    /** Nothing in front of the user needs Nivara's authentication. */
    data object NoProtectionRequired : ProtectionDecision

    /** [application] is protected and there is no valid session covering it. */
    data class AuthenticationRequired(
        val application: ProtectedApplication,
    ) : ProtectionDecision
}
