package com.nivara.app.domain.vault

import com.nivara.app.testing.testAlbum
import com.nivara.app.testing.testAlbumId
import com.nivara.app.testing.testItem
import com.nivara.app.testing.testItemId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for what an album *is*.
 *
 * Everything checked here is a rule about organisation and nothing about storage: an album holds
 * references, membership is a set that keeps its order, an item may belong to as many albums as its
 * owner likes, and a reference the index no longer names is kept rather than tidied away. None of it
 * needs a device, a vault or a key — which is the point of keeping the rules in the domain.
 */
class VaultAlbumTest {

    // ------------------------------------------------------------------ membership

    @Test
    fun `a new album holds nothing and that is a valid album`() {
        val album = testAlbum(seed = 1, name = "Trip")

        assertEquals("Trip", album.name)
        assertTrue(album.itemIds.isEmpty())
        assertEquals(0, album.size)
        assertFalse(album.contains(testItemId(7)))
    }

    @Test
    fun `adding an item appends it once and keeps the order it was added in`() {
        val album = testAlbum(seed = 1)
            .adding(testItemId(7))
            .adding(testItemId(8))
            .adding(testItemId(9))

        assertEquals(listOf(testItemId(7), testItemId(8), testItemId(9)), album.itemIds)
    }

    @Test
    fun `adding an item that is already in the album changes nothing`() {
        val album = testAlbum(seed = 1).adding(testItemId(7))

        val again = album.adding(testItemId(7))

        assertEquals("a second add is the same album", album, again)
        assertEquals("and the item is named once", 1, again.itemIds.count { id -> id == testItemId(7) })
    }

    @Test
    fun `removing an item that is not in the album changes nothing`() {
        val album = testAlbum(seed = 1).adding(testItemId(7))

        assertEquals(album, album.removing(testItemId(8)))
    }

    @Test
    fun `removing an item takes it out and leaves the rest in order`() {
        val album = testAlbum(seed = 1)
            .adding(testItemId(7))
            .adding(testItemId(8))
            .adding(testItemId(9))
            .removing(testItemId(8))

        assertEquals(listOf(testItemId(7), testItemId(9)), album.itemIds)
    }

    @Test
    fun `an empty album stays valid after its last item is removed`() {
        val album = testAlbum(seed = 1).adding(testItemId(7)).removing(testItemId(7))

        assertTrue("emptying an album is not deleting it", album.itemIds.isEmpty())
    }

    @Test
    fun `the same item may belong to several albums`() {
        val shared = testItemId(7)

        val first = testAlbum(seed = 1, name = "First").adding(shared)
        val second = testAlbum(seed = 2, name = "Second").adding(shared)

        assertTrue("the first album names it", first.contains(shared))
        assertTrue("and so does the second", second.contains(shared))
    }

    @Test
    fun `removing an item from one album leaves the other album untouched`() {
        val shared = testItemId(7)
        val first = testAlbum(seed = 1).adding(shared)
        val second = testAlbum(seed = 2).adding(shared)

        val trimmed = first.removing(shared)

        assertFalse(trimmed.contains(shared))
        assertTrue("the other album still names it", second.contains(shared))
    }

    @Test
    fun `membership belongs to the album and changing it leaves the original alone`() {
        val album = testAlbum(seed = 1)

        val added = album.adding(testItemId(7))

        assertTrue("the receiver is unchanged", album.itemIds.isEmpty())
        assertFalse("the copy holds the item", added.itemIds.isEmpty())
    }

    // ------------------------------------------------------------------ the name

    @Test
    fun `a name is trimmed and inner whitespace collapsed`() {
        val album = testAlbum(seed = 1, name = "Trip")

        assertEquals("Summer 2024", album.renamedTo("  Summer \n 2024 ")?.name)
    }

    @Test
    fun `a name that is not usable is refused`() {
        val album = testAlbum(seed = 1)

        assertNull("blank", album.renamedTo("   "))
        assertNull("empty", album.renamedTo(""))
        assertNull("a control character", album.renamedTo("Trip\u0000"))
        assertNull("longer than the limit", album.renamedTo("x".repeat(VaultAlbumNames.MAXIMUM_LENGTH + 1)))
    }

    @Test
    fun `a name at the limit is accepted`() {
        val album = testAlbum(seed = 1)

        val renamed = album.renamedTo("x".repeat(VaultAlbumNames.MAXIMUM_LENGTH))

        assertEquals(VaultAlbumNames.MAXIMUM_LENGTH, renamed?.name?.length)
    }

    @Test
    fun `renaming keeps the identifier and the membership`() {
        val album = testAlbum(seed = 1, name = "Old").adding(testItemId(7))

        val renamed = album.renamedTo("New")

        assertEquals(album.id, renamed?.id)
        assertEquals(listOf(testItemId(7)), renamed?.itemIds)
        assertEquals("albums are identified by their id, not their name", testAlbumId(1), renamed?.id)
    }

    @Test
    fun `two albums may share a name and stay two albums`() {
        val first = testAlbum(seed = 1, name = "Trip")
        val second = testAlbum(seed = 2, name = "Trip")

        assertEquals(first.name, second.name)
        assertFalse("the identifier is what tells them apart", first.id == second.id)
    }

    // ------------------------------------------------------------------ the record's own rules

    @Test(expected = IllegalArgumentException::class)
    fun `an album cannot be built with an unusable name`() {
        testAlbum(seed = 1, name = " ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an album cannot name the same item twice`() {
        testAlbum(seed = 1, itemIds = listOf(testItemId(7), testItemId(7)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an album cannot hold more items than the format allows`() {
        val members = (1..(VaultOrganizationLimits.MAXIMUM_MEMBERS_PER_ALBUM + 1)).map { seed -> testItemId(seed) }

        testAlbum(seed = 1, itemIds = members)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an album cannot be created before the epoch`() {
        testAlbum(seed = 1, createdAtEpochMillis = -1L)
    }

    // ------------------------------------------------------------------ resolving against the index

    @Test
    fun `an album resolves to the items the index names, in album order`() {
        val first = testItem(seed = 1, name = "a.txt")
        val second = testItem(seed = 2, name = "b.txt")
        val index = VaultIndexState.Ready(items = listOf(second, first))

        val contents = testAlbum(seed = 9, itemIds = listOf(first.id, second.id)).resolveAgainst(index)

        contents as VaultAlbumContents.Resolved
        assertEquals("the album's order, not the index's", listOf(first, second), contents.items)
        assertTrue(contents.staleItemIds.isEmpty())
    }

    @Test
    fun `a reference the index does not name is kept and reported as stale`() {
        val known = testItem(seed = 1)
        val index = VaultIndexState.Ready(items = listOf(known))

        val contents = testAlbum(seed = 9, itemIds = listOf(known.id, testItemId(404)))
            .resolveAgainst(index)

        contents as VaultAlbumContents.Resolved
        assertEquals(listOf(known), contents.items)
        assertEquals("nothing is dropped", listOf(testItemId(404)), contents.staleItemIds)
    }

    @Test
    fun `an album whose every reference is stale reports them all`() {
        val index = VaultIndexState.Ready(items = emptyList())

        val contents = testAlbum(seed = 9, itemIds = listOf(testItemId(404), testItemId(405)))
            .resolveAgainst(index)

        contents as VaultAlbumContents.Resolved
        assertTrue(contents.items.isEmpty())
        assertEquals(listOf(testItemId(404), testItemId(405)), contents.staleItemIds)
    }

    @Test
    fun `an empty album resolves to nothing without inventing stale references`() {
        val index = VaultIndexState.Ready(items = listOf(testItem(seed = 1)))

        val contents = testAlbum(seed = 9).resolveAgainst(index)

        contents as VaultAlbumContents.Resolved
        assertTrue(contents.isEmpty)
    }

    @Test
    fun `an index that cannot be read leaves the album unresolved rather than empty`() {
        val states = listOf(
            VaultIndexState.Missing,
            VaultIndexState.Unreadable(VaultIndexUnreadable.MetadataDamaged),
            VaultIndexState.Unreadable(VaultIndexUnreadable.KeyUnavailable),
            VaultIndexState.UnsupportedVersion(fileVersion = 2),
            VaultIndexState.Unavailable,
            VaultIndexState.AccessDenied,
            VaultIndexState.VaultNotReady(VaultState.Missing),
        )

        states.forEach { state ->
            val contents = testAlbum(seed = 9, itemIds = listOf(testItemId(7))).resolveAgainst(state)

            contents as VaultAlbumContents.Unresolved
            assertEquals("the reason travels with it", state, contents.index)
            assertEquals("and the album is still the album", testAlbumId(9), contents.album.id)
        }
    }

    @Test
    fun `resolving leaves the album it was given untouched`() {
        val album = testAlbum(seed = 9, itemIds = listOf(testItemId(404)))

        album.resolveAgainst(VaultIndexState.Ready(items = emptyList()))

        assertEquals(listOf(testItemId(404)), album.itemIds)
    }

    @Test
    fun `resolving a list of albums reports one result per album, in order`() {
        val albums = listOf(testAlbum(seed = 1), testAlbum(seed = 2))

        val resolved = albums.resolveAgainst(VaultIndexState.Ready(items = emptyList()))

        assertEquals(2, resolved.size)
        assertEquals(listOf(testAlbumId(1), testAlbumId(2)), resolved.map { contents -> contents.album.id })
    }

    @Test
    fun `the domain's album type is a value, so equal records are equal albums`() {
        val one = testAlbum(seed = 1, name = "Trip", itemIds = listOf(testItemId(7)))
        val same = testAlbum(seed = 1, name = "Trip", itemIds = listOf(testItemId(7)))

        assertEquals(one, same)
        assertEquals(one.hashCode(), same.hashCode())
    }
}
