package com.nivara.app.data.vault

import com.nivara.app.domain.vault.VaultAlbumNames
import com.nivara.app.domain.vault.VaultItemId
import com.nivara.app.domain.vault.VaultOrganizationLimits
import com.nivara.app.testing.testAlbum
import com.nivara.app.testing.testAlbumId
import com.nivara.app.testing.testItemId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for the album record's bytes.
 *
 * A codec's job is to be readable by the person who wrote it and by nobody else, so most of this file
 * is about what must be *refused*: a count nobody could have written, a length that runs past the end
 * of the record, a name that is not valid UTF-8, a record that names the same album or the same item
 * twice, a generation that disagrees with its own header, bytes after the last album. Every one of
 * those is checked before anything is allocated from it, because a count read from storage is a claim
 * rather than a fact. The crafted records below are written byte by byte, exactly as an attacker's
 * would be.
 */
class VaultOrganizationCodecTest {

    private val holidays = testAlbum(seed = 1, name = "Holidays", itemIds = listOf(testItemId(11), testItemId(12)))
    private val recipes = testAlbum(seed = 2, name = "Recipes \u2013 sweet", itemIds = listOf(testItemId(21)))

    // ------------------------------------------------------------------ round trip

    @Test
    fun `what is written is read back exactly`() {
        val payload = VaultOrganizationCodec.encodePayload(generation = 7L, albums = listOf(holidays, recipes))
        assertNotNull(payload)

        val decoded = VaultOrganizationCodec.decodePayload(payload!!, expectedGeneration = 7L)

        assertNotNull(decoded)
        assertEquals(7L, decoded!!.generation)
        assertEquals(listOf(holidays, recipes), decoded.albums)
        assertEquals("membership keeps its order", listOf(testItemId(11), testItemId(12)), decoded.albums[0].itemIds)
    }

    @Test
    fun `a record with no albums is a valid record, not an absent one`() {
        val payload = VaultOrganizationCodec.encodePayload(generation = 1L, albums = emptyList())
        assertNotNull("an empty album list is written, so it can be read", payload)

        val decoded = VaultOrganizationCodec.decodePayload(payload!!, expectedGeneration = 1L)

        assertNotNull(decoded)
        assertTrue(decoded!!.albums.isEmpty())
    }

    @Test
    fun `an album that holds nothing is written and read back as an album`() {
        val empty = testAlbum(seed = 3, name = "Empty")

        val payload = VaultOrganizationCodec.encodePayload(generation = 1L, albums = listOf(empty))
        val decoded = VaultOrganizationCodec.decodePayload(payload!!, expectedGeneration = 1L)

        assertEquals(listOf(empty), decoded!!.albums)
    }

    @Test
    fun `the clear header names what the bytes are and which generation they hold`() {
        val record = VaultOrganizationCodec.encodeRecord(
            generation = 4L,
            envelope = ByteArray(8) { index -> index.toByte() },
        )

        assertArrayEquals(VaultOrganizationCodec.MAGIC, record.copyOfRange(0, 4))
        assertEquals(VaultOrganizationCodec.VERSION, record[4].toInt())
        assertEquals(0, record[5].toInt())

        val header = VaultOrganizationCodec.readHeader(record)
        assertTrue(header is VaultOrganizationCodec.HeaderRead.Present)
        assertEquals(4L, (header as VaultOrganizationCodec.HeaderRead.Present).generation)
        assertArrayEquals(
            "the envelope follows the header untouched",
            ByteArray(8) { index -> index.toByte() },
            VaultOrganizationCodec.envelopeOf(record),
        )
    }

    // ------------------------------------------------------------------ what is not an album record

    @Test
    fun `something that is not an album record is reported as foreign, not as damaged`() {
        val header = VaultOrganizationCodec.readHeader("NVIN\u0001\u0000".toByteArray())

        assertEquals(VaultOrganizationCodec.HeaderRead.NotAnAlbumRecord, header)
    }

    @Test
    fun `an empty file is foreign rather than damaged`() {
        assertEquals(VaultOrganizationCodec.HeaderRead.NotAnAlbumRecord, VaultOrganizationCodec.readHeader(ByteArray(0)))
    }

    @Test
    fun `a record from a newer build is reported with its version`() {
        val newer = PayloadWrite.ascii("NVAO").byte(2).byte(0).long(1L).toByteArray()

        val header = VaultOrganizationCodec.readHeader(newer)

        assertTrue(header is VaultOrganizationCodec.HeaderRead.Unreadable)
        assertEquals(2, (header as VaultOrganizationCodec.HeaderRead.Unreadable).fileVersion)
    }

    @Test
    fun `a marker with nothing after it is damaged, and says so rather than being passed over`() {
        val header = VaultOrganizationCodec.readHeader("NVAO".toByteArray())

        assertTrue(header is VaultOrganizationCodec.HeaderRead.Unreadable)
    }

    @Test
    fun `a reserved byte that is not zero is refused`() {
        val record = VaultOrganizationCodec.encodeRecord(1L, ByteArray(4)).also { bytes -> bytes[5] = 1 }

        assertTrue(VaultOrganizationCodec.readHeader(record) is VaultOrganizationCodec.HeaderRead.Unreadable)
    }

    @Test
    fun `a generation that is not a generation is refused`() {
        val zero = VaultOrganizationCodec.encodeRecord(1L, ByteArray(4)).also { bytes ->
            for (index in 6 until 14) bytes[index] = 0
        }

        assertTrue(VaultOrganizationCodec.readHeader(zero) is VaultOrganizationCodec.HeaderRead.Unreadable)
    }

    // ------------------------------------------------------------------ the payload's own rules

    @Test
    fun `a payload whose generation disagrees with the header is refused`() {
        val payload = VaultOrganizationCodec.encodePayload(generation = 5L, albums = listOf(holidays))

        assertNull(
            "a record somebody moved cannot become authoritative",
            VaultOrganizationCodec.decodePayload(payload!!, expectedGeneration = 6L),
        )
    }

    @Test
    fun `bytes after the last album are refused rather than ignored`() {
        val payload = VaultOrganizationCodec.encodePayload(generation = 1L, albums = listOf(holidays))!!
        val extended = payload + byteArrayOf(0, 0, 0)

        assertNull(VaultOrganizationCodec.decodePayload(extended, expectedGeneration = 1L))
    }

    @Test
    fun `a record whose marker or version is wrong is refused`() {
        val payload = VaultOrganizationCodec.encodePayload(generation = 1L, albums = listOf(holidays))!!

        assertNull(VaultOrganizationCodec.decodePayload(payload.copyOf().also { it[0] = 'X'.code.toByte() }, 1L))
        assertNull(VaultOrganizationCodec.decodePayload(payload.copyOf().also { it[4] = 9 }, 1L))
        assertNull(VaultOrganizationCodec.decodePayload(payload.copyOf().also { it[5] = 1 }, 1L))
    }

    @Test
    fun `an album count nobody could have written is refused before anything is allocated`() {
        val claimed = payload(generation = 1L, count = 60_000) { }

        assertNull(VaultOrganizationCodec.decodePayload(claimed, expectedGeneration = 1L))
    }

    @Test
    fun `an album count larger than the format allows is refused`() {
        val claimed = payload(generation = 1L, count = VaultOrganizationLimits.MAXIMUM_ALBUMS + 1) { }

        assertNull(VaultOrganizationCodec.decodePayload(claimed, expectedGeneration = 1L))
    }

    @Test
    fun `a count that promises albums the record does not hold is refused`() {
        val claimed = payload(generation = 1L, count = 3) {
            album(seed = 1, name = "One", createdAt = 1L, members = emptyList())
        }

        assertNull(VaultOrganizationCodec.decodePayload(claimed, expectedGeneration = 1L))
    }

    @Test
    fun `a duplicate album identifier is refused`() {
        val payload = payload(generation = 1L, count = 2) {
            album(seed = 1, name = "First", createdAt = 1L, members = emptyList())
            album(seed = 1, name = "Second", createdAt = 2L, members = emptyList())
        }

        assertNull("two albums with one identity is a record that disagrees with itself", VaultOrganizationCodec.decodePayload(payload, 1L))
    }

    @Test
    fun `a duplicate membership is refused`() {
        val payload = payload(generation = 1L, count = 1) {
            album(seed = 1, name = "One", createdAt = 1L, members = listOf(7, 7))
        }

        assertNull(VaultOrganizationCodec.decodePayload(payload, 1L))
    }

    @Test
    fun `a name that is not valid UTF-8 is refused rather than replaced`() {
        val payload = payload(generation = 1L, count = 1) {
            album(seed = 1, nameBytes = byteArrayOf(0xC3.toByte(), 0x28), createdAt = 1L, members = emptyList())
        }

        assertNull(VaultOrganizationCodec.decodePayload(payload, 1L))
    }

    @Test
    fun `a name with no characters is refused`() {
        val payload = payload(generation = 1L, count = 1) {
            album(seed = 1, nameBytes = ByteArray(0), createdAt = 1L, members = emptyList())
        }

        assertNull(VaultOrganizationCodec.decodePayload(payload, 1L))
    }

    @Test
    fun `a name longer than the format allows is refused before it is read`() {
        val payload = payload(generation = 1L, count = 1) {
            album(
                seed = 1,
                nameBytes = ByteArray(VaultOrganizationLimits.MAXIMUM_NAME_BYTES + 1) { 'a'.code.toByte() },
                createdAt = 1L,
                members = emptyList(),
            )
        }

        assertNull(VaultOrganizationCodec.decodePayload(payload, 1L))
    }

    @Test
    fun `a name full of control characters is refused`() {
        val payload = payload(generation = 1L, count = 1) {
            album(seed = 1, name = "Trip\u0000Name", createdAt = 1L, members = emptyList())
        }

        assertNull(VaultOrganizationCodec.decodePayload(payload, 1L))
    }

    @Test
    fun `an album created before the epoch is refused`() {
        val payload = payload(generation = 1L, count = 1) {
            album(seed = 1, name = "One", createdAt = -1L, members = emptyList())
        }

        assertNull(VaultOrganizationCodec.decodePayload(payload, 1L))
    }

    @Test
    fun `a member count that runs past the end of the record is refused`() {
        val payload = payload(generation = 1L, count = 1) {
            albumHeader(seed = 1, name = "One", createdAt = 1L)
            short(5)
            id(7)
            id(8)
        }

        assertNull(VaultOrganizationCodec.decodePayload(payload, 1L))
    }

    @Test
    fun `a member count beyond the per-album limit is refused`() {
        val payload = payload(generation = 1L, count = 1) {
            albumHeader(seed = 1, name = "One", createdAt = 1L)
            short(VaultOrganizationLimits.MAXIMUM_MEMBERS_PER_ALBUM + 1)
            repeat(VaultOrganizationLimits.MAXIMUM_MEMBERS_PER_ALBUM + 1) { index -> id(index + 1) }
        }

        assertNull(VaultOrganizationCodec.decodePayload(payload, 1L))
    }

    @Test
    fun `a record naming more memberships in total than the limit is refused`() {
        val perAlbum = VaultOrganizationLimits.MAXIMUM_MEMBERS_PER_ALBUM
        val albums = VaultOrganizationLimits.MAXIMUM_TOTAL_MEMBERSHIPS / perAlbum + 1
        val payload = payload(generation = 1L, count = albums) {
            repeat(albums) { index ->
                album(seed = index + 1, name = "Album $index", createdAt = 1L, members = (1..perAlbum).toList())
            }
        }

        assertNull(VaultOrganizationCodec.decodePayload(payload, 1L))
    }

    @Test
    fun `a truncated record is refused at every length`() {
        val payload = VaultOrganizationCodec.encodePayload(generation = 1L, albums = listOf(holidays, recipes))!!

        (0 until payload.size).forEach { length ->
            assertNull(
                "a record cut short at $length bytes must not be read as one",
                VaultOrganizationCodec.decodePayload(payload.copyOf(length), expectedGeneration = 1L),
            )
        }
    }

    // ------------------------------------------------------------------ the writing side refuses too

    @Test
    fun `the writer refuses more memberships than the limit rather than writing a record it would not read`() {
        val perAlbum = VaultOrganizationLimits.MAXIMUM_MEMBERS_PER_ALBUM
        val albums = VaultOrganizationLimits.MAXIMUM_TOTAL_MEMBERSHIPS / perAlbum + 1
        val memberships = (1..perAlbum).map { index -> testItemId(index) }
        val many = (0 until albums).map { index ->
            testAlbum(seed = index + 1, name = "Album $index", itemIds = memberships)
        }

        assertNull(
            "the writer must never produce a record its own reader would refuse",
            VaultOrganizationCodec.encodePayload(generation = 1L, albums = many),
        )
    }

    @Test
    fun `the writer refuses a generation that is not one`() {
        assertNull(VaultOrganizationCodec.encodePayload(generation = 0L, albums = listOf(holidays)))
    }

    @Test
    fun `the writer refuses two albums with one identity`() {
        val first = testAlbum(seed = 1, name = "One")
        val second = testAlbum(seed = 1, name = "Two")

        assertNull(VaultOrganizationCodec.encodePayload(generation = 1L, albums = listOf(first, second)))
    }

    @Test
    fun `a name with a combining mark survives the round trip unchanged`() {
        val decomposed = testAlbum(seed = 4, name = "cafe\u0301")

        val payload = VaultOrganizationCodec.encodePayload(generation = 1L, albums = listOf(decomposed))
        val decoded = VaultOrganizationCodec.decodePayload(payload!!, expectedGeneration = 1L)

        assertEquals(
            "stored as written: normalising a name would rewrite what a person typed",
            decomposed.name,
            decoded!!.albums.single().name,
        )
    }

    @Test
    fun `a name at the character limit round-trips`() {
        val long = testAlbum(seed = 5, name = "x".repeat(VaultAlbumNames.MAXIMUM_LENGTH))

        val payload = VaultOrganizationCodec.encodePayload(generation = 1L, albums = listOf(long))

        assertEquals(
            listOf(long),
            VaultOrganizationCodec.decodePayload(payload!!, expectedGeneration = 1L)!!.albums,
        )
    }

    @Test
    fun `the identifier of an album is the same sixteen bytes every time it is written`() {
        val album = testAlbum(seed = 1, name = "One")
        val payload = VaultOrganizationCodec.encodePayload(generation = 1L, albums = listOf(album))!!

        val writtenIdBytes = payload.copyOfRange(VaultOrganizationCodec.PAYLOAD_HEADER_LENGTH, VaultOrganizationCodec.PAYLOAD_HEADER_LENGTH + 16)

        assertEquals(
            testAlbumId(1).value.chunked(2).map { pair -> pair.toInt(16) },
            writtenIdBytes.map { byte -> byte.toInt() and 0xFF },
        )
    }

    // ------------------------------------------------------------------ crafting bytes

    private fun payload(generation: Long, count: Int, body: PayloadWrite.() -> Unit): ByteArray =
        PayloadWrite()
            .ascii("NVAO")
            .byte(VaultOrganizationCodec.VERSION)
            .byte(0)
            .long(generation)
            .short(count)
            .apply(body)
            .toByteArray()

    /** Writes one album with valid UTF-8 text for its name. */
    private fun PayloadWrite.album(seed: Int, name: String, createdAt: Long, members: List<Int>) =
        album(seed = seed, nameBytes = name.toByteArray(Charsets.UTF_8), createdAt = createdAt, members = members)

    /** Writes one album, taking the name's bytes as given so damaged text can be written too. */
    private fun PayloadWrite.album(seed: Int, nameBytes: ByteArray, createdAt: Long, members: List<Int>) {
        albumHeader(seed = seed, nameBytes = nameBytes, createdAt = createdAt)
        short(members.size)
        members.forEach { memberSeed -> id(memberSeed) }
    }

    private fun PayloadWrite.albumHeader(seed: Int, name: String, createdAt: Long) =
        albumHeader(seed = seed, nameBytes = name.toByteArray(Charsets.UTF_8), createdAt = createdAt)

    private fun PayloadWrite.albumHeader(seed: Int, nameBytes: ByteArray, createdAt: Long) {
        id(seed)
        short(nameBytes.size)
        bytes(nameBytes)
        long(createdAt)
    }

    /** A tiny big-endian writer, so a malformed record can be built field by field. */
    private class PayloadWrite {

        private val bytes = ArrayList<Byte>()

        fun ascii(text: String): PayloadWrite = apply { text.forEach { character -> bytes += character.code.toByte() } }

        fun byte(value: Int): PayloadWrite = apply { bytes += value.toByte() }

        fun short(value: Int): PayloadWrite = apply {
            bytes += (value ushr 8).toByte()
            bytes += value.toByte()
        }

        fun long(value: Long): PayloadWrite = apply {
            for (shift in 56 downTo 0 step 8) bytes += (value ushr shift).toByte()
        }

        fun bytes(values: ByteArray): PayloadWrite = apply { values.forEach { value -> bytes += value } }

        /** An item or album identifier, written as the sixteen bytes it stands for. */
        fun id(seed: Int): PayloadWrite = apply {
            val hex = VaultItemId(String.format("%032x", seed.toLong() and 0xFFFFFFFFL)).value
            bytes(hex.chunked(2).map { pair -> pair.toInt(16).toByte() }.toByteArray())
        }

        fun toByteArray(): ByteArray = bytes.toByteArray()
    }
}
