package com.nivara.app.data.vault

import com.nivara.app.domain.security.SecureRandomGenerator
import com.nivara.app.domain.vault.VaultIdentity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for the vault metadata record's shape.
 *
 * The codec is where "is this a Nivara vault?" is answered, so the interesting cases are the ones
 * where the answer must be *no* without becoming "there is no vault": a foreign file, a truncated
 * record, an unknown version, a payload whose declared length does not match its bytes. Each of those
 * is checked here, together with the double generation check that keeps an edited header from
 * promoting an old record.
 */
class VaultRecordCodecTest {

    private val random = SecureRandomGenerator()
    private val identity = VaultIdentity.create(random)
    private val wrappedKey = random.nextByteArray(48)

    private fun envelope(size: Int = 40): ByteArray = random.nextByteArray(size)

    // ------------------------------------------------------------------ the payload

    @Test
    fun `a payload round-trips exactly`() {
        val encoded = VaultRecordCodec.encodePayload(
            generation = 5L,
            identity = identity,
            wrappedKey = wrappedKey,
        )

        val decoded = VaultRecordCodec.decodePayload(encoded)

        assertEquals(VaultRecordCodec.PAYLOAD_HEADER_LENGTH + wrappedKey.size, encoded.size)
        assertEquals(5L, decoded?.generation)
        assertEquals(VaultRecordCodec.KEY_SCHEME_WRAPPED_CONTENT_KEY, decoded?.scheme)
        assertEquals(identity, decoded?.identity)
        assertArrayEquals(wrappedKey, decoded?.wrappedKey)
    }

    @Test
    fun `a payload with a foreign magic is not a payload`() {
        val encoded = VaultRecordCodec.encodePayload(1L, identity, wrappedKey)
        encoded[0] = 'X'.code.toByte()

        assertNull(VaultRecordCodec.decodePayload(encoded))
    }

    @Test
    fun `a payload with an unknown version is refused, not reinterpreted`() {
        val encoded = VaultRecordCodec.encodePayload(1L, identity, wrappedKey)
        encoded[4] = (VaultRecordCodec.VERSION + 1).toByte()

        assertNull(VaultRecordCodec.decodePayload(encoded))
    }

    @Test
    fun `a payload with a non-zero reserved byte is refused`() {
        val encoded = VaultRecordCodec.encodePayload(1L, identity, wrappedKey)
        encoded[5] = 1

        assertNull(VaultRecordCodec.decodePayload(encoded))
    }

    @Test
    fun `a payload with an unknown key scheme is refused`() {
        val encoded = VaultRecordCodec.encodePayload(1L, identity, wrappedKey)
        encoded[6] = 9

        assertNull(VaultRecordCodec.decodePayload(encoded))
    }

    @Test
    fun `a payload that stops before its header is not a payload`() {
        assertNull(VaultRecordCodec.decodePayload(ByteArray(0)))
        assertNull(VaultRecordCodec.decodePayload(ByteArray(VaultRecordCodec.PAYLOAD_HEADER_LENGTH - 1)))
    }

    @Test
    fun `a payload whose declared length does not match its bytes is refused`() {
        val encoded = VaultRecordCodec.encodePayload(1L, identity, wrappedKey)
        val lengthOffset = VaultRecordCodec.PAYLOAD_HEADER_LENGTH - VaultRecordCodec.WRAPPED_KEY_LENGTH_SIZE
        encoded[lengthOffset] = 0
        encoded[lengthOffset + 1] = (wrappedKey.size + 1).toByte()

        assertNull("a length that disagrees with the bytes is not a shorter payload", VaultRecordCodec.decodePayload(encoded))
    }

    @Test
    fun `trailing bytes after the wrapped key are refused`() {
        val encoded = VaultRecordCodec.encodePayload(1L, identity, wrappedKey)

        assertNull(VaultRecordCodec.decodePayload(encoded + byteArrayOf(0)))
    }

    @Test
    fun `a payload with a generation of zero is refused`() {
        val encoded = VaultRecordCodec.encodePayload(1L, identity, wrappedKey)
        val generationOffset = 7
        repeat(VaultRecordCodec.GENERATION_SIZE) { index -> encoded[generationOffset + index] = 0 }

        assertNull(VaultRecordCodec.decodePayload(encoded))
    }

    @Test
    fun `an oversized wrapped key is refused at both ends`() {
        val oversized = random.nextByteArray(VaultRecordCodec.MAXIMUM_WRAPPED_KEY_LENGTH + 1)

        val refused = runCatching { VaultRecordCodec.encodePayload(1L, identity, oversized) }

        assertTrue("the codec must not write an absurd key length", refused.isFailure)

        // And a length field claiming more than the bytes can hold is refused on the way in.
        val encoded = VaultRecordCodec.encodePayload(1L, identity, wrappedKey)
        val lengthOffset = VaultRecordCodec.PAYLOAD_HEADER_LENGTH - VaultRecordCodec.WRAPPED_KEY_LENGTH_SIZE
        encoded[lengthOffset] = (VaultRecordCodec.MAXIMUM_WRAPPED_KEY_LENGTH + 1 shr 8).toByte()
        encoded[lengthOffset + 1] = (VaultRecordCodec.MAXIMUM_WRAPPED_KEY_LENGTH + 1).toByte()
        assertNull(VaultRecordCodec.decodePayload(encoded))
    }

    @Test
    fun `two payloads carrying the same bytes are equal`() {
        val first = VaultRecordCodec.decodePayload(VaultRecordCodec.encodePayload(2L, identity, wrappedKey))
        val second = VaultRecordCodec.decodePayload(VaultRecordCodec.encodePayload(2L, identity, wrappedKey))

        assertEquals("equality compares the wrapped key's contents", first, second)
        assertEquals(first?.hashCode(), second?.hashCode())
    }

    @Test
    fun `payloads that differ are not equal`() {
        val first = VaultRecordCodec.decodePayload(VaultRecordCodec.encodePayload(2L, identity, wrappedKey))
        val other = VaultRecordCodec.decodePayload(
            VaultRecordCodec.encodePayload(3L, identity, wrappedKey),
        )

        assertNotEquals(first, other)
    }

    // ------------------------------------------------------------------ the record

    @Test
    fun `a record round-trips its header and envelope`() {
        val envelope = envelope()

        val record = VaultRecordCodec.encodeRecord(generation = 9L, envelope = envelope)

        assertEquals(VaultRecordCodec.HEADER_LENGTH + envelope.size, record.size)
        assertArrayEquals(envelope, VaultRecordCodec.envelopeOf(record))
        val present = VaultRecordCodec.readHeader(record) as? VaultRecordCodec.HeaderRead.Present
        assertTrue("a record with a readable header", present != null)
        assertEquals(9L, present?.header?.generation)
        assertEquals(VaultRecordCodec.VERSION, present?.header?.version)
    }

    @Test
    fun `a record without Nivara's marker is not a vault record`() {
        val record = VaultRecordCodec.encodeRecord(1L, envelope())
        record[0] = 'X'.code.toByte()

        assertEquals(
            VaultRecordCodec.HeaderRead.NotAVaultRecord,
            VaultRecordCodec.readHeader(record),
        )
    }

    @Test
    fun `a file that is too short to be a record is not one`() {
        assertEquals(
            VaultRecordCodec.HeaderRead.NotAVaultRecord,
            VaultRecordCodec.readHeader(ByteArray(0)),
        )
        assertEquals(
            VaultRecordCodec.HeaderRead.NotAVaultRecord,
            VaultRecordCodec.readHeader("NV".toByteArray()),
        )
    }

    @Test
    fun `a truncated record keeps the marker and is reported as unreadable`() {
        val record = VaultRecordCodec.encodeRecord(4L, envelope())

        val truncated = record.copyOf(VaultRecordCodec.HEADER_LENGTH - 1)

        val unreadable =
            VaultRecordCodec.readHeader(truncated) as? VaultRecordCodec.HeaderRead.Unreadable
        assertTrue("a damaged Nivara file must not read as 'no vault'", unreadable != null)
        assertEquals(VaultRecordCodec.VERSION, unreadable?.fileVersion)
        assertFalse(VaultRecordCodec.isCompleteRecord(truncated))
    }

    @Test
    fun `a record written by a newer format reports the version it read`() {
        val record = VaultRecordCodec.encodeRecord(1L, envelope())
        record[4] = (VaultRecordCodec.VERSION + 1).toByte()

        val header = VaultRecordCodec.readHeader(record)

        assertEquals(
            VaultRecordCodec.HeaderRead.Unreadable(fileVersion = VaultRecordCodec.VERSION + 1),
            header,
        )
    }

    @Test
    fun `a record with a non-zero reserved byte is unreadable`() {
        val record = VaultRecordCodec.encodeRecord(1L, envelope())
        record[5] = 1

        val header = VaultRecordCodec.readHeader(record)

        assertEquals(
            VaultRecordCodec.HeaderRead.Unreadable(fileVersion = VaultRecordCodec.VERSION),
            header,
        )
    }

    @Test
    fun `a record with a generation below the first is unreadable`() {
        val record = VaultRecordCodec.encodeRecord(1L, envelope())
        repeat(VaultRecordCodec.GENERATION_SIZE) { index -> record[6 + index] = 0 }

        val header = VaultRecordCodec.readHeader(record)

        assertTrue(header is VaultRecordCodec.HeaderRead.Unreadable)
    }

    @Test
    fun `a marker alone carries no version to report`() {
        val header = VaultRecordCodec.readHeader("NVVM".toByteArray())

        assertEquals(VaultRecordCodec.HeaderRead.Unreadable(fileVersion = null), header)
    }

    @Test
    fun `an envelope must not be empty`() {
        val refused = runCatching { VaultRecordCodec.encodeRecord(1L, ByteArray(0)) }

        assertTrue("a record without an envelope is not a record", refused.isFailure)
    }

    @Test
    fun `a generation below the first is not written`() {
        val refused = runCatching { VaultRecordCodec.encodeRecord(0L, envelope()) }

        assertTrue("generations start at ${VaultRecordCodec.FIRST_GENERATION}", refused.isFailure)
    }
}
