package com.nivara.app.ui.vault

import com.nivara.app.R
import com.nivara.app.domain.security.SecureRandomGenerator
import com.nivara.app.domain.vault.VaultFailure
import com.nivara.app.domain.vault.VaultIdentity
import com.nivara.app.domain.vault.VaultState
import com.nivara.app.domain.vault.VaultUnreadableReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for the vault's user-facing wording.
 *
 * Copy is behaviour here: the difference between "there is no vault" and "there is a vault I cannot
 * open" is the whole point of the stage, and it is carried by the sentences the screen shows. These
 * tests check that every state has its own heading and explanation, that the states which must not be
 * confused do not share wording, and that every typed failure has a message rather than a default.
 */
class VaultPresentationTest {

    private val random = SecureRandomGenerator()

    private fun everyState(): List<VaultState> = listOf(
        VaultState.NotConfigured,
        VaultState.LocationUnknown,
        VaultState.Missing,
        VaultState.Ready(VaultIdentity.create(random), formatVersion = 1),
        VaultState.Unreadable(VaultUnreadableReason.MetadataDamaged),
        VaultState.Unreadable(VaultUnreadableReason.StructureIncomplete),
        VaultState.Unreadable(VaultUnreadableReason.KeyUnavailable),
        VaultState.UnsupportedVersion(fileVersion = 2),
        VaultState.Unavailable,
        VaultState.AccessDenied,
    )

    private fun everyFailure(): List<VaultFailure> = listOf(
        VaultFailure.InvalidLocation,
        VaultFailure.LocationUnreadable,
        VaultFailure.AccessDenied,
        VaultFailure.StorageUnavailable,
        VaultFailure.VaultAlreadyExists,
        VaultFailure.VaultUnreadable(VaultUnreadableReason.MetadataDamaged),
        VaultFailure.VaultUnreadable(VaultUnreadableReason.StructureIncomplete),
        VaultFailure.VaultUnreadable(VaultUnreadableReason.KeyUnavailable),
        VaultFailure.UnsupportedVersion(fileVersion = 2),
        VaultFailure.WriteFailed,
        VaultFailure.VerificationFailed,
        VaultFailure.KeyUnavailable,
        VaultFailure.CryptographyFailed,
    )

    @Test
    fun `every state has a heading and an explanation`() {
        everyState().forEach { state ->
            assertTrue("a state without a heading: $state", state.titleRes() != 0)
            assertTrue("a state without an explanation: $state", state.bodyRes() != 0)
        }
    }

    @Test
    fun `the states that must not be confused do not share wording`() {
        val pairs = everyState().map { state -> state.titleRes() to state.bodyRes() }

        assertEquals(
            "two states must not be drawn with the same heading and explanation",
            pairs.size,
            pairs.toSet().size,
        )
    }

    @Test
    fun `an unreadable record and an unfinished setup are explained differently`() {
        val damaged = VaultState.Unreadable(VaultUnreadableReason.MetadataDamaged)
        val incomplete = VaultState.Unreadable(VaultUnreadableReason.StructureIncomplete)

        assertNotEquals(
            "one is a vault that cannot be opened, the other is a setup that did not finish",
            damaged.titleRes(),
            incomplete.titleRes(),
        )
        assertNotEquals(damaged.bodyRes(), incomplete.bodyRes())
    }

    @Test
    fun `a lost platform key is not described as damage`() {
        val lost = VaultState.Unreadable(VaultUnreadableReason.KeyUnavailable)
        val damaged = VaultState.Unreadable(VaultUnreadableReason.MetadataDamaged)

        assertNotEquals(lost.bodyRes(), damaged.bodyRes())
    }

    @Test
    fun `the states that mean trouble do not read as no vault at all`() {
        val missing = VaultState.Missing

        everyState()
            .filter { state -> state != missing && state != VaultState.NotConfigured }
            .forEach { state ->
                assertNotEquals(
                    "this state must not be drawn like a folder without a vault: $state",
                    missing.bodyRes(),
                    state.bodyRes(),
                )
            }
    }

    @Test
    fun `every typed failure has its own message`() {
        val expected = mapOf(
            VaultFailure.InvalidLocation to R.string.vault_error_invalid_location,
            VaultFailure.LocationUnreadable to R.string.vault_error_location_unreadable,
            VaultFailure.AccessDenied to R.string.vault_error_access_denied,
            VaultFailure.StorageUnavailable to R.string.vault_error_storage_unavailable,
            VaultFailure.VaultAlreadyExists to R.string.vault_error_already_exists,
            VaultFailure.VaultUnreadable(VaultUnreadableReason.MetadataDamaged) to R.string.vault_error_not_replaced,
            VaultFailure.VaultUnreadable(VaultUnreadableReason.StructureIncomplete) to R.string.vault_error_not_replaced,
            VaultFailure.VaultUnreadable(VaultUnreadableReason.KeyUnavailable) to R.string.vault_error_not_replaced,
            VaultFailure.UnsupportedVersion(fileVersion = 2) to R.string.vault_error_not_replaced,
            VaultFailure.WriteFailed to R.string.vault_error_write_failed,
            VaultFailure.VerificationFailed to R.string.vault_error_not_verified,
            VaultFailure.KeyUnavailable to R.string.vault_error_key_unavailable,
            VaultFailure.CryptographyFailed to R.string.vault_error_cryptography,
        )

        assertEquals("every failure needs a case", everyFailure().size, expected.size)
        everyFailure().forEach { failure ->
            assertEquals(
                "the message for $failure",
                expected.getValue(failure),
                failure.asMessage().textRes,
            )
        }
    }

    @Test
    fun `a refusal to replace is never reported as nothing to do`() {
        val notReplaced = setOf(
            VaultFailure.VaultUnreadable(VaultUnreadableReason.MetadataDamaged),
            VaultFailure.VaultUnreadable(VaultUnreadableReason.KeyUnavailable),
            VaultFailure.UnsupportedVersion(fileVersion = 2),
        )

        notReplaced.forEach { failure ->
            assertNotEquals(
                "a vault Nivara refused to touch must not be reported like a normal outcome",
                R.string.vault_notice_initialized,
                failure.asMessage().textRes,
            )
            assertNotEquals(R.string.vault_error_selection_failed, failure.asMessage().textRes)
        }
    }

    @Test
    fun `the locked message and the selection message are the screen's own`() {
        assertEquals(R.string.vault_locked, vaultLockedMessage().textRes)
        assertEquals(R.string.vault_error_selection_failed, vaultSelectionFailedMessage().textRes)
        assertNotEquals(
            "asking to unlock is not the same as a refused selection",
            vaultLockedMessage().textRes,
            vaultSelectionFailedMessage().textRes,
        )
    }
}
