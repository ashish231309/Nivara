package com.nivara.app.domain.vault

import com.nivara.app.testing.testAlbum
import com.nivara.app.testing.testItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for what a query does and what it is allowed to look at.
 *
 * The rule the whole feature rests on is that a search reads *metadata*: a name, a declared type and
 * the kind Nivara classifies that type as. Nothing below hands the search a file, a handle, a key or a
 * reader, and the tests that matter most are the ones about what a query must not do — it does not
 * touch content, it does not rank, it does not remember, and it never turns "I could not read the list"
 * into "nothing matches".
 */
class VaultSearchTest {

    private val holiday = testItem(seed = 1, name = "Holiday Trip.jpg", mimeType = "image/jpeg")
    private val clip = testItem(seed = 2, name = "clip.mp4", mimeType = "video/mp4")
    private val song = testItem(seed = 3, name = "Song.mp3", mimeType = "audio/mpeg")
    private val notes = testItem(seed = 4, name = "notes.txt", mimeType = "text/plain")
    private val blob = testItem(seed = 5, name = "archive.bin", mimeType = null)
    private val everything = listOf(holiday, clip, song, notes, blob)

    private fun query(text: String): VaultSearchQuery = VaultSearchQuery.of(text)

    private fun results(text: String): List<VaultItem> =
        VaultSearch.filter(everything, query(text))

    // ------------------------------------------------------------------ the empty question

    @Test
    fun `an empty query asks nothing and matches nothing`() {
        assertTrue(VaultSearchQuery.NONE.isBlank)
        assertFalse(VaultSearch.matches(holiday, VaultSearchQuery.NONE))
        assertEquals("an unasked filter leaves the list alone", everything, VaultSearch.filter(everything, VaultSearchQuery.NONE))
    }

    @Test
    fun `a query of whitespace is an empty query`() {
        assertTrue(query("   ").isBlank)
        assertTrue(query("\n\t ").isBlank)
    }

    // ------------------------------------------------------------------ names

    @Test
    fun `a name matches regardless of case`() {
        assertEquals(listOf(holiday), results("holiday"))
        assertEquals(listOf(holiday), results("HOLIDAY"))
        assertEquals(listOf(holiday), results("HoLiDaY"))
    }

    @Test
    fun `a match may be anywhere in the name, not only at the start`() {
        assertEquals(listOf(holiday), results("trip"))
        assertEquals(listOf(holiday), results("lip"))
    }

    @Test
    fun `leading, trailing and repeated whitespace in the query does not matter`() {
        assertEquals(listOf(holiday), results("   holiday   trip "))
        assertEquals(listOf(holiday), results("holiday\ttrip"))
    }

    @Test
    fun `a query with several words matches a name containing them in that order`() {
        assertEquals(listOf(holiday), results("trip jpg"))
    }

    @Test
    fun `a word no file contains finds nothing`() {
        assertTrue(results("zzz").isEmpty())
    }

    @Test
    fun `the result keeps the order the list was in and is never rearranged by relevance`() {
        val matches = results("o")

        assertEquals(
            "the order is the list's, filtered — there is no ranking",
            everything.filter { item -> item in matches },
            matches,
        )
    }

    @Test
    fun `a query is answered the same way twice`() {
        assertEquals(results("holiday"), results("holiday"))
    }

    // ------------------------------------------------------------------ Unicode

    @Test
    fun `a composed and a decomposed name find each other`() {
        val composed = testItem(seed = 11, name = "caf\u00e9.jpg", mimeType = "image/jpeg")
        val decomposed = testItem(seed = 12, name = "cafe\u0301.jpg", mimeType = "image/jpeg")
        val items = listOf(composed, decomposed)

        assertEquals(
            "the same word typed either way finds both",
            listOf(composed, decomposed),
            VaultSearch.filter(items, query("caf\u00e9")),
        )
        assertEquals(
            "and so does the other spelling",
            listOf(composed, decomposed),
            VaultSearch.filter(items, query("cafe\u0301")),
        )
    }

    @Test
    fun `non-Latin names are searched as they are typed`() {
        val hindi = testItem(seed = 13, name = "\u0924\u0938\u094d\u0935\u0940\u0930.jpg", mimeType = "image/jpeg")

        assertEquals(listOf(hindi), VaultSearch.filter(listOf(hindi), query("\u0924\u0938\u094d\u0935")))
    }

    @Test
    fun `case folding does not depend on the device's language`() {
        val dotted = testItem(seed = 14, name = "Istanbul.txt", mimeType = "text/plain")

        assertEquals("a Turkish device finds the same file", listOf(dotted), VaultSearch.filter(listOf(dotted), query("istanbul")))
    }

    // ------------------------------------------------------------------ the declared type

    @Test
    fun `a declared type is searchable`() {
        assertEquals(listOf(clip), results("video/mp4"))
        assertEquals(listOf(notes), results("text/plain"))
        assertEquals("a type anywhere matches", listOf(clip, song), results("mp"))
    }

    @Test
    fun `an item with no declared type is still found by its name and kind`() {
        assertEquals(listOf(blob), results("archive"))
        assertEquals(listOf(blob), results("file"))
    }

    // ------------------------------------------------------------------ the kind

    @Test
    fun `each kind is found by the words the list shows it with`() {
        assertTrue("image", holiday in results("image"))
        assertTrue("picture", holiday in results("picture"))
        assertTrue("photo", holiday in results("photo"))
        assertTrue("video", clip in results("video"))
        assertTrue("movie", clip in results("movie"))
        assertTrue("audio", song in results("audio"))
        assertTrue("music", song in results("music"))
        assertTrue("sound", song in results("sound"))
        assertTrue("document", notes in results("document"))
        assertTrue("doc", notes in results("doc"))
    }

    @Test
    fun `a kind word finds every file of that kind`() {
        val pictures = listOf(holiday, testItem(seed = 6, name = "beach.png", mimeType = "image/png"))

        assertEquals(pictures, VaultSearch.filter(pictures, query("photo")))
    }

    @Test
    fun `a kind word does not match a file of another kind`() {
        assertFalse(clip in results("photo"))
        assertFalse(holiday in results("music"))
    }

    // ------------------------------------------------------------------ what is not searched

    @Test
    fun `a search never has to read content, so a listed file with no content is still found`() {
        val listedWithoutContent = testItem(seed = 20, name = "gone.jpg", mimeType = "image/jpeg")
        val index = VaultIndexState.Ready(items = listOf(listedWithoutContent), missingContent = setOf(listedWithoutContent.id))

        val found = index.search(query("gone")) as VaultSearchResult.Found

        assertEquals(listOf(listedWithoutContent), found.items)
    }

    @Test
    fun `album membership is not searched, so a query cannot be answered by organisation`() {
        val album = testAlbum(seed = 1, name = "Holiday", itemIds = listOf(clip.id))

        assertFalse("the album's title is not a fact about its items", VaultSearch.matches(clip, query("holiday")))
        assertEquals(
            "an item in an album is found by its own name",
            listOf(clip),
            VaultSearch.filter(listOf(clip), query("clip")),
        )
        assertEquals(
            "and the title finds the album on the albums surface",
            listOf(album),
            VaultSearch.filterAlbums(listOf(album), query("holiday")),
        )
    }

    @Test
    fun `an album title is searchable on the albums surface`() {
        val album = testAlbum(seed = 1, name = "Summer Holiday 2024")

        assertTrue(VaultSearch.matches(album, query("holiday")))
        assertTrue(VaultSearch.matches(album, query("SUMMER")))
        assertFalse(VaultSearch.matches(album, query("winter")))
        assertEquals(listOf(album), VaultSearch.filterAlbums(listOf(album), query("2024")))
    }

    // ------------------------------------------------------------------ the states a search can be in

    @Test
    fun `an unreadable list cannot be searched, and that is not an empty result`() {
        val states = listOf(
            VaultIndexState.Unreadable(VaultIndexUnreadable.MetadataDamaged),
            VaultIndexState.Unreadable(VaultIndexUnreadable.KeyUnavailable),
            VaultIndexState.UnsupportedVersion(fileVersion = 2),
            VaultIndexState.Unavailable,
            VaultIndexState.AccessDenied,
            VaultIndexState.VaultNotReady(VaultState.Missing),
        )

        states.forEach { state ->
            val result = state.search(query("holiday"))

            result as VaultSearchResult.CannotSearch
            assertEquals("the reason travels with it", state, result.index)
        }
    }

    @Test
    fun `an unasked query is not a result`() {
        assertEquals(VaultSearchResult.NotAsked, VaultIndexState.Ready(items = everything).search(VaultSearchQuery.NONE))
        assertEquals(VaultSearchResult.NotAsked, VaultIndexState.Missing.search(VaultSearchQuery.NONE))
    }

    @Test
    fun `a vault with no list record at all answers a question with nothing, which is an answer`() {
        val result = VaultIndexState.Missing.search(query("holiday"))

        result as VaultSearchResult.Found
        assertTrue("the vault is known to hold nothing", result.items.isEmpty())
        assertTrue(result.isEmpty)
    }

    @Test
    fun `a readable list with nothing matching is a result, not a failure`() {
        val result = VaultIndexState.Ready(items = everything).search(query("zzz"))

        result as VaultSearchResult.Found
        assertTrue(result.isEmpty)
    }

    @Test
    fun `the empty result and the unsearchable state are different answers`() {
        val empty = VaultIndexState.Ready(items = everything).search(query("zzz"))
        val cannot = VaultIndexState.Unavailable.search(query("zzz"))

        assertNotEquals(empty, cannot)
        assertFalse("one is an answer about the vault", cannot is VaultSearchResult.Found)
    }

    @Test
    fun `the query's own text is never drawn`() {
        val query = query("holiday")

        assertFalse("a query does not print what was typed", query.toString().contains("holiday"))
    }

    @Test
    fun `normalizing is idempotent, so filtering can be repeated safely`() {
        val once = VaultSearch.normalize("  Holiday   Trip  ")

        assertEquals("holiday trip", once)
        assertEquals(once, VaultSearch.normalize(once))
    }
}
