package com.nivara.app.domain.applock

/**
 * Something App Lock noticed that the layer above may need to react to.
 *
 * Events are one-shot triggers, not state. The authoritative answer to "does anything need
 * protecting right now" is always the monitor's current state, and a consumer that starts late
 * reads the state first and treats events only as a nudge. That order is what keeps a missed event
 * from becoming a missed protection decision.
 *
 * An event is raised when a requirement *appears*: a protected application came forward and there
 * is no session covering it. The same requirement is not repeated while it still holds — the
 * repeat would turn into a storm of authentication prompts once a presentation layer exists.
 */
sealed interface ProtectionEvent {

    /**
     * [application] is in the foreground and needs authentication.
     *
     * Raised once per entry into a protected application that no valid session covers. Leaving the
     * application, authenticating, or a session ending raises it again when the situation repeats,
     * because those are genuinely new situations rather than repetitions of the same one.
     */
    data class AuthenticationRequired(
        val application: ProtectedApplication,
    ) : ProtectionEvent
}
