package com.nivara.app.data.biometric

import com.nivara.app.domain.security.BiometricUnavailability
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the rule that decides whether removing Nivara's biometric configuration needs another
 * authentication first.
 *
 * The rule is a compromise between two failures, and the tests state both sides of it. Removing a
 * configuration that can still authenticate takes a capability away, so it must be authenticated —
 * anything else would make "turn biometric unlock off" a way to change a security control without
 * proving anything. Removing one that cannot authenticate takes nothing away: the key is already
 * dead, so requiring a biometric to dispose of it would only strand the user with a setting they
 * cannot clear.
 *
 * The primary credential is verified before the removal in either case; this rule only decides
 * whether Android also has to ask.
 */
class BiometricRemovalRuleTest {

    @Test
    fun `an unusable key needs no prompt, whatever the hardware says`() {
        val availabilities = listOf(
            null,
            BiometricUnavailability.HardwareUnavailable,
            BiometricUnavailability.NoHardware,
            BiometricUnavailability.NotEnrolled,
            BiometricUnavailability.Unsupported,
            BiometricUnavailability.SecurityUpdateRequired,
            BiometricUnavailability.Unknown,
        )

        for (availability in availabilities) {
            assertFalse(
                "an already-invalid key should not require a prompt ($availability)",
                requiresPromptBeforeRemoval(keyUsable = false, availability = availability),
            )
        }
    }

    @Test
    fun `a usable key on a working device needs a prompt`() {
        assertTrue(requiresPromptBeforeRemoval(keyUsable = true, availability = null))
    }

    @Test
    fun `a usable key needs a prompt while the sensor is merely busy or the state is unknown`() {
        assertTrue(
            requiresPromptBeforeRemoval(
                keyUsable = true,
                availability = BiometricUnavailability.HardwareUnavailable,
            ),
        )
        assertTrue(
            requiresPromptBeforeRemoval(
                keyUsable = true,
                availability = BiometricUnavailability.Unknown,
            ),
        )
    }

    @Test
    fun `a usable key on hardware that cannot authenticate needs no prompt`() {
        val unusableHardware = listOf(
            BiometricUnavailability.NoHardware,
            BiometricUnavailability.NotEnrolled,
            BiometricUnavailability.Unsupported,
            BiometricUnavailability.SecurityUpdateRequired,
        )

        for (availability in unusableHardware) {
            assertFalse(
                "no prompt can succeed when the platform reports $availability",
                requiresPromptBeforeRemoval(keyUsable = true, availability = availability),
            )
        }
    }
}
