package com.nivara.app.data.vault

import com.nivara.app.domain.vault.VaultLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for the record of which folder holds the vault.
 *
 * The record is tiny, and the only interesting question is what happens when it is not what Nivara
 * wrote. Every one of those cases must decode to `null` — which the store turns into "the location
 * cannot be read" — rather than to a location that was never chosen.
 */
class VaultLocationCodecTest {

    private val reference = "content://com.android.externalstorage.documents/tree/primary%3ANivara"

    @Test
    fun `a location round-trips exactly`() {
        val location = VaultLocation(reference)

        val decoded = VaultLocationCodec.decode(VaultLocationCodec.encode(location))

        assertEquals(location, decoded)
        assertEquals(
            VaultLocationCodec.HEADER_LENGTH + reference.toByteArray(Charsets.UTF_8).size,
            VaultLocationCodec.encode(location).size,
        )
    }

    @Test
    fun `a record with a foreign magic is not a location`() {
        val bytes = VaultLocationCodec.encode(VaultLocation(reference))
        bytes[0] = 'X'.code.toByte()

        assertNull(VaultLocationCodec.decode(bytes))
    }

    @Test
    fun `a record from an unknown version is refused, not reinterpreted`() {
        val bytes = VaultLocationCodec.encode(VaultLocation(reference))
        bytes[4] = (VaultLocationCodec.VERSION + 1).toByte()

        assertNull(VaultLocationCodec.decode(bytes))
    }

    @Test
    fun `a record with a non-zero reserved byte is refused`() {
        val bytes = VaultLocationCodec.encode(VaultLocation(reference))
        bytes[5] = 1

        assertNull(VaultLocationCodec.decode(bytes))
    }

    @Test
    fun `a record that stops before its header is not a location`() {
        assertNull(VaultLocationCodec.decode(ByteArray(0)))
        assertNull(VaultLocationCodec.decode(ByteArray(VaultLocationCodec.HEADER_LENGTH - 1)))
    }

    @Test
    fun `a record whose declared length does not match its bytes is refused`() {
        val bytes = VaultLocationCodec.encode(VaultLocation(reference))
        bytes[7] = (bytes[7] + 1).toByte()

        assertNull(VaultLocationCodec.decode(bytes))
    }

    @Test
    fun `trailing bytes after the reference are refused`() {
        val bytes = VaultLocationCodec.encode(VaultLocation(reference))

        assertNull(VaultLocationCodec.decode(bytes + byteArrayOf(0)))
    }

    @Test
    fun `a blank reference is not a location`() {
        // Hand-built, because VaultLocation itself refuses a blank reference: the codec is what has to
        // refuse bytes that came from somewhere other than Nivara.
        val bytes = VaultLocationCodec.encode(VaultLocation("x"))
        bytes[VaultLocationCodec.HEADER_LENGTH] = ' '.code.toByte()

        assertNull("a blank reference names nothing", VaultLocationCodec.decode(bytes))
    }

    @Test
    fun `a reference longer than the bound is not written`() {
        val tooLong = "content://x/".padEnd(VaultLocation.MAXIMUM_REFERENCE_LENGTH + 1, 'a')

        val refused = runCatching { VaultLocationCodec.encode(VaultLocation(tooLong)) }

        assertTrue("the codec must not write an unbounded reference", refused.isFailure)
    }

    @Test
    fun `the largest reference the vault accepts still round-trips`() {
        val longest = "content://x/".padEnd(VaultLocation.MAXIMUM_REFERENCE_LENGTH, 'a')
        val location = VaultLocation(longest)

        val decoded = VaultLocationCodec.decode(VaultLocationCodec.encode(location))

        assertEquals(location, decoded)
    }
}
