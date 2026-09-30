package com.nivara.app.domain.security

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the rule that decides what Nivara reports about biometrics.
 *
 * [BiometricStatus.resolve] is a pure function of three facts, so the whole truth table can be
 * checked here, on the JVM, without a sensor: what is stored, what the platform says about the key,
 * and what the platform says about the hardware.
 *
 * The order of the rules is the interesting part. Hardware the platform will never support wins;
 * a stored configuration that cannot authenticate is reported as needing to be set up again rather
 * than as "off"; and a sensor that is merely busy does not turn a working configuration into a
 * broken one.
 */
class BiometricStatusTest {

    @Test
    fun `nothing stored on a capable device is reported as off`() {
        assertEquals(
            BiometricStatus.Disabled,
            BiometricStatus.resolve(configured = false, keyUsable = false, unavailability = null),
        )
    }

    @Test
    fun `nothing stored reports the platform's own reason for not being able to authenticate`() {
        for (reason in BiometricUnavailability.entries) {
            assertEquals(
                "expected the platform's reason to be reported: $reason",
                BiometricStatus.Unavailable(reason),
                BiometricStatus.resolve(configured = false, keyUsable = false, unavailability = reason),
            )
        }
    }

    @Test
    fun `a stored configuration with a usable key is enabled`() {
        assertEquals(
            BiometricStatus.Enabled,
            BiometricStatus.resolve(configured = true, keyUsable = true, unavailability = null),
        )
    }

    @Test
    fun `a stored configuration that cannot authenticate must be set up again`() {
        assertEquals(
            BiometricStatus.Invalidated,
            BiometricStatus.resolve(configured = true, keyUsable = false, unavailability = null),
        )
    }

    @Test
    fun `removing the device's enrolment invalidates a stored configuration`() {
        assertEquals(
            BiometricStatus.Invalidated,
            BiometricStatus.resolve(
                configured = true,
                keyUsable = true,
                unavailability = BiometricUnavailability.NotEnrolled,
            ),
        )
    }

    @Test
    fun `a temporarily busy sensor does not invalidate a stored configuration`() {
        // The user is still set up; the sensor is simply not answering at this moment, and the
        // prompt will report that itself.
        assertEquals(
            BiometricStatus.Enabled,
            BiometricStatus.resolve(
                configured = true,
                keyUsable = true,
                unavailability = BiometricUnavailability.HardwareUnavailable,
            ),
        )
    }

    @Test
    fun `hardware that can never work wins over a stored configuration`() {
        val permanent = listOf(
            BiometricUnavailability.NoHardware,
            BiometricUnavailability.Unsupported,
            BiometricUnavailability.SecurityUpdateRequired,
        )

        for (reason in permanent) {
            assertEquals(
                "expected $reason to be reported as unavailability, not as a configuration problem",
                BiometricStatus.Unavailable(reason),
                BiometricStatus.resolve(configured = true, keyUsable = true, unavailability = reason),
            )
            assertEquals(
                BiometricStatus.Unavailable(reason),
                BiometricStatus.resolve(configured = true, keyUsable = false, unavailability = reason),
            )
        }
    }

    @Test
    fun `an unknown platform state is reported as unknown, never as available`() {
        assertEquals(
            BiometricStatus.Unavailable(BiometricUnavailability.Unknown),
            BiometricStatus.resolve(
                configured = false,
                keyUsable = false,
                unavailability = BiometricUnavailability.Unknown,
            ),
        )
    }
}
