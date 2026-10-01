package com.nivara.app.data.vault

import com.nivara.app.domain.vault.VaultItemId
import com.nivara.app.domain.vault.VaultTrashEntry
import com.nivara.app.domain.vault.VaultTrashLimits
import com.nivara.app.testing.testItemId
import com.nivara.app.testing.testTrashEntry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for the trash record's format.
 *
 * The codec's job is to be boring and strict: what Nivara writes is exactly what Nivara reads, one set
 * of entries has one encoding, and every way a record can be malformed — a duplicate, an invalid
 * identifier, a moment before the epoch, an unknown version, a set reserved byte, a truncated payload,
 * trailing bytes, an impossible count, a count larger than the format allows, a payload that disagrees
 * with its header — is refused rather than interpreted. A record Nivara cannot read must never come
 * back as an empty trash, so the decoder returning `null` is a fact the suite checks in every one of
 * those directions.
 */
class VaultTrashCodecTest {

    private fun entries(count: Int, at: Long = 1_700_000_000_000L): List<VaultTrashEntry> =
        (1..count).map { seed -> testTrashEntry(seed = seed, trashedAtEpochMillis = at + seed) }

    private fun payload(generation: Long = 1L, entries: List<VaultTrashEntry>): ByteArray =
        VaultTrashCodec.encodePayload(generation = generation, entries = entries)
            ?: error("the test's payload could not be encoded")

    private fun decoded(bytes: ByteArray, generation: Long = 1L) =
        VaultTrashCodec.decodePayload(bytes = bytes, expectedGeneration = generation)

    // ------------------------------------------------------------------ the header

    @Test
    fun a_record_header_names_itself_and_carries_its_generation() {
        val record = VaultTrashCodec.encodeRecord(generation = 3L, envelope = byteArrayOf(9, 8, 7))
        val header = VaultTrashCodec.readHeader(record)
        assertTrue(header is VaultTrashCodec.HeaderRead.Present)
        assertEquals(3L, (header as VaultTrashCodec.HeaderRead.Present).generation)
    }

    @Test
    fun bytes_that_do_not_begin_with_the_marker_are_not_a_trash_record() {
        assertTrue(VaultTrashCodec.readHeader(byteArrayOf(1, 2, 3, 4, 5)) is VaultTrashCodec.HeaderRead.NotATrashRecord)
        assertTrue(VaultTrashCodec.readHeader(ByteArray(0)) is VaultTrashCodec.HeaderRead.NotATrashRecord)
        assertTrue(
            VaultTrashCodec.readHeader("NVIN".toByteArray() + ByteArray(10))
                is VaultTrashCodec.HeaderRead.NotATrashRecord,
        )
    }

    @Test
    fun a_header_that_stops_after_the_marker_is_unreadable() {
        val header = VaultTrashCodec.readHeader(VaultTrashCodec.MAGIC + byteArrayOf(1))
        assertTrue(header is VaultTrashCodec.HeaderRead.Unreadable)
    }

    @Test
    fun a_version_this_build_does_not_know_is_reported_with_its_version() {
        val record = VaultTrashCodec.MAGIC + byteArrayOf(2, 0) + ByteArray(8) { 1 } + ByteArray(4)
        val header = VaultTrashCodec.readHeader(record)
        assertEquals(2, (header as VaultTrashCodec.HeaderRead.Unreadable).fileVersion)
    }

    @Test
    fun a_set_reserved_byte_makes_the_header_unreadable() {
        val record = VaultTrashCodec.MAGIC + byteArrayOf(1, 1) + ByteArray(8) { 1 } + ByteArray(4)
        assertTrue(VaultTrashCodec.readHeader(record) is VaultTrashCodec.HeaderRead.Unreadable)
    }

    @Test
    fun a_generation_below_one_is_not_a_record() {
        val record = VaultTrashCodec.MAGIC + byteArrayOf(1, 0) + ByteArray(8) + ByteArray(4)
        assertTrue(VaultTrashCodec.readHeader(record) is VaultTrashCodec.HeaderRead.Unreadable)
    }

    @Test
    fun the_envelope_is_the_record_without_its_clear_header() {
        val envelope = byteArrayOf(9, 8, 7)
        val record = VaultTrashCodec.encodeRecord(generation = 1L, envelope = envelope)
        assertArrayEquals(envelope, VaultTrashCodec.envelopeOf(record))
    }

    // ------------------------------------------------------------------ the payload

    @Test
    fun a_payload_round_trips_exactly() {
        val written = entries(count = 3)
        val read = decoded(payload(generation = 7L, entries = written), generation = 7L)
        assertNotNull(read)
        assertEquals(7L, read?.generation)
        assertEquals(written, read?.entries)
    }

    @Test
    fun an_empty_record_round_trips_as_an_empty_record_not_as_no_record() {
        val read = decoded(payload(entries = emptyList()))
        assertNotNull(read)
        assertEquals(emptyList<VaultTrashEntry>(), read?.entries)
    }

    @Test
    fun the_written_order_is_canonical() {
        val first = testTrashEntry(seed = 1)
        val second = testTrashEntry(seed = 2)
        val read = decoded(payload(entries = listOf(second, first)))
        assertEquals(listOf(first, second), read?.entries)
    }

    @Test
    fun a_duplicate_identifier_is_refused_by_the_writer() {
        val entry = testTrashEntry(seed = 1)
        assertNull(
            VaultTrashCodec.encodePayload(generation = 1L, entries = listOf(entry, entry)),
        )
    }

    @Test
    fun a_moment_before_the_epoch_is_refused_by_the_reader() {
        val bytes = payload(entries = listOf(testTrashEntry(seed = 1))).copyOf()
        // Flip the sign bit of the stored timestamp: the bytes stay well formed, the moment they say
        // is before the epoch, and the decoder must refuse rather than read it.
        bytes[VaultTrashCodec.PAYLOAD_HEADER_LENGTH + VaultTrashCodec.ID_SIZE] =
            (bytes[VaultTrashCodec.PAYLOAD_HEADER_LENGTH + VaultTrashCodec.ID_SIZE].toInt() or 0x80).toByte()
        assertNull(decoded(bytes))
    }

    @Test
    fun more_entries_than_the_record_may_carry_is_refused_by_the_writer() {
        val tooMany = (1..VaultTrashLimits.MAXIMUM_TRASHED_ITEMS + 1).map { seed -> testTrashEntry(seed = seed) }
        assertNull(VaultTrashCodec.encodePayload(generation = 1L, entries = tooMany))
    }

    @Test
    fun a_record_at_the_size_bound_is_encodable_and_stays_inside_the_bound() {
        val full = (1..VaultTrashLimits.MAXIMUM_TRASHED_ITEMS).map { seed -> testTrashEntry(seed = seed) }
        val encoded = VaultTrashCodec.encodePayload(generation = 1L, entries = full)
        assertNotNull(encoded)
        assertTrue(
            "an encoded record must fit the bound it promises to read",
            (encoded?.size ?: Int.MAX_VALUE) < VaultTrashLimits.MAXIMUM_RECORD_BYTES,
        )
    }

    @Test
    fun a_payload_that_disagrees_with_its_header_generation_is_refused() {
        assertNull(decoded(payload(generation = 1L, entries = emptyList()), generation = 2L))
    }

    @Test
    fun a_payload_whose_own_version_is_unknown_is_refused() {
        val bytes = payload(entries = emptyList()).copyOf()
        bytes[4] = 2
        assertNull(decoded(bytes))
    }

    @Test
    fun a_payload_with_a_set_reserved_byte_is_refused() {
        val bytes = payload(entries = emptyList()).copyOf()
        bytes[5] = 1
        assertNull(decoded(bytes))
    }

    @Test
    fun a_count_larger_than_the_payload_can_hold_is_refused_before_allocation() {
        val bytes = payload(entries = listOf(testTrashEntry(seed = 1))).copyOf()
        bytes[14] = 0
        bytes[15] = 9
        assertNull(decoded(bytes))
    }

    @Test
    fun a_count_larger_than_the_format_allows_is_refused() {
        val bytes = payload(entries = emptyList()).copyOf()
        bytes[14] = 0xFF.toByte()
        bytes[15] = 0xFF.toByte()
        assertNull(decoded(bytes))
    }

    @Test
    fun a_truncated_payload_is_refused() {
        val bytes = payload(entries = listOf(testTrashEntry(seed = 1)))
        assertNull(decoded(bytes.copyOfRange(0, bytes.size - 1)))
    }

    @Test
    fun trailing_bytes_are_refused_rather_than_ignored() {
        val bytes = payload(entries = listOf(testTrashEntry(seed = 1)))
        assertNull(decoded(bytes + byteArrayOf(0)))
    }

    @Test
    fun an_identifier_that_is_not_lower_case_hex_is_refused() {
        val bytes = payload(entries = listOf(testTrashEntry(seed = 1))).copyOf()
        // Sixteen bytes that are not a hex identifier at all: the reader must refuse the entry rather
        // than invent one.
        for (index in VaultTrashCodec.PAYLOAD_HEADER_LENGTH until VaultTrashCodec.PAYLOAD_HEADER_LENGTH + VaultTrashCodec.ID_SIZE) {
            bytes[index] = 0
        }
        assertNull(decoded(bytes))
    }

    @Test
    fun two_records_written_from_the_same_set_are_byte_identical() {
        val first = VaultTrashCodec.encodeRecord(
            generation = 1L,
            envelope = payload(entries = listOf(testTrashEntry(seed = 2), testTrashEntry(seed = 1))),
        )
        val second = VaultTrashCodec.encodeRecord(
            generation = 1L,
            envelope = payload(entries = listOf(testTrashEntry(seed = 1), testTrashEntry(seed = 2))),
        )
        assertArrayEquals(first, second)
    }

    @Test
    fun the_identifier_in_a_decoded_entry_is_the_identifier_that_was_written() {
        val written = testTrashEntry(seed = 42)
        val read = decoded(payload(entries = listOf(written)))?.entries?.single()
        assertEquals(testItemId(42), read?.itemId)
        assertEquals(VaultItemId(testItemId(42).value), read?.itemId)
    }
}
