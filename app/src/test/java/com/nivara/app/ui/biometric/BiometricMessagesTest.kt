package com.nivara.app.ui.biometric

import com.nivara.app.R
import com.nivara.app.domain.security.BiometricAuthenticationOutcome
import com.nivara.app.domain.security.BiometricFailure
import com.nivara.app.domain.security.BiometricStatus
import com.nivara.app.domain.security.BiometricUnavailability
import com.nivara.app.ui.components.NivaraMessage
import com.nivara.app.ui.components.secondsFromMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for what the screen tells the user.
 *
 * These are pure mappings from a typed state to a string resource, so they can be checked on the
 * JVM. What they protect is the distinction the stage insists on: Nivara's own pause, Android's
 * lockout, a rejected biometric and a cancellation must not be described with the same words, and
 * Android's own reason for being unavailable must be the reason the user is given.
 *
 * The wording itself is not asserted — that would make every copy edit a test failure — only that
 * each state maps somewhere, that the states that must differ do differ, and that the formatted
 * message carries its number.
 */
class BiometricMessagesTest {

    @Test
    fun `success has no message while every other outcome does`() {
        assertNull(BiometricAuthenticationOutcome.Succeeded.toMessage())

        val outcomes = listOf(
            BiometricAuthenticationOutcome.Failed(attemptsRemaining = 0, blockedForMillis = 0L),
            BiometricAuthenticationOutcome.Cancelled,
            BiometricAuthenticationOutcome.TemporarilyBlocked(retryAfterMillis = 30_000L),
            BiometricAuthenticationOutcome.SystemBlocked(permanent = false),
            BiometricAuthenticationOutcome.SystemBlocked(permanent = true),
            BiometricAuthenticationOutcome.NotEnabled,
            BiometricAuthenticationOutcome.Invalidated,
            BiometricAuthenticationOutcome.Unavailable(BiometricUnavailability.NoHardware),
        )

        for (outcome in outcomes) {
            assertTrue("$outcome should be reported to the user", outcome.toMessage() != null)
        }
    }

    @Test
    fun `Nivara's pause and Android's lockout are never the same message`() {
        val pause = BiometricAuthenticationOutcome.TemporarilyBlocked(retryAfterMillis = 30_000L).toMessage()
        val lockout = BiometricAuthenticationOutcome.SystemBlocked(permanent = false).toMessage()
        val permanent = BiometricAuthenticationOutcome.SystemBlocked(permanent = true).toMessage()
        val rejection = BiometricAuthenticationOutcome.Failed(attemptsRemaining = 4, blockedForMillis = 0L).toMessage()

        assertNotEquals(pause, lockout)
        assertNotEquals(lockout, permanent)
        assertNotEquals(rejection, pause)
        assertNotEquals(rejection, lockout)
    }

    @Test
    fun `Nivara's pause carries the number of seconds to wait`() {
        val message = BiometricAuthenticationOutcome.TemporarilyBlocked(retryAfterMillis = 30_000L).toMessage()

        assertEquals(
            NivaraMessage(
                textRes = R.string.biometric_error_app_blocked,
                argument = secondsFromMillis(30_000L),
            ),
            message,
        )
        assertEquals(30L, message?.argument)
    }

    @Test
    fun `each of the platform's reasons maps to its own message`() {
        val reasons = BiometricUnavailability.entries.map { reason ->
            reason to BiometricAuthenticationOutcome.Unavailable(reason).toMessage()?.textRes
        }

        for ((reason, textRes) in reasons) {
            assertEquals(
                "the platform's reason must be the one reported: $reason",
                biometricUnavailabilityRes(reason),
                textRes,
            )
        }

        // Six distinct reasons must not collapse into one message.
        assertEquals(reasons.size, reasons.map { it.second }.toSet().size)
    }

    @Test
    fun `every status has a headline and a summary`() {
        val statuses = listOf(
            BiometricStatus.Enabled,
            BiometricStatus.Disabled,
            BiometricStatus.Invalidated,
            BiometricStatus.Unavailable(BiometricUnavailability.NotEnrolled),
        )

        for (status in statuses) {
            assertTrue(biometricStatusRes(status) != 0)
            assertTrue(biometricStatusSummaryRes(status) != 0)
        }
    }

    @Test
    fun `an unusable configuration is described as one, not as being switched off`() {
        // The difference matters: "off" is a choice the user made, "set up again" is a key Android
        // threw away, and the two need different words and different actions.
        assertNotEquals(biometricStatusRes(BiometricStatus.Disabled), biometricStatusRes(BiometricStatus.Invalidated))
    }

    @Test
    fun `every failure of enabling or disabling is reported`() {
        val failures = listOf(
            BiometricFailure.PrimaryCredentialRequired,
            BiometricFailure.Unavailable(BiometricUnavailability.NotEnrolled),
            BiometricFailure.Cancelled,
            BiometricFailure.SystemBlocked(permanent = false),
            BiometricFailure.SystemBlocked(permanent = true),
            BiometricFailure.NotEnabled,
            BiometricFailure.KeyStoreUnavailable,
            BiometricFailure.KeyGenerationFailed,
            BiometricFailure.StorageUnavailable,
        )

        for (failure in failures) {
            assertTrue("$failure should be reported to the user", failure.toMessage().textRes != 0)
        }

        assertEquals(
            R.string.biometric_error_primary_required,
            BiometricFailure.PrimaryCredentialRequired.toMessage().textRes,
        )
    }
}
