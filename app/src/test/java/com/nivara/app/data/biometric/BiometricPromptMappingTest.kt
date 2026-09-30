package com.nivara.app.data.biometric

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import com.nivara.app.domain.security.BiometricAuthenticationOutcome
import com.nivara.app.domain.security.BiometricFailure
import com.nivara.app.domain.security.BiometricUnavailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the two translations between Android's answers and Nivara's.
 *
 * These are pure functions of an `Int`, so they can be checked on the JVM against the platform's
 * own constants — the real ones from the AndroidX library that runs on a device, not copies of
 * them. What the tests protect is the distinction the stage turns on: a cancelled prompt, a
 * platform lockout and a sensor that cannot answer are three different facts, and none of them is
 * a failed match.
 *
 * Nothing here performs an authentication, and nothing here claims to: these tests verify how an
 * answer that came from Android would be interpreted. Only a device can produce the answer.
 */
class BiometricPromptMappingTest {

    @Test
    fun `a capable device reports no reason to be unavailable`() {
        assertNull(biometricAvailability(BiometricManager.BIOMETRIC_SUCCESS))
    }

    @Test
    fun `the platform's capability codes are translated one for one`() {
        assertEquals(
            BiometricUnavailability.NoHardware,
            biometricAvailability(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE),
        )
        assertEquals(
            BiometricUnavailability.HardwareUnavailable,
            biometricAvailability(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE),
        )
        assertEquals(
            BiometricUnavailability.NotEnrolled,
            biometricAvailability(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED),
        )
        assertEquals(
            BiometricUnavailability.SecurityUpdateRequired,
            biometricAvailability(BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED),
        )
        assertEquals(
            BiometricUnavailability.Unsupported,
            biometricAvailability(BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED),
        )
    }

    @Test
    fun `an unrecognised capability code is reported as unknown`() {
        assertEquals(BiometricUnavailability.Unknown, biometricAvailability(-42))
        assertEquals(BiometricUnavailability.Unknown, biometricAvailability(Int.MIN_VALUE))
    }

    @Test
    fun `dismissing the prompt is reported as a cancellation`() {
        val cancelling = listOf(
            BiometricPrompt.ERROR_USER_CANCELED,
            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
            BiometricPrompt.ERROR_CANCELED,
            BiometricPrompt.ERROR_TIMEOUT,
        )

        for (code in cancelling) {
            assertEquals(
                "error $code should be a cancellation",
                BiometricAuthenticationOutcome.Cancelled,
                promptErrorOutcome(code),
            )
        }
    }

    @Test
    fun `Android's lockout is reported as Android's lockout`() {
        assertEquals(
            BiometricAuthenticationOutcome.SystemBlocked(permanent = false),
            promptErrorOutcome(BiometricPrompt.ERROR_LOCKOUT),
        )
        assertEquals(
            BiometricAuthenticationOutcome.SystemBlocked(permanent = true),
            promptErrorOutcome(BiometricPrompt.ERROR_LOCKOUT_PERMANENT),
        )
    }

    @Test
    fun `a sensor or configuration problem is reported as unavailability`() {
        assertEquals(
            BiometricAuthenticationOutcome.Unavailable(BiometricUnavailability.HardwareUnavailable),
            promptErrorOutcome(BiometricPrompt.ERROR_HW_UNAVAILABLE),
        )
        assertEquals(
            BiometricAuthenticationOutcome.Unavailable(BiometricUnavailability.HardwareUnavailable),
            promptErrorOutcome(BiometricPrompt.ERROR_UNABLE_TO_PROCESS),
        )
        assertEquals(
            BiometricAuthenticationOutcome.Unavailable(BiometricUnavailability.NotEnrolled),
            promptErrorOutcome(BiometricPrompt.ERROR_NO_BIOMETRICS),
        )
        assertEquals(
            BiometricAuthenticationOutcome.Unavailable(BiometricUnavailability.NoHardware),
            promptErrorOutcome(BiometricPrompt.ERROR_HW_NOT_PRESENT),
        )
        assertEquals(
            BiometricAuthenticationOutcome.Unavailable(BiometricUnavailability.SecurityUpdateRequired),
            promptErrorOutcome(BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED),
        )
    }

    @Test
    fun `an error Nivara does not recognise is reported as unavailable, not as success`() {
        val unrecognised = listOf(
            BiometricPrompt.ERROR_NO_SPACE,
            BiometricPrompt.ERROR_VENDOR,
            BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
            -42,
        )

        for (code in unrecognised) {
            assertEquals(
                BiometricAuthenticationOutcome.Unavailable(BiometricUnavailability.Unknown),
                promptErrorOutcome(code),
            )
        }
    }

    @Test
    fun `no error code is ever reported as a success or a failed match`() {
        // The mapping must not invent a policy decision: an error means the prompt ended, so it can
        // never mean "the user was accepted", and it is the authenticator that decides whether a
        // mismatch happened, from the count of rejected biometrics Android reported.
        val allCodes = listOf(
            BiometricPrompt.ERROR_CANCELED,
            BiometricPrompt.ERROR_HW_NOT_PRESENT,
            BiometricPrompt.ERROR_HW_UNAVAILABLE,
            BiometricPrompt.ERROR_LOCKOUT,
            BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
            BiometricPrompt.ERROR_NO_BIOMETRICS,
            BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
            BiometricPrompt.ERROR_NO_SPACE,
            BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED,
            BiometricPrompt.ERROR_TIMEOUT,
            BiometricPrompt.ERROR_UNABLE_TO_PROCESS,
            BiometricPrompt.ERROR_USER_CANCELED,
            BiometricPrompt.ERROR_VENDOR,
        )

        for (code in allCodes) {
            val outcome = promptErrorOutcome(code)
            assertFalse("error $code must not mean success", outcome is BiometricAuthenticationOutcome.Succeeded)
            assertFalse("error $code must not mean a rejected match", outcome is BiometricAuthenticationOutcome.Failed)
            assertTrue(
                "error $code must be a cancellation, a lockout or an unavailability",
                outcome is BiometricAuthenticationOutcome.Cancelled ||
                    outcome is BiometricAuthenticationOutcome.SystemBlocked ||
                    outcome is BiometricAuthenticationOutcome.Unavailable,
            )
        }
    }

    @Test
    fun `the failure mapping keeps the same distinctions`() {
        assertEquals(BiometricFailure.Cancelled, promptErrorFailure(BiometricPrompt.ERROR_USER_CANCELED))
        assertEquals(
            BiometricFailure.SystemBlocked(permanent = false),
            promptErrorFailure(BiometricPrompt.ERROR_LOCKOUT),
        )
        assertEquals(
            BiometricFailure.SystemBlocked(permanent = true),
            promptErrorFailure(BiometricPrompt.ERROR_LOCKOUT_PERMANENT),
        )
        assertEquals(
            BiometricFailure.Unavailable(BiometricUnavailability.NotEnrolled),
            promptErrorFailure(BiometricPrompt.ERROR_NO_BIOMETRICS),
        )
    }
}
