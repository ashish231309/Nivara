package com.nivara.app.ui.vault

import com.nivara.app.R
import com.nivara.app.domain.vault.VaultIndexState
import com.nivara.app.domain.vault.VaultItem
import com.nivara.app.domain.vault.VaultItemId
import com.nivara.app.domain.vault.VaultOrganizationFailure
import com.nivara.app.domain.vault.VaultOrganizationState
import com.nivara.app.domain.vault.VaultOrganizationUnreadable
import com.nivara.app.domain.vault.VaultState
import com.nivara.app.testing.testAlbum
import com.nivara.app.testing.testAlbumId
import com.nivara.app.testing.testItem
import com.nivara.app.testing.testItemId
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for how the organisation surface is worded and mapped.
 *
 * Copy is behaviour in this stage as much as anywhere else: an album record that cannot be read must
 * never be drawn as a vault with no albums, because that is the sentence that invites somebody to
 * start organising again over albums that are still there. These tests hold that line — every state
 * has its own words, the states that must not be confused do not share them, every failure has a name,
 * and an album that is drawn carries no copy of the facts the index owns.
 */
class VaultOrganizationPresentationTest {

    private fun everyState(): List<VaultOrganizationState> = listOf(
        VaultOrganizationState.Missing,
        VaultOrganizationState.Ready(listOf(testAlbum(seed = 1, name = "Trip"))),
        VaultOrganizationState.Ready(emptyList()),
        VaultOrganizationState.Unreadable(VaultOrganizationUnreadable.MetadataDamaged),
        VaultOrganizationState.Unreadable(VaultOrganizationUnreadable.KeyUnavailable),
        VaultOrganizationState.UnsupportedVersion(fileVersion = 2),
        VaultOrganizationState.Unavailable,
        VaultOrganizationState.AccessDenied,
        VaultOrganizationState.VaultNotReady(VaultState.Missing),
    )

    private fun everyFailure(): List<VaultOrganizationFailure> = listOf(
        VaultOrganizationFailure.NotAuthorized,
        VaultOrganizationFailure.VaultNotReady(VaultState.Missing),
        VaultOrganizationFailure.OrganizationUnreadable(VaultOrganizationUnreadable.MetadataDamaged),
        VaultOrganizationFailure.OrganizationUnreadable(VaultOrganizationUnreadable.KeyUnavailable),
        VaultOrganizationFailure.UnsupportedVersion(fileVersion = 2),
        VaultOrganizationFailure.MetadataUnavailable,
        VaultOrganizationFailure.AccessDenied,
        VaultOrganizationFailure.AlbumNotFound,
        VaultOrganizationFailure.InvalidAlbumName,
        VaultOrganizationFailure.AlbumNameUnchanged,
        VaultOrganizationFailure.AlbumFull,
        VaultOrganizationFailure.OrganizationFull,
        VaultOrganizationFailure.StorageUnavailable,
        VaultOrganizationFailure.WriteFailed,
        VaultOrganizationFailure.VerificationFailed,
        VaultOrganizationFailure.CryptographyFailed,
    )

    // ------------------------------------------------------------------ the states

    @Test
    fun `a record that cannot be read has its own words and never the empty album list's`() {
        val unreadable = listOf(
            VaultOrganizationUiState.Unreadable(VaultOrganizationUnreadable.MetadataDamaged),
            VaultOrganizationUiState.Unreadable(VaultOrganizationUnreadable.KeyUnavailable),
            VaultOrganizationUiState.UnsupportedVersion,
            VaultOrganizationUiState.Unavailable,
            VaultOrganizationUiState.AccessDenied,
        )

        unreadable.forEach { state ->
            val title = state.titleRes()
            val body = state.bodyRes()

            assertTrue("a state that needs a heading must have one: $state", title != null)
            assertTrue("and a sentence to go with it: $state", body != null)
            assertNotEquals("and never the sentence for a vault nobody has organised", R.string.vault_albums_none, body)
        }
        assertEquals("two unreadable states must not share a heading and a sentence", unreadable.size, unreadable.map { it.titleRes() to it.bodyRes() }.toSet().size)
    }

    @Test
    fun `the two reasons a record cannot be opened are explained differently`() {
        val damaged = VaultOrganizationUiState.Unreadable(VaultOrganizationUnreadable.MetadataDamaged)
        val withoutKey = VaultOrganizationUiState.Unreadable(VaultOrganizationUnreadable.KeyUnavailable)

        assertNotEquals(damaged.bodyRes(), withoutKey.bodyRes())
    }

    @Test
    fun `an ordinary album list has no card of its own`() {
        listOf(
            VaultOrganizationUiState.Loading,
            VaultOrganizationUiState.Empty,
            VaultOrganizationUiState.Albums(emptyList()),
            VaultOrganizationUiState.VaultNotReady,
        ).forEach { state ->
            assertNull("the albums themselves are the body: $state", state.titleRes())
            assertNull(state.bodyRes())
        }
    }

    @Test
    fun `only a record that was read, or the certain knowledge that none exists, may be changed`() {
        assertTrue(VaultOrganizationUiState.Empty.acceptsChanges)
        assertTrue(VaultOrganizationUiState.Albums(emptyList()).acceptsChanges)

        listOf(
            VaultOrganizationUiState.Loading,
            VaultOrganizationUiState.VaultNotReady,
            VaultOrganizationUiState.Unreadable(VaultOrganizationUnreadable.MetadataDamaged),
            VaultOrganizationUiState.Unreadable(VaultOrganizationUnreadable.KeyUnavailable),
            VaultOrganizationUiState.UnsupportedVersion,
            VaultOrganizationUiState.Unavailable,
            VaultOrganizationUiState.AccessDenied,
        ).forEach { state ->
            assertFalse("$state must not be written over", state.acceptsChanges)
        }
    }

    @Test
    fun `no album record state is drawn as an empty album list`() {
        everyState().forEach { state ->
            val drawn = state.toUiState { album -> album.resolveAgainst(VaultIndexState.Missing) }

            if (state is VaultOrganizationState.Missing) {
                assertEquals(VaultOrganizationUiState.Empty, drawn)
            } else {
                assertNotEquals(
                    "only the certain knowledge that no record exists may look like this: $state",
                    VaultOrganizationUiState.Empty,
                    drawn,
                )
            }
        }
    }

    @Test
    fun `a readable record is drawn with its albums, in a deterministic order`() {
        val state = VaultOrganizationState.Ready(
            listOf(testAlbum(seed = 3, name = "zebra"), testAlbum(seed = 1, name = "Apples"), testAlbum(seed = 2, name = "apples")),
        )

        val drawn = state.toUiState { album -> album.resolveAgainst(VaultIndexState.Missing) } as VaultOrganizationUiState.Albums

        assertEquals(listOf("Apples", "apples", "zebra"), drawn.albums.map { album -> album.name })
        assertEquals(
            "a tie on the title is decided by the identifier",
            listOf(testAlbumId(1), testAlbumId(2), testAlbumId(3)),
            drawn.albums.map { album -> album.id },
        )
    }

    @Test
    fun `a readable record with no albums is a readable record`() {
        val drawn = VaultOrganizationState.Ready(emptyList())
            .toUiState { album -> album.resolveAgainst(VaultIndexState.Missing) }

        assertEquals(VaultOrganizationUiState.Albums(emptyList()), drawn)
        assertTrue("and it may be added to", drawn.acceptsChanges)
    }

    // ------------------------------------------------------------------ what an album row carries

    @Test
    fun `an album row carries no copy of any file's facts`() {
        val fields = VaultAlbumUi::class.java.declaredFields
            .filter { field -> !Modifier.isStatic(field.modifiers) }

        assertEquals(
            "an album row is its identity, its title, when it was made, and its counts",
            setOf("id", "name", "createdAtEpochMillis", "memberCount", "staleCount", "resolved"),
            fields.map { field -> field.name }.toSet(),
        )
        fields.forEach { field ->
            assertNotEquals("no item is copied into an album row", VaultItem::class.java, field.type)
            assertNotEquals("and no item identifier either", VaultItemId::class.java, field.type)
        }
    }

    @Test
    fun `an open album carries its membership from the record, not from the list on screen`() {
        val fields = VaultAlbumDetailUi::class.java.declaredFields
            .filter { field -> !Modifier.isStatic(field.modifiers) }

        assertTrue(
            "membership is the album record's own answer",
            fields.any { field -> field.name == "memberItemIds" && Set::class.java.isAssignableFrom(field.type) },
        )
        assertTrue(
            "and the items that resolve are the index's",
            fields.any { field -> field.name == "contents" },
        )
    }

    @Test
    fun `an album with no items still has counts of its own`() {
        val album = testAlbum(seed = 1, name = "Trip")
        val drawn = album.toAlbumUi(album.resolveAgainst(VaultIndexState.Ready(items = emptyList())))

        assertEquals(0, drawn.memberCount)
        assertEquals(0, drawn.staleCount)
        assertTrue(drawn.resolved)
    }

    @Test
    fun `an album with references the index does not name says how many are missing`() {
        val album = testAlbum(seed = 1, name = "Trip", itemIds = listOf(testItemId(1), testItemId(404)))
        val index = VaultIndexState.Ready(items = listOf(testItem(seed = 1)))

        val drawn = album.toAlbumUi(album.resolveAgainst(index))

        assertEquals(2, drawn.memberCount)
        assertEquals(1, drawn.staleCount)
        assertTrue(drawn.resolved)
    }

    @Test
    fun `an album that could not be resolved counts what the record holds and says so`() {
        val album = testAlbum(seed = 1, name = "Trip", itemIds = listOf(testItemId(1), testItemId(2)))

        val drawn = album.toAlbumUi(album.resolveAgainst(VaultIndexState.Unavailable))

        assertEquals(2, drawn.memberCount)
        assertEquals(0, drawn.staleCount)
        assertFalse("nothing is claimed about files that were not read", drawn.resolved)
    }

    @Test
    fun `an item is drawn the same way in every collection`() {
        val item = testItem(seed = 1, name = "holiday.jpg", mimeType = "image/jpeg")
        val album = testAlbum(seed = 1, name = "Trip", itemIds = listOf(item.id))

        val fromTheIndex = VaultIndexState.Ready(items = listOf(item)).toUiState()
            .let { state -> (state as VaultIndexUiState.Indexed).items.single() }
        val fromTheAlbum = (album.resolveAgainst(VaultIndexState.Ready(items = listOf(item))).toUiState()
            as VaultAlbumContentsUi.Resolved).items.single()

        assertEquals("one row mapping, one way to look at a file", fromTheIndex, fromTheAlbum)
    }

    // ------------------------------------------------------------------ failures

    @Test
    fun `every way a change can fail has its own sentence`() {
        val messages = everyFailure().map { failure -> failure.asMessage().textRes }

        assertTrue("no failure is drawn with a blank message", messages.none { id -> id == 0 })
        assertEquals(
            "failures that mean different things must not say the same thing",
            messages.size,
            messages.toSet().size,
        )
    }

    @Test
    fun `a missing key is one cause, so it is one sentence wherever it is reported`() {
        assertEquals(
            "the record and the change fail for the same reason, and say so in the same words",
            VaultOrganizationFailure.OrganizationUnreadable(VaultOrganizationUnreadable.KeyUnavailable)
                .asMessage()
                .textRes,
            VaultOrganizationFailure.KeyUnavailable.asMessage().textRes,
        )
    }

    @Test
    fun `a refusal is never worded as though the albums were gone`() {
        everyFailure().forEach { failure ->
            assertNotEquals(
                "$failure must not read as an empty album list",
                R.string.vault_albums_none,
                failure.asMessage().textRes,
            )
        }
    }

    @Test
    fun `a change that could not be named has a sentence of its own`() {
        assertNotEquals(0, vaultAlbumChangeFailedMessage().textRes)
        assertNotEquals(0, vaultOrganizationUnavailableMessage().textRes)
        assertNotEquals(
            "the reason a change was refused is not the same as the change failing",
            vaultAlbumChangeFailedMessage().textRes,
            vaultOrganizationUnavailableMessage().textRes,
        )
    }

    @Test
    fun `being locked is explained by the gate that already exists`() {
        assertEquals(R.string.vault_locked, vaultLockedMessage().textRes)
    }

    // ------------------------------------------------------------------ the query's states

    @Test
    fun `whether a query is filtering is asked of one place`() {
        assertFalse(VaultSearchUiState.NotAsked.isActive)
        assertTrue(VaultSearchUiState.Matches.isActive)
        assertTrue(VaultSearchUiState.NoMatches.isActive)
        assertTrue(VaultSearchUiState.CannotSearch.isActive)
    }

    @Test
    fun `an unanswerable search and an empty result are worded differently`() {
        assertEquals(4, VaultSearchUiState.entries.size)
        assertNotEquals(
            "not being able to search must never be drawn as nothing matching",
            R.string.vault_search_cannot_search,
            R.string.vault_search_no_matches,
        )
        assertNotEquals(
            "and the two surfaces answer about their own collection",
            R.string.vault_search_no_matches,
            R.string.vault_search_no_album_matches,
        )
        assertNotEquals(
            R.string.vault_search_result_format,
            R.string.vault_search_albums_result_format,
        )
        assertNotEquals(
            R.string.vault_search_cannot_search,
            R.string.vault_search_cannot_search_albums,
        )
    }
}
