package com.nivara.app.domain.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the session value itself.
 *
 * What matters here is that the type carries nothing it should not and answers the two questions a
 * consumer asks — is it valid, and how long is left — with the same boundary rule as the policy.
 */
class SessionStateTest {

    private val session = SessionState.Authenticated(
        source = AuthenticationSource.Primary,
        startedAtMillis = 1_000L,
        expiresAtMillis = 61_000L,
    )

    @Test
    fun `an authenticated session knows its source`() {
        assertEquals(AuthenticationSource.Primary, session.source)
        assertEquals(
            AuthenticationSource.Biometric,
            session.copy(source = AuthenticationSource.Biometric).source,
        )
    }

    @Test
    fun `the remaining time counts down and stops at zero`() {
        assertEquals(60_000L, session.remainingMillis(1_000L))
        assertEquals(30_000L, session.remainingMillis(31_000L))
        assertEquals(0L, session.remainingMillis(61_000L))
        assertEquals(0L, session.remainingMillis(120_000L))
    }

    @Test
    fun `validity uses the same half-open boundary as the policy`() {
        assertTrue(session.isValidAt(1_000L))
        assertTrue(session.isValidAt(60_999L))
        assertFalse(session.isValidAt(61_000L))
    }

    @Test
    fun `only the authenticated case reports an authenticated session`() {
        assertTrue(session.isAuthenticated)
        assertTrue(session.copy().isAuthenticated)
        assertFalse(SessionState.Unauthenticated.isAuthenticated)
    }

    @Test
    fun `sessions with the same values are equal`() {
        assertEquals(
            SessionState.Authenticated(AuthenticationSource.Primary, 1_000L, 61_000L),
            session,
        )
    }

    @Test
    fun `the source is part of the session's identity`() {
        assertNotEquals(
            SessionState.Authenticated(AuthenticationSource.Biometric, 1_000L, 61_000L),
            session,
        )
    }

    @Test
    fun `a session cannot expire before it starts`() {
        try {
            SessionState.Authenticated(AuthenticationSource.Primary, 2_000L, 1_999L)
            throw AssertionError("a session with an expiry before its start must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(true)
        }
    }

    @Test
    fun `the state carries no credential or key material`() {
        // A session is an authorization state, not a secret: the only fields are the factor, the
        // instant it started and the instant it ends. This test fails the moment someone adds a
        // fourth field — which is the point, because that field would need a justification.
        val fields = SessionState.Authenticated::class.java.declaredFields
            .map { it.name }
            .filterNot { it.startsWith("$") }
            .toSet()

        assertEquals(setOf("source", "startedAtMillis", "expiresAtMillis"), fields)
        assertEquals(session.remainingMillis(1_000L), session.remainingMillis(1_000L))
    }
}
