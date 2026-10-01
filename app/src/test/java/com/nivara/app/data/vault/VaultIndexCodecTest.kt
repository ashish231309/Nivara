package com.nivara.app.data.vault

import com.nivara.app.domain.security.SecureRandomGenerator
import com.nivara.app.domain.vault.VaultContentDigest
import com.nivara.app.domain.vault.VaultItem
import com.nivara.app.domain.vault.VaultItemId
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for the vault's content index format.
 *
 * An index is the only thing that decides what a vault holds, so the format's strictness is not
 * decoration: every length is bounded, every string is validated, a repeated identifier invalidates
 * the whole record, trailing bytes are refused, and the generation inside the sealed payload must
 * agree with the generation in the clear header. What is checked here is that a record which is not
 * exactly the record this codec writes is never accepted as one.
 */
class VaultIndexCodecTest {

    private val random = SecureRandomGenerator()

    // ------------------------------------------------------------------ round trips

    @Test
    fun `an index with no items is a valid record, not a missing one`() {
        val payload = VaultIndexCodec.encodePayload(generation = 1, vaultGeneration = 1, items = emptyList())

        assertNotNull(payload)
        val decoded = VaultIndexCodec.decodePayload(payload!!, expectedGeneration = 1)
        assertEquals(emptyList<VaultItem>(), decoded?.items)
        assertEquals(1L, decoded?.generation)
    }

    @Test
    fun `one item survives the format with every fact it carries`() {
        val item = item(name = "holiday photo.jpg", mimeType = "image/jpeg", size = 4_194_304L, importedAt = 1_700_000_000_000L)

        val decoded = roundTrip(listOf(item))

        assertEquals(listOf(item), decoded)
    }

    @Test
    fun `an item without a declared type survives as one without a type`() {
        val item = item(name = "notes.txt", mimeType = null, size = 12L, importedAt = 5L)

        assertEquals(listOf(item), roundTrip(listOf(item)))
    }

    @Test
    fun `many items survive in the order they were written`() {
        val items = List(64) { index ->
            item(
                name = "file-$index.bin",
                mimeType = "application/octet-stream",
                size = index.toLong() * 1_000,
                importedAt = 1_000L + index,
            )
        }

        assertEquals(items, roundTrip(items))
    }

    @Test
    fun `the same items always encode to the same bytes`() {
        val items = listOf(item(name = "a.txt", mimeType = "text/plain", size = 1L, importedAt = 2L))

        val first = VaultIndexCodec.encodePayload(1, 1, items)
        val second = VaultIndexCodec.encodePayload(1, 1, items)

        assertArrayEquals(first, second)
    }

    @Test
    fun `a record is its header followed by the sealed envelope, and nothing else`() {
        val envelope = ByteArray(64) { 7 }
        val record = VaultIndexCodec.encodeRecord(generation = 9, envelope = envelope)

        assertEquals(VaultIndexCodec.HEADER_LENGTH + envelope.size, record.size)
        assertArrayEquals(envelope, VaultIndexCodec.envelopeOf(record))
        val header = VaultIndexCodec.readHeader(record)
        assertEquals(VaultIndexCodec.HeaderRead.Present(generation = 9), header)
    }

    // ------------------------------------------------------------------ what must be refused

    @Test
    fun `a record that is not a Nivara index is not an index`() {
        assertEquals(
            VaultIndexCodec.HeaderRead.NotAVaultIndex,
            VaultIndexCodec.readHeader("not an index at all".toByteArray()),
        )
        assertEquals(VaultIndexCodec.HeaderRead.NotAVaultIndex, VaultIndexCodec.readHeader(ByteArray(0)))
    }

    @Test
    fun `a Nivara file that is too short to be an index is damage, not absence`() {
        val header = VaultIndexCodec.readHeader(VaultIndexCodec.MAGIC + byteArrayOf(1, 0))

        assertTrue(header is VaultIndexCodec.HeaderRead.Unreadable)
    }

    @Test
    fun `a version this build does not know is reported with the version it found`() {
        val record = VaultIndexCodec.encodeRecord(generation = 1, envelope = ByteArray(8) { 1 })
        record[VaultIndexCodec.MAGIC.size] = 2

        assertEquals(
            VaultIndexCodec.HeaderRead.Unreadable(fileVersion = 2),
            VaultIndexCodec.readHeader(record),
        )
    }

    @Test
    fun `a set reserved byte invalidates the record`() {
        val record = VaultIndexCodec.encodeRecord(generation = 1, envelope = ByteArray(8) { 1 })
        record[VaultIndexCodec.MAGIC.size + 1] = 1

        assertTrue(VaultIndexCodec.readHeader(record) is VaultIndexCodec.HeaderRead.Unreadable)
    }

    @Test
    fun `a generation of zero is not a generation`() {
        val record = VaultIndexCodec.encodeRecord(generation = 1, envelope = ByteArray(8) { 1 })
        for (index in 0 until VaultIndexCodec.GENERATION_SIZE) {
            record[VaultIndexCodec.MAGIC.size + 2 + index] = 0
        }

        assertTrue(VaultIndexCodec.readHeader(record) is VaultIndexCodec.HeaderRead.Unreadable)
    }

    @Test
    fun `a payload for another generation than the header names is refused`() {
        val payload = VaultIndexCodec.encodePayload(generation = 4, vaultGeneration = 4, items = emptyList())

        assertNull(VaultIndexCodec.decodePayload(payload!!, expectedGeneration = 5))
        assertNotNull(VaultIndexCodec.decodePayload(payload, expectedGeneration = 4))
    }

    @Test
    fun `a truncated payload is refused`() {
        val payload = VaultIndexCodec.encodePayload(
            generation = 1,
            vaultGeneration = 1,
            items = listOf(item(name = "a.txt", mimeType = "text/plain", size = 1L, importedAt = 1L)),
        )!!

        assertNull(VaultIndexCodec.decodePayload(payload.copyOf(payload.size - 1), expectedGeneration = 1))
        assertNull(VaultIndexCodec.decodePayload(payload.copyOf(4), expectedGeneration = 1))
        assertNull(VaultIndexCodec.decodePayload(ByteArray(0), expectedGeneration = 1))
    }

    @Test
    fun `bytes after the last item are refused rather than ignored`() {
        val payload = VaultIndexCodec.encodePayload(generation = 1, vaultGeneration = 1, items = emptyList())!!

        assertNull(VaultIndexCodec.decodePayload(payload + byteArrayOf(0), expectedGeneration = 1))
    }

    @Test
    fun `the same identifier twice makes the whole record untrustworthy`() {
        val id = VaultItemId.create(random)
        val digest = VaultContentDigest.fromBytes(ByteArray(32) { 5 })!!
        // Two items built by hand with one identifier, because the codec refuses to build this.
        val item = VaultItem(id, "a.txt", null, 1L, 1L, 1, digest)
        val payload = payloadWith(items = listOf(item, item.copy(name = "b.txt")))

        assertNull(VaultIndexCodec.decodePayload(payload, expectedGeneration = 1))
    }

    @Test
    fun `a name a provider could not produce is refused by the payload parser`() {
        val id = VaultItemId.create(random)
        val digest = VaultContentDigest.fromBytes(ByteArray(32) { 5 })!!
        val traversal = VaultItem(id, "..", null, 1L, 1L, 1, digest)
        val long = VaultItem(VaultItemId.create(random), "a".repeat(300), null, 1L, 1L, 1, digest)

        assertNull(VaultIndexCodec.decodePayload(payloadWith(listOf(traversal)), expectedGeneration = 1))
        assertNull(VaultIndexCodec.decodePayload(payloadWith(listOf(long)), expectedGeneration = 1))
    }

    @Test
    fun `an item the format cannot hold is refused instead of half-written`() {
        val digest = VaultContentDigest.fromBytes(ByteArray(32) { 1 })!!
        val tooLongAName = VaultItem(
            VaultItemId.create(random),
            "a".repeat(1_000),
            null,
            1L,
            1L,
            1,
            digest,
        )
        val negativeSize = VaultItem(VaultItemId.create(random), "a.txt", null, -1L, 1L, 1, digest)
        val badDigest = VaultItem(
            VaultItemId.create(random),
            "a.txt",
            null,
            1L,
            1L,
            1,
            VaultContentDigest("not a digest"),
        )

        assertNull(VaultIndexCodec.encodePayload(1, 1, listOf(tooLongAName)))
        assertNull(VaultIndexCodec.encodePayload(1, 1, listOf(negativeSize)))
        assertNull(VaultIndexCodec.encodePayload(1, 1, listOf(badDigest)))
        assertNull(VaultIndexCodec.encodePayload(0, 1, emptyList()))
    }

    @Test
    fun `a payload that is not a Nivara payload is refused`() {
        val payload = VaultIndexCodec.encodePayload(1, 1, emptyList())!!

        val magicBroken = payload.copyOf().also { bytes -> bytes[0] = 0 }
        assertNull(VaultIndexCodec.decodePayload(magicBroken, expectedGeneration = 1))

        val versionBroken = payload.copyOf().also { bytes -> bytes[4] = 2 }
        assertNull(VaultIndexCodec.decodePayload(versionBroken, expectedGeneration = 1))

        val reservedSet = payload.copyOf().also { bytes -> bytes[5] = 1 }
        assertNull(VaultIndexCodec.decodePayload(reservedSet, expectedGeneration = 1))

        val countTooHigh = payload.copyOf().also { bytes ->
            bytes[22] = 0
            bytes[23] = 4
        }
        assertNull(VaultIndexCodec.decodePayload(countTooHigh, expectedGeneration = 1))
    }

    // ------------------------------------------------------------------ helpers

    private fun roundTrip(items: List<VaultItem>): List<VaultItem>? {
        val payload = VaultIndexCodec.encodePayload(
            generation = 7,
            vaultGeneration = 7,
            items = items,
        ) ?: return null
        return VaultIndexCodec.decodePayload(payload, expectedGeneration = 7)?.items
    }

    /**
     * A payload assembled by hand, so a test can build one the codec itself refuses to produce.
     *
     * This is the only way to check that the parser — not just the writer — is strict, and it is how
     * a record damaged after it was written is simulated.
     */
    private fun payloadWith(items: List<VaultItem>): ByteArray {
        val body = ByteArrayOutputStream()
        for (item in items) {
            body.write(item.id.toBytes())
            val name = item.name.toByteArray(Charsets.UTF_8)
            body.write(byteArrayOf((name.size ushr 8).toByte(), name.size.toByte()))
            body.write(name)
            val mime = item.mimeType?.toByteArray(Charsets.UTF_8)
            if (mime == null) {
                body.write(byteArrayOf(0, 0))
            } else {
                body.write(byteArrayOf((mime.size ushr 8).toByte(), mime.size.toByte()))
                body.write(mime)
            }
            repeat(8) { shift ->
                // One byte at a time, most significant first, written as the low byte of the shift.
                body.write((item.sizeBytes ushr ((7 - shift) * 8)).toInt())
            }
            repeat(8) { shift ->
                body.write((item.importedAtEpochMillis ushr ((7 - shift) * 8)).toInt())
            }
            body.write(item.contentFormatVersion)
            body.write(hexToBytes(item.contentDigest.value))
        }
        val itemsBytes = body.toByteArray()
        val payload = ByteArray(VaultIndexCodec.PAYLOAD_HEADER_LENGTH + itemsBytes.size)
        VaultIndexCodec.MAGIC.copyInto(payload, 0)
        payload[4] = VaultIndexCodec.VERSION.toByte()
        payload[5] = 0
        payload[22] = (items.size ushr 8).toByte()
        payload[23] = items.size.toByte()
        // generation 1, vault generation 1, most significant bytes first
        payload[13] = 1
        payload[21] = 1
        itemsBytes.copyInto(payload, VaultIndexCodec.PAYLOAD_HEADER_LENGTH)
        return payload
    }

    private fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { index ->
        ((hex[index * 2].digitToInt(16) shl 4) or hex[index * 2 + 1].digitToInt(16)).toByte()
    }

    private fun item(name: String, mimeType: String?, size: Long, importedAt: Long): VaultItem = VaultItem(
        id = VaultItemId.create(random),
        name = name,
        mimeType = mimeType,
        sizeBytes = size,
        importedAtEpochMillis = importedAt,
        contentFormatVersion = 1,
        contentDigest = VaultContentDigest.fromBytes(ByteArray(32) { (size % 251).toByte() })!!,
    )
}
