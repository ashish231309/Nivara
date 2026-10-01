package com.nivara.app.domain.permissions

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.domain.app.ApplicationDiscoveryState
import com.nivara.app.domain.app.InstalledApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for the setup aggregate.
 *
 * The aggregate is what a screen reads to decide what is missing, so the tests are about honest
 * answers: a failed query is not an empty device, an unreadable capability is not a refusal, and
 * the readiness claim is derived from the same fields it summarises — including the overlay
 * permission, which shows protection rather than detecting it.
 */
class AppLockSetupStateTest {

    private val applications = listOf(
        InstalledApplication("com.example.camera", "Camera"),
        InstalledApplication("com.example.notes", "Notes"),
    )

    @Test
    fun `a successful discovery is available with its applications`() {
        val state = AppLockSetupState.of(
            discovery = NivaraResult.Success(applications),
            usageAccess = UsageAccessStatus.Granted,
            overlay = OverlayCapability.Granted,
        )

        assertEquals(ApplicationDiscoveryState.Available(applications), state.discovery)
        assertEquals(applications, state.applications)
    }

    @Test
    fun `a failed discovery is unavailable, not an empty device`() {
        val state = AppLockSetupState.of(
            discovery = NivaraResult.Failure(),
            usageAccess = UsageAccessStatus.Granted,
            overlay = OverlayCapability.Granted,
        )

        assertEquals(ApplicationDiscoveryState.Unavailable, state.discovery)
        assertNotEquals(ApplicationDiscoveryState.Available(emptyList()), state.discovery)
        assertTrue(state.applications.isEmpty())
    }

    @Test
    fun `an empty but successful discovery is a different answer from a failure`() {
        val state = AppLockSetupState.of(
            discovery = NivaraResult.Success(emptyList()),
            usageAccess = UsageAccessStatus.Granted,
            overlay = OverlayCapability.Granted,
        )

        assertEquals(ApplicationDiscoveryState.Available(emptyList()), state.discovery)
        assertNotEquals(ApplicationDiscoveryState.Unavailable, state.discovery)
    }

    @Test
    fun `everything in place reads as ready`() {
        val state = AppLockSetupState.of(
            discovery = NivaraResult.Success(applications),
            usageAccess = UsageAccessStatus.Granted,
            overlay = OverlayCapability.Granted,
        )

        assertTrue(state.isReady)
        assertTrue(state.missingPrerequisites.isEmpty())
    }

    @Test
    fun `a missing grant is the only thing left when discovery succeeded`() {
        val state = AppLockSetupState.of(
            discovery = NivaraResult.Success(applications),
            usageAccess = UsageAccessStatus.NotGranted,
            overlay = OverlayCapability.Granted,
        )

        assertFalse(state.isReady)
        assertEquals(listOf(AppLockPrerequisite.UsageAccess), state.missingPrerequisites)
    }

    @Test
    fun `an unreadable grant is missing, never treated as granted`() {
        val state = AppLockSetupState.of(
            discovery = NivaraResult.Success(applications),
            usageAccess = UsageAccessStatus.Unavailable,
            overlay = OverlayCapability.Granted,
        )

        assertFalse(state.isReady)
        assertEquals(listOf(AppLockPrerequisite.UsageAccess), state.missingPrerequisites)
    }

    @Test
    fun `a failed discovery with no grant reports both, in a stable order`() {
        val state = AppLockSetupState.of(
            discovery = NivaraResult.Failure(),
            usageAccess = UsageAccessStatus.NotGranted,
            overlay = OverlayCapability.Granted,
        )

        assertEquals(
            listOf(AppLockPrerequisite.ApplicationDiscovery, AppLockPrerequisite.UsageAccess),
            state.missingPrerequisites,
        )
    }

    @Test
    fun `discovery alone is not readiness`() {
        val state = AppLockSetupState.of(
            discovery = NivaraResult.Failure(),
            usageAccess = UsageAccessStatus.Granted,
            overlay = OverlayCapability.Granted,
        )

        assertEquals(
            listOf(AppLockPrerequisite.ApplicationDiscovery),
            state.missingPrerequisites,
        )
    }

    @Test
    fun `a missing overlay grant is a missing prerequisite of its own`() {
        val state = AppLockSetupState.of(
            discovery = NivaraResult.Success(applications),
            usageAccess = UsageAccessStatus.Granted,
            overlay = OverlayCapability.NotGranted,
        )

        assertFalse("detection without a way to present it is not readiness", state.isReady)
        assertEquals(listOf(AppLockPrerequisite.Overlay), state.missingPrerequisites)
    }

    @Test
    fun `an unreadable overlay capability is missing, never treated as granted`() {
        val state = AppLockSetupState.of(
            discovery = NivaraResult.Success(applications),
            usageAccess = UsageAccessStatus.Granted,
            overlay = OverlayCapability.Unavailable,
        )

        assertFalse(state.isReady)
        assertEquals(listOf(AppLockPrerequisite.Overlay), state.missingPrerequisites)
    }

    @Test
    fun `every missing prerequisite is reported together, in a stable order`() {
        val state = AppLockSetupState.of(
            discovery = NivaraResult.Failure(),
            usageAccess = UsageAccessStatus.Unavailable,
            overlay = OverlayCapability.NotGranted,
        )

        assertEquals(
            listOf(
                AppLockPrerequisite.ApplicationDiscovery,
                AppLockPrerequisite.UsageAccess,
                AppLockPrerequisite.Overlay,
            ),
            state.missingPrerequisites,
        )
    }

    @Test
    fun `the same answers build the same aggregate`() {
        val first = AppLockSetupState.of(
            NivaraResult.Success(applications),
            UsageAccessStatus.Granted,
            OverlayCapability.Granted,
        )
        val second = AppLockSetupState.of(
            NivaraResult.Success(applications),
            UsageAccessStatus.Granted,
            OverlayCapability.Granted,
        )

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }
}
