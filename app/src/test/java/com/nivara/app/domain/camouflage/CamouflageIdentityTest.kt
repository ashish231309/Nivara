package com.nivara.app.domain.camouflage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for the rule that decides what the device is presenting.
 *
 * The rule is a pure function over the set of identities whose launcher entry is enabled, which is
 * why the security-relevant part of camouflage — *which name stands for the application?* — can be
 * tested exhaustively without a device. Everything that reads Android's component states is tested
 * by inspection of the implementation and by the manifest rules the verifier enforces; what is
 * decided here is what those readings mean.
 *
 * The property that matters most is the one the fail-closed cases protect: a device state Nivara
 * cannot vouch for never becomes a camouflage identity. The user chose nothing, so the answer is
 * Nivara's own identity — the one they can recognise and return from.
 */
class CamouflageIdentityTest {

    @Test
    fun `a single enabled entry is the identity the device presents`() {
        CamouflageProfile.entries.forEach { profile ->
            val identity = resolveCamouflageIdentity(setOf(profile))

            assertEquals(
                "one enabled entry is an answer, not a repair",
                CamouflageIdentity.Active(profile),
                identity,
            )
        }
    }

    @Test
    fun `nothing enabled is a repair, not an identity`() {
        assertEquals(CamouflageIdentity.NeedsRepair, resolveCamouflageIdentity(emptySet()))
    }

    @Test
    fun `several enabled entries are a repair, not a guess`() {
        val entries = CamouflageProfile.entries

        for (first in entries) {
            for (second in entries) {
                if (first == second) continue
                assertEquals(
                    "two enabled entries do not name one identity",
                    CamouflageIdentity.NeedsRepair,
                    resolveCamouflageIdentity(setOf(first, second)),
                )
            }
        }
    }

    @Test
    fun `every set that is not a single entry needs repair`() {
        val entries = CamouflageProfile.entries
        val subsets = (1 until (1 shl entries.size)).map { mask ->
            entries.filterIndexed { index, _ -> (mask shr index) and 1 == 1 }.toSet()
        }

        assertEquals("every subset is exercised", (1 shl entries.size) - 1, subsets.size)
        subsets.forEach { subset ->
            val identity = resolveCamouflageIdentity(subset)
            if (subset.size == 1) {
                assertEquals(CamouflageIdentity.Active(subset.single()), identity)
            } else {
                assertEquals(
                    "a set of ${subset.size} enabled entries names no identity",
                    CamouflageIdentity.NeedsRepair,
                    identity,
                )
            }
        }
    }

    @Test
    fun `a device state Nivara cannot vouch for never becomes a camouflage identity`() {
        // The failure this prevents: an identity change interrupted by a crash, or a tool that
        // reset the application's components, silently presenting the application under a name the
        // user did not choose.
        val unverifiable = listOf(
            emptySet(),
            setOf(CamouflageProfile.Notes, CamouflageProfile.Calculator),
            setOf(
                CamouflageProfile.Nivara,
                CamouflageProfile.Notes,
                CamouflageProfile.Calculator,
                CamouflageProfile.Weather,
            ),
        )

        unverifiable.forEach { enabled ->
            assertEquals(CamouflageIdentity.NeedsRepair, resolveCamouflageIdentity(enabled))
        }
    }

    @Test
    fun `the repair always means nivara's own identity`() {
        // The rule reports a repair; the repair is the default identity, and the default is Nivara's
        // own. Asserted together here because the guarantee is the pair, not either half.
        assertEquals(CamouflageProfile.Nivara, CamouflageProfile.Default)
        assertFalse(
            "a repair must never land on a name the user did not choose",
            CamouflageProfile.Default.isCamouflage,
        )
        assertEquals(
            CamouflageIdentity.NeedsRepair,
            resolveCamouflageIdentity(setOf(CamouflageProfile.Weather, CamouflageProfile.Notes)),
        )
    }

    @Test
    fun `the rule is deterministic`() {
        val enabled = setOf(CamouflageProfile.Calculator)

        assertEquals(
            resolveCamouflageIdentity(enabled),
            resolveCamouflageIdentity(enabled),
        )
    }

    @Test
    fun `the rule always answers`() {
        // Total by construction, and asserted so: whatever the platform reports, the screens get a
        // value rather than an exception to handle.
        val entries = CamouflageProfile.entries
        val subsets = (0 until (1 shl entries.size)).map { mask ->
            entries.filterIndexed { index, _ -> (mask shr index) and 1 == 1 }.toSet()
        }

        subsets.forEach { subset ->
            assertNotNull(resolveCamouflageIdentity(subset))
        }
    }

    @Test
    fun `an active identity is not a repair and a repair is not an identity`() {
        val active = resolveCamouflageIdentity(setOf(CamouflageProfile.Notes))

        assertTrue(active is CamouflageIdentity.Active)
        assertFalse(active is CamouflageIdentity.NeedsRepair)
    }
}
