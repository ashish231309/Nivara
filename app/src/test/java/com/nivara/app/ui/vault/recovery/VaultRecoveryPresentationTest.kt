package com.nivara.app.ui.vault.recovery

import com.nivara.app.R
import com.nivara.app.domain.vault.VaultRecoveryFailure
import com.nivara.app.domain.vault.displayFingerprint
import com.nivara.app.ui.vault.asRecoverySetupMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Local JVM tests for recovery's wording and mapping.
 *
 * Copy is behaviour here as much as anywhere else: every refusal has its own sentence, a lockout is
 * drawn with the seconds it lasts, and nothing recovery says can be read as "the vault is empty"
 * or "the vault is gone". Recovery succeeded is not "you are authenticated" either, and the setup
 * wording says the vault itself is unchanged.
 */
class VaultRecoveryPresentationTest {

    private fun everyFailure(): List<VaultRecoveryFailure> = listOf(
        VaultRecoveryFailure.NotAVault,
        VaultRecoveryFailure.RecoveryNotSetUp,
        VaultRecoveryFailure.VaultDamaged,
        VaultRecoveryFailure.VaultUnsupported,
        VaultRecoveryFailure.LocationUnavailable,
        VaultRecoveryFailure.CodeMalformed,
        VaultRecoveryFailure.CodeChecksumMismatch,
        VaultRecoveryFailure.WrongMaterial,
        VaultRecoveryFailure.KeyMismatch,
        VaultRecoveryFailure.WriteFailed,
        VaultRecoveryFailure.KeyUnavailable,
        VaultRecoveryFailure.CryptographyFailed,
        VaultRecoveryFailure.VaultNotReady,
    )

    @Test
    fun `every refusal maps to its own words`() {
        val messages = everyFailure().map { failure -> failure.asRecoveryMessage() }

        assertEquals(R.string.vault_recovery_not_a_vault, messages[0].textRes)
        assertEquals(R.string.vault_recovery_not_set_up, messages[1].textRes)
        assertEquals(R.string.vault_recovery_damaged, messages[2].textRes)
        assertEquals(R.string.vault_recovery_unsupported, messages[3].textRes)
        assertEquals(R.string.vault_recovery_location_unavailable, messages[4].textRes)
        assertEquals(R.string.vault_recovery_code_malformed, messages[5].textRes)
        assertEquals(R.string.vault_recovery_code_checksum, messages[6].textRes)
        assertEquals(R.string.vault_recovery_wrong_material, messages[7].textRes)
        assertEquals(R.string.vault_recovery_key_mismatch, messages[8].textRes)
        assertEquals(R.string.vault_recovery_write_failed, messages[9].textRes)

        // The typed refusals each carry their own sentence; the three machinery failures that
        // follow deliberately share the generic one, so distinctness is pinned over the ten.
        val resources = messages.take(10).map { message -> message.textRes }
        assertEquals(
            "no two typed refusals share one sentence",
            resources.size,
            resources.distinct().size,
        )
        assertEquals(R.string.vault_recovery_failed, messages[10].textRes)
        assertEquals(R.string.vault_recovery_failed, messages[11].textRes)
        assertEquals(R.string.vault_recovery_failed, messages[12].textRes)
    }

    @Test
    fun `a lockout is drawn with its remaining seconds, rounded up`() {
        val shortLock = VaultRecoveryFailure.Locked(remainingMillis = 1L).asRecoveryMessage()
        assertEquals(R.string.vault_recovery_locked, shortLock.textRes)
        assertEquals("never below one second", 1L, shortLock.argument)

        val oddLock = VaultRecoveryFailure.Locked(remainingMillis = 4_200L).asRecoveryMessage()
        assertEquals(5L, oddLock.argument)

        val exactLock = VaultRecoveryFailure.Locked(remainingMillis = 3_000L).asRecoveryMessage()
        assertEquals(3L, exactLock.argument)
    }

    @Test
    fun `an unknown failure is reported, never swallowed`() {
        val message = IllegalStateException("platform detail").asRecoveryMessage()

        assertEquals(R.string.vault_recovery_failed, message.textRes)
        assertNull("no platform text reaches the screen", message.argument)
    }

    @Test
    fun `a missing failure is the generic refusal, not silence`() {
        assertEquals(R.string.vault_recovery_failed, (null as Throwable?).asRecoveryMessage().textRes)
    }

    @Test
    fun `the setup's refusals keep their two facts apart`() {
        assertEquals(
            R.string.vault_error_recovery_not_ready,
            VaultRecoveryFailure.VaultNotReady.asRecoverySetupMessage().textRes,
        )
        assertEquals(
            R.string.vault_error_recovery_failed,
            VaultRecoveryFailure.WriteFailed.asRecoverySetupMessage().textRes,
        )
        assertNotEquals(
            VaultRecoveryFailure.VaultNotReady.asRecoverySetupMessage().textRes,
            VaultRecoveryFailure.WriteFailed.asRecoverySetupMessage().textRes,
        )
    }

    @Test
    fun `the phases a recovery moves through are distinct states`() {
        val phases = listOf(
            VaultRecoveryPhase.SelectLocation,
            VaultRecoveryPhase.NotAVault,
            VaultRecoveryPhase.RecoveryNotSetUp,
            VaultRecoveryPhase.VaultDamaged,
            VaultRecoveryPhase.VaultUnsupported,
            VaultRecoveryPhase.LocationUnavailable,
            VaultRecoveryPhase.RecoveryRequired(identityFingerprint = "abcd"),
            VaultRecoveryPhase.Reconnected(identityFingerprint = "abcd"),
        )

        assertEquals(
            "no phase collapses into another",
            phases.size,
            phases.distinct().size,
        )
    }

    @Test
    fun `the fingerprint is grouped hex, never raw bytes`() {
        val fingerprint =
            com.nivara.app.domain.vault.VaultIdentity("00112233445566778899aabbccddeeff")
                .displayFingerprint()

        assertEquals("0011 2233 4455 6677 8899 aabb ccddeeff", fingerprint)
    }
}
