package com.nivara.app.domain.vault

import com.nivara.app.domain.security.SecureRandomGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for the vault's content model.
 *
 * The model is deliberately small, and every part of it is a promise the rest of the stage depends
 * on: an identifier that is random and fixed, a name that can never be a path, a type that is either
 * a usable type or nothing, and a digest that describes bytes. What is checked here is the boundary —
 * the values that must be refused — because a name that travels into storage is a name a provider
 * chose, and a digest that is accepted loosely is a digest that stops meaning anything.
 */
class VaultItemTest {

    private val random = SecureRandomGenerator()

    // ------------------------------------------------------------------ identifiers

    @Test
    fun `an identifier is sixteen random bytes in lower-case hex`() {
        val id = VaultItemId.create(random)

        assertEquals(VaultItemId.BYTES * 2, id.value.length)
        assertTrue(VaultItemId.isWellFormed(id.value))
        assertTrue(id.value.none { character -> character !in "0123456789abcdef" })
    }

    @Test
    fun `generated identifiers do not repeat`() {
        val generated = List(200) { VaultItemId.create(random) }

        assertEquals(200, generated.toSet().size)
    }

    @Test
    fun `an identifier survives its own bytes`() {
        val id = VaultItemId.create(random)

        assertEquals(id, VaultItemId.fromBytes(id.toBytes()))
    }

    @Test
    fun `bytes that are not sixteen bytes long are not an identifier`() {
        assertNull(VaultItemId.fromBytes(ByteArray(15)))
        assertNull(VaultItemId.fromBytes(ByteArray(17)))
        assertNull(VaultItemId.fromBytes(ByteArray(0)))
    }

    @Test
    fun `identifiers are only the hex characters the format writes`() {
        assertFalse(VaultItemId.isWellFormed(""))
        assertFalse(VaultItemId.isWellFormed("00112233445566778899aabbccddeef")) // one short
        assertFalse(VaultItemId.isWellFormed("00112233445566778899aabbccddeeff0")) // one long
        assertFalse(VaultItemId.isWellFormed("00112233445566778899AABBCCDDEEFF")) // upper case
        assertFalse(VaultItemId.isWellFormed("00112233445566778899aabbccddeefg")) // not hex
    }

    @Test
    fun `an identifier never prints itself`() {
        val id = VaultItemId.create(random)

        assertFalse(id.toString().contains(id.value))
    }

    // ------------------------------------------------------------------ names

    @Test
    fun `an ordinary file name is usable`() {
        assertTrue(VaultItemNames.isWellFormed("holiday photo.jpg"))
        assertTrue(VaultItemNames.isWellFormed("notes-2026-01-01.txt"))
        assertTrue(VaultItemNames.isWellFormed("छुट्टी.jpg".take(20)))
    }

    @Test
    fun `a name that is not usable text is refused`() {
        assertFalse("an empty name is not a name", VaultItemNames.isWellFormed(""))
        assertFalse(VaultItemNames.isWellFormed("   "))
        assertFalse("no control characters", VaultItemNames.isWellFormed("notes\u0001.txt"))
        assertFalse("no NUL", VaultItemNames.isWellFormed("notes\u0000.txt"))
        assertFalse("not a path", VaultItemNames.isWellFormed("folder/notes.txt"))
        assertFalse("not a windows path", VaultItemNames.isWellFormed("folder\\notes.txt"))
        assertFalse("no traversal", VaultItemNames.isWellFormed(".."))
        assertFalse("no traversal anywhere", VaultItemNames.isWellFormed("a..b"))
        assertFalse(
            "the length is bounded",
            VaultItemNames.isWellFormed("a".repeat(VaultItemNames.MAXIMUM_LENGTH + 1)),
        )
    }

    @Test
    fun `a path-shaped name is reduced to its own segment`() {
        assertEquals("notes.txt", VaultItemNames.sanitize("/storage/emulated/0/Documents/notes.txt"))
        assertEquals("notes.txt", VaultItemNames.sanitize("Documents\\notes.txt"))
        assertNull("a trailing separator leaves no name at all", VaultItemNames.sanitize("Documents/"))
    }

    @Test
    fun `sanitising never turns an unusable name into a usable one`() {
        assertNull(VaultItemNames.sanitize(".."))
        assertNull(VaultItemNames.sanitize(""))
        assertNull(VaultItemNames.sanitize("notes\u0000.txt"))
        assertEquals("notes.txt", VaultItemNames.sanitize("notes.txt"))
    }

    // ------------------------------------------------------------------ declared types

    @Test
    fun `a type is a single type and subtype`() {
        assertTrue(VaultItemNames.isWellFormedMimeType("text/plain"))
        assertTrue(VaultItemNames.isWellFormedMimeType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        assertFalse(VaultItemNames.isWellFormedMimeType(""))
        assertFalse(VaultItemNames.isWellFormedMimeType("text"))
        assertFalse(VaultItemNames.isWellFormedMimeType("text/plain/extra"))
        assertFalse(VaultItemNames.isWellFormedMimeType("text / plain"))
        assertFalse(VaultItemNames.isWellFormedMimeType("text/pl\u0000ain"))
        assertFalse(
            "a type is bounded too",
            VaultItemNames.isWellFormedMimeType("a".repeat(VaultItemNames.MAXIMUM_MIME_LENGTH + 1)),
        )
    }

    // ------------------------------------------------------------------ digests

    @Test
    fun `a digest is the hash of some bytes, in hex`() {
        val bytes = ByteArray(32) { index -> (index * 7).toByte() }
        val digest = VaultContentDigest.fromBytes(bytes)

        assertTrue(digest != null)
        assertEquals(64, digest!!.value.length)
        assertTrue(VaultContentDigest.isWellFormed(digest.value))
        assertEquals(digest, VaultContentDigest.fromBytes(bytes))
        assertNotEquals(digest, VaultContentDigest.fromBytes(ByteArray(32) { 1 }))
    }

    @Test
    fun `a digest that is not thirty-two bytes is not a digest`() {
        assertNull(VaultContentDigest.fromBytes(ByteArray(31)))
        assertNull(VaultContentDigest.fromBytes(ByteArray(33)))
        assertFalse(VaultContentDigest.isWellFormed(""))
        // Letters, so case is actually being tested: "00" repeated would be its own uppercase.
        assertFalse(VaultContentDigest.isWellFormed("AB".repeat(32)))
        assertFalse(VaultContentDigest.isWellFormed("ab".repeat(31)))
        assertTrue(VaultContentDigest.isWellFormed("ab".repeat(32)))
    }

    // ------------------------------------------------------------------ the item

    @Test
    fun `an item describes its file and prints none of it`() {
        val item = VaultItem(
            id = VaultItemId.create(random),
            name = "private notes.txt",
            mimeType = "text/plain",
            sizeBytes = 42,
            importedAtEpochMillis = 1_700_000_000_000,
            contentFormatVersion = 1,
            contentDigest = VaultContentDigest.fromBytes(ByteArray(32) { 3 })!!,
        )

        assertEquals("private notes.txt", item.name)
        assertFalse("a file name is never printed", item.toString().contains("private notes.txt"))
        assertFalse(item.toString().contains(item.id.value))
    }

    @Test
    fun `an item's equality describes every fact the index stores`() {
        val id = VaultItemId.create(random)
        val digest = VaultContentDigest.fromBytes(ByteArray(32) { 9 })!!
        val first = VaultItem(id, "a.txt", "text/plain", 1L, 2L, 1, digest)

        assertEquals(first, VaultItem(id, "a.txt", "text/plain", 1L, 2L, 1, digest))
        assertNotEquals(first, VaultItem(id, "b.txt", "text/plain", 1L, 2L, 1, digest))
        assertNotEquals(first, VaultItem(id, "a.txt", null, 1L, 2L, 1, digest))
        assertNotEquals(first, VaultItem(id, "a.txt", "text/plain", 2L, 2L, 1, digest))
        assertNotEquals(first, VaultItem(id, "a.txt", "text/plain", 1L, 3L, 1, digest))
        assertNotEquals(first, VaultItem(id, "a.txt", "text/plain", 1L, 2L, 2, digest))
        assertNotEquals(first, VaultItem(id, "a.txt", "text/plain", 1L, 2L, 1, VaultContentDigest.fromBytes(ByteArray(32) { 8 })!!))
    }

    @Test
    fun `a reference to a source is opaque and bounded`() {
        val reference = VaultSourceReference.create("content://provider/document/42")

        assertTrue(reference != null)
        assertEquals("content://provider/document/42", reference!!.value)
        assertFalse("a reference is never printed", reference.toString().contains("provider"))
        assertNull(VaultSourceReference.create(""))
        assertNull(VaultSourceReference.create("a".repeat(VaultSourceReference.MAXIMUM_LENGTH + 1)))
    }
}
