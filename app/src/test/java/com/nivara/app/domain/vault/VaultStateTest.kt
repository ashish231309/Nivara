package com.nivara.app.domain.vault

import com.nivara.app.domain.security.SecureRandomGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Local JVM tests for the vault's state model.
 *
 * The whole stage rests on one claim: the states that mean different things are actually different
 * values, so no code can accidentally treat an unreadable vault as an absent one. Two checks carry
 * that claim — every pair of states is unequal, and every state that means "something is wrong" is
 * unequal to both states that mean "there is nothing here" — and both are written so that a collapsed
 * case fails them.
 */
class VaultStateTest {

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

    @Test
    fun `every state is distinguishable from every other`() {
        val states = everyState()

        states.forEachIndexed { index, state ->
            states.drop(index + 1).forEach { other ->
                assertNotEquals(
                    "two different facts about a root must not be the same value: $state",
                    state,
                    other,
                )
            }
        }
    }

    @Test
    fun `no trouble state can be read as an empty vault`() {
        // The states that mean "something is wrong" are never equal to the states that mean "there is
        // nothing here", which is the collapse this stage exists to prevent.
        val trouble = listOf(
            VaultState.LocationUnknown,
            VaultState.Unreadable(VaultUnreadableReason.MetadataDamaged),
            VaultState.Unreadable(VaultUnreadableReason.StructureIncomplete),
            VaultState.Unreadable(VaultUnreadableReason.KeyUnavailable),
            VaultState.UnsupportedVersion(fileVersion = 2),
            VaultState.Unavailable,
            VaultState.AccessDenied,
        )

        trouble.forEach { state ->
            assertNotEquals(
                "an unreadable or unreachable vault is not a folder without a vault: $state",
                VaultState.Missing,
                state,
            )
            assertNotEquals(
                "and it is not an unconfigured vault either: $state",
                VaultState.NotConfigured,
                state,
            )
        }
    }

    @Test
    fun `the three reasons a vault cannot be opened are distinct values`() {
        val reasons = listOf(
            VaultUnreadableReason.MetadataDamaged,
            VaultUnreadableReason.StructureIncomplete,
            VaultUnreadableReason.KeyUnavailable,
        ).map { reason -> VaultState.Unreadable(reason) }

        assertEquals("each reason keeps its own state", reasons.size, reasons.toSet().size)
    }

    @Test
    fun `a ready vault carries its identity and format version`() {
        val identity = VaultIdentity.create(random)

        val state = VaultState.Ready(identity = identity, formatVersion = 1)

        assertEquals(identity, state.identity)
        assertEquals(1, state.formatVersion)
        assertEquals("an identical copy is the same vault", state, state.copy())
    }

    @Test
    fun `an unsupported version keeps the version it read`() {
        val state = VaultState.UnsupportedVersion(fileVersion = 7)

        assertEquals(7, state.fileVersion)
        assertNotEquals(
            "a newer format is not a damaged record",
            state,
            VaultState.Unreadable(VaultUnreadableReason.MetadataDamaged),
        )
    }

    @Test
    fun `unreachable storage and a refused grant are different answers`() {
        assertNotEquals(
            "the two have different remedies: reconnect the storage, or restore the grant",
            VaultState.Unavailable,
            VaultState.AccessDenied,
        )
    }
}
