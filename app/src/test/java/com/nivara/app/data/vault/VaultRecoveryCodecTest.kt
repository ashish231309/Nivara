package com.nivara.app.data.vault

import com.nivara.app.domain.vault.VaultIdentity
import com.nivara.app.testing.randomBytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for the recovery record codec.
 *
 * The record is what a fresh installation reads before it holds any key, so the tests pin the two
 * promises that follow from that: the clear structure is parseable without a secret, and anything
 * short of exactly what this build wrote is refused rather than interpreted.
 */
class VaultRecoveryCodecTest {

    private val identity = VaultIdentity("00112233445566778899aabbccddeeff")
    private val proof = randomBytes(VaultRecoveryCodec.PROOF_SIZE)
    private val envelope = randomBytes(64)

    private fun payload(generation: Long = 1L): ByteArray = VaultRecoveryCodec.encodePayload(
        generation = generation,
        identity = identity,
        proof = proof,
        envelope = envelope,
    )

    private fun record(generation: Long = 1L): ByteArray =
        VaultRecoveryCodec.encodeRecord(generation = generation, payload = payload(generation))

    // ------------------------------------------------------------------ round trips

    @Test
    fun `a record round-trips through the codec`() {
        val bytes = record(generation = 7L)

        val header = VaultRecoveryCodec.readHeader(bytes)
        assertTrue(header is VaultRecoveryCodec.HeaderRead.Present)
        assertEquals(7L, (header as VaultRecoveryCodec.HeaderRead.Present).header.generation)
        assertEquals(VaultRecoveryCodec.VERSION, header.header.version)

        val decoded = VaultRecoveryCodec.decodePayload(
            bytes = VaultRecoveryCodec.payloadOf(bytes),
            expectedGeneration = 7L,
        )
        assertEquals(identity, decoded?.identity)
        assertArrayEquals(proof, decoded?.proof)
        assertArrayEquals(envelope, decoded?.envelope)
        assertEquals(7L, decoded?.generation)
    }

    @Test
    fun `the record's magic and the payload's magic are distinct`() {
        val bytes = record()

        assertTrue(String(bytes.copyOfRange(0, 4), Charsets.US_ASCII) == "NVRC")
        val payloadBytes = VaultRecoveryCodec.payloadOf(bytes)
        assertTrue(String(payloadBytes.copyOfRange(0, 4), Charsets.US_ASCII) == "NVRP")

        // A payload is not a record: the record header reader refuses it.
        assertTrue(VaultRecoveryCodec.readHeader(payloadBytes) is VaultRecoveryCodec.HeaderRead.NotARecoveryRecord)
    }

    @Test
    fun `a vault record is not a recovery record`() {
        val vaultRecord = VaultRecordCodec.encodeRecord(
            generation = 1L,
            envelope = randomBytes(48),
        )

        assertTrue(
            VaultRecoveryCodec.readHeader(vaultRecord) is VaultRecoveryCodec.HeaderRead.NotARecoveryRecord,
        )
    }

    @Test
    fun `payload equality is by content`() {
        val first = VaultRecoveryCodec.decodePayload(payload(3L), 3L)
        val second = VaultRecoveryCodec.decodePayload(payload(3L), 3L)
        val differentGeneration = VaultRecoveryCodec.decodePayload(payload(4L), 4L)

        assertEquals(first, second)
        assertEquals(first?.hashCode(), second?.hashCode())
        assertNotEquals(first, differentGeneration)
    }

    // ------------------------------------------------------------------ the clear header

    @Test
    fun `a header that stops before it is complete is unreadable, not absent`() {
        val bytes = record().copyOfRange(0, 6)

        val read = VaultRecoveryCodec.readHeader(bytes)

        assertTrue(read is VaultRecoveryCodec.HeaderRead.Unreadable)
    }

    @Test
    fun `a foreign version is unreadable and carries its version`() {
        val bytes = record()
        bytes[4] = 2

        val read = VaultRecoveryCodec.readHeader(bytes)

        assertTrue(read is VaultRecoveryCodec.HeaderRead.Unreadable)
        assertEquals(2, (read as VaultRecoveryCodec.HeaderRead.Unreadable).fileVersion)
    }

    @Test
    fun `a set reserved byte is unreadable`() {
        val bytes = record()
        bytes[5] = 1

        assertTrue(VaultRecoveryCodec.readHeader(bytes) is VaultRecoveryCodec.HeaderRead.Unreadable)
    }

    @Test
    fun `a generation below the first is unreadable`() {
        val bytes = record(generation = 1L)
        // Zero the generation bytes in the clear header.
        for (index in 6 until 14) bytes[index] = 0

        assertTrue(VaultRecoveryCodec.readHeader(bytes) is VaultRecoveryCodec.HeaderRead.Unreadable)
    }

    @Test
    fun `foreign bytes are not a recovery record`() {
        assertTrue(
            VaultRecoveryCodec.readHeader("hello".toByteArray()) is
                VaultRecoveryCodec.HeaderRead.NotARecoveryRecord,
        )
        assertTrue(
            VaultRecoveryCodec.readHeader(ByteArray(0)) is
                VaultRecoveryCodec.HeaderRead.NotARecoveryRecord,
        )
    }

    // ------------------------------------------------------------------ the payload

    @Test
    fun `a payload that disagrees with its header generation is refused`() {
        val bytes = record(generation = 5L)

        val decoded = VaultRecoveryCodec.decodePayload(
            bytes = VaultRecoveryCodec.payloadOf(bytes),
            expectedGeneration = 6L,
        )

        assertNull("an edited header must not be promoted", decoded)
    }

    @Test
    fun `a payload with a foreign magic is refused`() {
        val bytes = payload()
        bytes[0] = 'X'.code.toByte()

        assertNull(VaultRecoveryCodec.decodePayload(bytes, 1L))
    }

    @Test
    fun `a payload with a foreign version is refused`() {
        val bytes = payload()
        bytes[4] = 2

        assertNull(VaultRecoveryCodec.decodePayload(bytes, 1L))
    }

    @Test
    fun `a payload with a set reserved byte is refused`() {
        val bytes = payload()
        bytes[5] = 1

        assertNull(VaultRecoveryCodec.decodePayload(bytes, 1L))
    }

    @Test
    fun `a changed identity changes the identity the payload names`() {
        val bytes = payload()
        bytes[7] = (bytes[7].toInt() xor 0x01).toByte()

        val decoded = VaultRecoveryCodec.decodePayload(bytes, 1L)

        assertNotEquals("the identity is read from the payload, not assumed", identity, decoded?.identity)
    }

    @Test
    fun `a declared envelope length of zero is refused`() {
        val bytes = payload()
        val lengthOffset = VaultRecoveryCodec.PAYLOAD_HEADER_LENGTH - 2
        bytes[lengthOffset] = 0
        bytes[lengthOffset + 1] = 0

        assertNull(VaultRecoveryCodec.decodePayload(bytes, 1L))
    }

    @Test
    fun `a declared envelope length above the bound is refused`() {
        val bytes = payload()
        val lengthOffset = VaultRecoveryCodec.PAYLOAD_HEADER_LENGTH - 2
        val oversized = VaultRecoveryCodec.MAXIMUM_ENVELOPE_LENGTH + 1
        bytes[lengthOffset] = (oversized ushr 8).toByte()
        bytes[lengthOffset + 1] = oversized.toByte()

        assertNull(VaultRecoveryCodec.decodePayload(bytes, 1L))
    }

    @Test
    fun `trailing bytes after the envelope are refused`() {
        val bytes = record()

        val decoded = VaultRecoveryCodec.decodePayload(
            bytes = VaultRecoveryCodec.payloadOf(bytes) + byteArrayOf(0),
            expectedGeneration = 1L,
        )

        assertNull("nothing may follow the envelope", decoded)
    }

    @Test
    fun `a truncated payload is refused`() {
        val bytes = payload()

        assertNull(VaultRecoveryCodec.decodePayload(bytes.copyOfRange(0, bytes.size - 1), 1L))
        assertNull(VaultRecoveryCodec.decodePayload(ByteArray(0), 1L))
    }

    @Test
    fun `the largest allowed envelope is accepted`() {
        val big = randomBytes(VaultRecoveryCodec.MAXIMUM_ENVELOPE_LENGTH)
        val bytes = VaultRecoveryCodec.encodePayload(
            generation = 1L,
            identity = identity,
            proof = proof,
            envelope = big,
        )

        val decoded = VaultRecoveryCodec.decodePayload(bytes, 1L)

        assertArrayEquals(big, decoded?.envelope)
        assertTrue(
            "the record stays inside the read bound",
            VaultRecoveryCodec.HEADER_LENGTH + bytes.size <= VaultRecoveryCodec.MAXIMUM_RECORD_LENGTH,
        )
    }

    // ------------------------------------------------------------------ writer discipline

    @Test
    fun `the writer refuses a proof of the wrong size`() {
        val result = runCatching {
            VaultRecoveryCodec.encodePayload(
                generation = 1L,
                identity = identity,
                proof = randomBytes(VaultRecoveryCodec.PROOF_SIZE - 1),
                envelope = envelope,
            )
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun `the writer refuses an oversized envelope`() {
        val result = runCatching {
            VaultRecoveryCodec.encodePayload(
                generation = 1L,
                identity = identity,
                proof = proof,
                envelope = randomBytes(VaultRecoveryCodec.MAXIMUM_ENVELOPE_LENGTH + 1),
            )
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun `the writer refuses an empty envelope`() {
        val result = runCatching {
            VaultRecoveryCodec.encodePayload(
                generation = 1L,
                identity = identity,
                proof = proof,
                envelope = ByteArray(0),
            )
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun `the writer refuses a malformed identity`() {
        val result = runCatching {
            VaultRecoveryCodec.encodePayload(
                generation = 1L,
                identity = VaultIdentity("zz"),
                proof = proof,
                envelope = envelope,
            )
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun `the writer refuses a generation below the first`() {
        val result = runCatching { record(generation = 0L) }

        assertTrue(result.isFailure)
    }

    @Test
    fun `a changed proof changes the payload`() {
        val first = payload()
        val otherProof = randomBytes(VaultRecoveryCodec.PROOF_SIZE)
        val second = VaultRecoveryCodec.encodePayload(
            generation = 1L,
            identity = identity,
            proof = otherProof,
            envelope = envelope,
        )

        assertFalse(first.contentEquals(second))
    }
}
