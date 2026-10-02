package com.nivara.app.domain.applock

import com.nivara.app.domain.security.SessionState

/**
 * What one observation of the foreground means for App Lock.
 *
 * The engine is the whole rule, and it is a pure function of four inputs plus one memory: the
 * foreground application, the protected set, the session the gate just reported, Nivara's own
 * package name, and the last decision it produced. It never reads a clock, never reads a file,
 * never asks the platform anything and never starts anything — which is what makes the behaviour
 * below testable on the JVM, and what keeps the decision out of the layer that presents it.
 *
 * ```
 *   no foreground application                     → NoProtectionRequired
 *   Nivara itself in the foreground               → NoProtectionRequired
 *   foreground application is not protected       → NoProtectionRequired
 *   protected + a valid session                   → NoProtectionRequired
 *   protected + no valid session                  → AuthenticationRequired
 * ```
 *
 * ### The two comparisons that are not obvious
 *
 * **Nivara is never protected.** Nivara's own screens are in the foreground while the user browses
 * the application, and a future App Lock prompt will be one of them. If Nivara could be treated as
 * a protected application, it would ask for authentication to show its own question. The package
 * name is injected rather than written down, so the rule is one comparison in one place and the
 * tests can state it with a name of their own choosing.
 *
 * **The session is read, never remembered.** The engine is handed whatever
 * [com.nivara.app.domain.security.SessionManager.currentState] returned, which already applies the
 * timeout rule. There is no clock here, no expiry arithmetic and no second notion of an unlocked
 * application: a session that has expired, or that Quick Lock ended, arrives as
 * [SessionState.Unauthenticated] and produces a requirement on the next observation.
 *
 * ### Repeating a requirement
 *
 * A decision is only a *new* requirement when it differs from the previous one. Staying in the same
 * protected application while the session is still closed therefore raises
 * [ProtectionEvent.AuthenticationRequired] once, not once per observation; leaving the application,
 * authenticating or switching to anything else clears the memory, so coming back raises it again.
 */
class ProtectionDecisionEngine(private val nivaraPackageName: String) {

    init {
        require(nivaraPackageName.isNotBlank()) {
            "the engine needs Nivara's own package name to exclude it from protection"
        }
    }

    @Volatile
    private var lastDecision: ProtectionDecision = ProtectionDecision.NoProtectionRequired

    /** The most recent decision, for callers that only need to look. */
    val decision: ProtectionDecision get() = lastDecision

    /**
     * Applies one observation and reports what changed.
     *
     * [session] must be the session the gate reported for this moment, not a value cached from an
     * earlier one: that is what makes an expired session, a Quick Lock or a fresh authentication
     * take effect on the very next observation.
     */
    fun observe(
        foreground: ForegroundApplication?,
        protectedApplications: Collection<ProtectedApplication>,
        session: SessionState,
    ): ProtectionEvaluation {
        val decision = decide(foreground, protectedApplications, session)
        val events = if (decision is ProtectionDecision.AuthenticationRequired &&
            decision != lastDecision
        ) {
            listOf(ProtectionEvent.AuthenticationRequired(decision.application))
        } else {
            emptyList()
        }
        lastDecision = decision
        return ProtectionEvaluation(decision = decision, events = events)
    }

    /**
     * Forgets the previous decision, so the next observation is treated as new.
     *
     * Used when detection starts, because a restarted detector has seen nothing: if a protected
     * application is in front and no session covers it, that requirement is new to this run and the
     * presentation layer must hear about it.
     */
    fun reset() {
        lastDecision = ProtectionDecision.NoProtectionRequired
    }

    private fun decide(
        foreground: ForegroundApplication?,
        protectedApplications: Collection<ProtectedApplication>,
        session: SessionState,
    ): ProtectionDecision {
        if (foreground == null) return ProtectionDecision.NoProtectionRequired
        // Nivara's own package is never a protected application, whatever the stored set says.
        if (foreground.packageName == nivaraPackageName) return ProtectionDecision.NoProtectionRequired
        if (!protectedApplications.protects(foreground.packageName)) {
            return ProtectionDecision.NoProtectionRequired
        }
        if (session.isAuthenticated) return ProtectionDecision.NoProtectionRequired
        return ProtectionDecision.AuthenticationRequired(ProtectedApplication(foreground.packageName))
    }
}

/**
 * The result of one observation: what App Lock should do, and what became newly true.
 *
 * @param decision the state of the decision after this observation.
 * @param events requirements that appeared with this observation, empty when nothing changed. The
 *   same requirement is never reported twice in a row.
 */
data class ProtectionEvaluation(
    val decision: ProtectionDecision,
    val events: List<ProtectionEvent>,
)
