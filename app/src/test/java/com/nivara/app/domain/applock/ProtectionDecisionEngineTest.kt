package com.nivara.app.domain.applock

import com.nivara.app.domain.security.AuthenticationSource
import com.nivara.app.domain.security.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for the protection rule.
 *
 * This is the whole of App Lock's judgement, and every case in it is stated with plain values:
 * a package name, a set, a session. Nothing here observes Android — a test that says
 * "com.example.camera needs authentication" proves what the rule concludes, not that Android would
 * ever report that application as being in the foreground. That part is the detector's, and only a
 * device can show it.
 *
 * The engine is handed the session the gate returned, so it never evaluates an expiry itself: the
 * tests below use [SessionState.Unauthenticated], which is exactly what the gate reports once a
 * session has run out or Quick Lock has ended it. The real expiry path — clock, timer and all — is
 * exercised in `NivaraAppLockMonitorTest`, against the production session manager.
 */
class ProtectionDecisionEngineTest {

    private val engine = ProtectionDecisionEngine(nivaraPackageName = NIVARA_PACKAGE)

    private val protected = ProtectedApplication(PROTECTED_PACKAGE)
    private val protectedApplications = setOf(protected)

    private fun evaluate(
        foreground: ForegroundApplication?,
        protectedApplications: Collection<ProtectedApplication> = this.protectedApplications,
        session: SessionState = SessionState.Unauthenticated,
    ): ProtectionEvaluation = engine.observe(foreground, protectedApplications, session)

    @Test
    fun `no foreground application needs no protection`() {
        val evaluation = evaluate(foreground = null)

        assertEquals(ProtectionDecision.NoProtectionRequired, evaluation.decision)
        assertTrue(evaluation.events.isEmpty())
    }

    @Test
    fun `an unprotected application needs no protection`() {
        val evaluation = evaluate(foreground = ForegroundApplication("com.example.notes"))

        assertEquals(ProtectionDecision.NoProtectionRequired, evaluation.decision)
        assertTrue(evaluation.events.isEmpty())
    }

    @Test
    fun `a protected application without a session requires authentication`() {
        val evaluation = evaluate(foreground = ForegroundApplication(PROTECTED_PACKAGE))

        assertEquals(ProtectionDecision.AuthenticationRequired(protected), evaluation.decision)
        assertEquals(listOf(ProtectionEvent.AuthenticationRequired(protected)), evaluation.events)
    }

    @Test
    fun `a protected application with a session needs no protection`() {
        val evaluation = evaluate(
            foreground = ForegroundApplication(PROTECTED_PACKAGE),
            session = authenticated(),
        )

        assertEquals(ProtectionDecision.NoProtectionRequired, evaluation.decision)
        assertTrue(evaluation.events.isEmpty())
    }

    @Test
    fun `a session the gate has already ended requires authentication again`() {
        // Opening the session clears the requirement, and the gate reporting it closed brings the
        // requirement straight back — which is how a timeout and Quick Lock take effect here,
        // without the engine owning a clock or remembering an unlock.
        evaluate(foreground = ForegroundApplication(PROTECTED_PACKAGE), session = authenticated())

        val evaluation = evaluate(foreground = ForegroundApplication(PROTECTED_PACKAGE))

        assertEquals(ProtectionDecision.AuthenticationRequired(protected), evaluation.decision)
        assertEquals(listOf(ProtectionEvent.AuthenticationRequired(protected)), evaluation.events)
    }

    @Test
    fun `Nivara itself is never protected`() {
        val evaluation = evaluate(
            foreground = ForegroundApplication(NIVARA_PACKAGE),
            protectedApplications = setOf(ProtectedApplication(NIVARA_PACKAGE)),
        )

        assertEquals(ProtectionDecision.NoProtectionRequired, evaluation.decision)
        assertTrue(evaluation.events.isEmpty())
    }

    @Test
    fun `the requirement is raised once, not once per observation`() {
        val first = evaluate(foreground = ForegroundApplication(PROTECTED_PACKAGE))
        assertEquals(1, first.events.size)

        repeat(5) {
            val repeated = evaluate(foreground = ForegroundApplication(PROTECTED_PACKAGE))

            assertEquals(ProtectionDecision.AuthenticationRequired(protected), repeated.decision)
            assertTrue("a requirement that still holds must not be raised again", repeated.events.isEmpty())
        }
    }

    @Test
    fun `leaving the application and returning raises the requirement again`() {
        evaluate(foreground = ForegroundApplication(PROTECTED_PACKAGE))

        val away = evaluate(foreground = ForegroundApplication("com.example.notes"))
        assertEquals(ProtectionDecision.NoProtectionRequired, away.decision)

        val returned = evaluate(foreground = ForegroundApplication(PROTECTED_PACKAGE))
        assertEquals(listOf(ProtectionEvent.AuthenticationRequired(protected)), returned.events)
    }

    @Test
    fun `another protected application raises its own requirement`() {
        val other = ProtectedApplication("com.example.notes")
        val applications = setOf(protected, other)
        evaluate(foreground = ForegroundApplication(PROTECTED_PACKAGE), protectedApplications = applications)

        val evaluation = evaluate(
            foreground = ForegroundApplication("com.example.notes"),
            protectedApplications = applications,
        )

        assertEquals(ProtectionDecision.AuthenticationRequired(other), evaluation.decision)
        assertEquals(listOf(ProtectionEvent.AuthenticationRequired(other)), evaluation.events)
    }

    @Test
    fun `protecting an application while it is in front raises the requirement`() {
        val evaluation = evaluate(
            foreground = ForegroundApplication(PROTECTED_PACKAGE),
            protectedApplications = emptySet(),
        )
        assertEquals(ProtectionDecision.NoProtectionRequired, evaluation.decision)

        val nowProtected = evaluate(foreground = ForegroundApplication(PROTECTED_PACKAGE))

        assertEquals(listOf(ProtectionEvent.AuthenticationRequired(protected)), nowProtected.events)
    }

    @Test
    fun `unprotecting an application while it is in front clears the requirement`() {
        evaluate(foreground = ForegroundApplication(PROTECTED_PACKAGE))

        val evaluation = evaluate(
            foreground = ForegroundApplication(PROTECTED_PACKAGE),
            protectedApplications = emptySet(),
        )

        assertEquals(ProtectionDecision.NoProtectionRequired, evaluation.decision)
        assertTrue(evaluation.events.isEmpty())
    }

    @Test
    fun `resetting treats the next observation as new`() {
        evaluate(foreground = ForegroundApplication(PROTECTED_PACKAGE))
        engine.reset()

        val evaluation = evaluate(foreground = ForegroundApplication(PROTECTED_PACKAGE))

        assertEquals(listOf(ProtectionEvent.AuthenticationRequired(protected)), evaluation.events)
    }

    @Test
    fun `the last decision is available without observing again`() {
        assertEquals(ProtectionDecision.NoProtectionRequired, engine.decision)

        evaluate(foreground = ForegroundApplication(PROTECTED_PACKAGE))

        assertEquals(ProtectionDecision.AuthenticationRequired(protected), engine.decision)
    }

    @Test
    fun `an engine without a package name is refused`() {
        assertThrows(IllegalArgumentException::class.java) { ProtectionDecisionEngine("") }
    }

    private fun authenticated(): SessionState = SessionState.Authenticated(
        source = AuthenticationSource.Primary,
        startedAtMillis = 0L,
        expiresAtMillis = Long.MAX_VALUE,
    )

    private companion object {
        const val NIVARA_PACKAGE = "com.nivara.test"
        const val PROTECTED_PACKAGE = "com.example.camera"
    }
}
