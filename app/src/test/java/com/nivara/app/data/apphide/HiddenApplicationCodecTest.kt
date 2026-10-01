package com.nivara.app.data.apphide

import com.nivara.app.domain.apphide.HiddenApplication
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for the hidden-set file format.
 *
 * The format is the boundary where damage becomes visible, so the suite is mostly about rejection:
 * every malformed shape the reader could meet on a device is handed to the decoder and must come
 * back as `null` — never as an empty set, and never as a partially decoded one. The repository turns
 * that `null` into the unreadable state the screen and, later, the launcher fail closed on.
 *
 * The tests work on real bytes with the layout the codec documents, so a change to the layout that
 * is not accompanied by a change here fails rather than silently reinterpreting the file.
 */
class HiddenApplicationCodecTest {

    private val camera = HiddenApplication("com.example.camera")
    private val notes = HiddenApplication("com.example.notes")
    private val maps = HiddenApplication("com.example.maps")

    /** Offsets of the documented layout, so a test can damage exactly one field. */
    private val versionIndex = 4
    private val countIndex = 5
    private val firstEntryIndex = 9

    @Test
    fun `an empty set round-trips`() {
        val bytes = HiddenApplicationCodec.encode(emptyList())

        assertEquals(HiddenApplicationCodec.MINIMUM_BYTES, bytes.size)
        assertEquals(emptySet<HiddenApplication>(), HiddenApplicationCodec.decode(bytes))
    }

    @Test
    fun `one application round-trips`() {
        val bytes = HiddenApplicationCodec.encode(listOf(camera))

        assertEquals(setOf(camera), HiddenApplicationCodec.decode(bytes))
        assertEquals(
            HiddenApplicationCodec.MINIMUM_BYTES + 1 + camera.packageName.length,
            bytes.size,
        )
    }

    @Test
    fun `several applications round-trip`() {
        val bytes = HiddenApplicationCodec.encode(listOf(camera, notes, maps))

        assertEquals(setOf(camera, notes, maps), HiddenApplicationCodec.decode(bytes))
    }

    @Test
    fun `the encoding does not depend on the order the set was built in`() {
        val first = HiddenApplicationCodec.encode(listOf(notes, camera, maps))
        val second = HiddenApplicationCodec.encode(listOf(maps, camera, notes))

        assertArrayEquals(first, second)
    }

    @Test
    fun `a repeated application is stored once`() {
        val bytes = HiddenApplicationCodec.encode(listOf(camera, camera))

        assertEquals(setOf(camera), HiddenApplicationCodec.decode(bytes))
        assertEquals(HiddenApplicationCodec.encode(listOf(camera)).size, bytes.size)
    }

    @Test
    fun `the file holds the package names and the checksum, nothing else`() {
        val bytes = HiddenApplicationCodec.encode(listOf(camera))

        val names = String(bytes, firstEntryIndex, bytes.size - firstEntryIndex - 4, Charsets.UTF_8)
        assertEquals(camera.packageName, names.drop(1))
    }

    @Test
    fun `too few bytes are rejected`() {
        assertNull(HiddenApplicationCodec.decode(ByteArray(0)))
        assertNull(HiddenApplicationCodec.decode(ByteArray(HiddenApplicationCodec.MINIMUM_BYTES - 1)))
    }

    @Test
    fun `an unknown magic is rejected`() {
        val bytes = HiddenApplicationCodec.encode(listOf(camera))
        bytes[0] = 'X'.code.toByte()

        assertNull(HiddenApplicationCodec.decode(bytes))
    }

    @Test
    fun `an unknown version is rejected`() {
        val bytes = HiddenApplicationCodec.encode(listOf(camera))
        bytes[versionIndex] = (HiddenApplicationCodec.FORMAT_VERSION + 1).toByte()

        assertNull(HiddenApplicationCodec.decode(bytes))
    }

    @Test
    fun `a truncated file is rejected`() {
        val bytes = HiddenApplicationCodec.encode(listOf(camera, notes))

        assertNull(HiddenApplicationCodec.decode(bytes.copyOf(bytes.size - 1)))
    }

    @Test
    fun `trailing bytes are rejected`() {
        val bytes = HiddenApplicationCodec.encode(listOf(camera))
        val extended = bytes.copyOf(bytes.size + 1)
        extended[extended.size - 1] = 0

        assertNull("a file with bytes the format does not account for is not a valid set",
            HiddenApplicationCodec.decode(extended))
    }

    @Test
    fun `a damaged checksum is rejected`() {
        val bytes = HiddenApplicationCodec.encode(listOf(camera))
        bytes[firstEntryIndex + 1] = bytes[firstEntryIndex + 1].inc()

        assertNull(
            "a payload changed after the checksum was written is damage, not configuration",
            HiddenApplicationCodec.decode(bytes),
        )
    }

    @Test
    fun `an implausible count is rejected before anything is allocated`() {
        val bytes = HiddenApplicationCodec.encode(listOf(camera))
        // A count far beyond any device's application list, written over the real one.
        bytes[countIndex] = 0x7F
        bytes[countIndex + 1] = 0xFF.toByte()
        bytes[countIndex + 2] = 0xFF.toByte()
        bytes[countIndex + 3] = 0xFF.toByte()

        assertNull(HiddenApplicationCodec.decode(bytes))
    }

    @Test
    fun `a negative count is rejected`() {
        val bytes = HiddenApplicationCodec.encode(listOf(camera))
        bytes[countIndex] = 0xFF.toByte()

        assertNull(HiddenApplicationCodec.decode(bytes))
    }

    @Test
    fun `a zero-length name is rejected`() {
        // A header claiming one entry, followed by an entry whose length byte is zero: a name that
        // is not a name. The checksum is written correctly, so only the name rule can reject it.
        val bytes = ByteArray(firstEntryIndex + 1 + 4)
        bytes[0] = 'N'.code.toByte()
        bytes[1] = 'V'.code.toByte()
        bytes[2] = 'H'.code.toByte()
        bytes[3] = 'A'.code.toByte()
        bytes[versionIndex] = HiddenApplicationCodec.FORMAT_VERSION.toByte()
        bytes[countIndex + 3] = 1
        bytes[firstEntryIndex] = 0
        writeChecksum(bytes, bytes.size - 4)

        assertNull(HiddenApplicationCodec.decode(bytes))
    }

    @Test
    fun `a name that is not a package name is rejected even when the checksum fits`() {
        // A single segment with no dot is not an application identity. The file is written the way a
        // damaged one could be, with a correct checksum over the wrong content, so only the name rule
        // can reject it.
        val name = "camera".toByteArray(Charsets.UTF_8)
        val bytes = ByteArray(firstEntryIndex + 1 + name.size + 4)
        bytes[0] = 'N'.code.toByte()
        bytes[1] = 'V'.code.toByte()
        bytes[2] = 'H'.code.toByte()
        bytes[3] = 'A'.code.toByte()
        bytes[versionIndex] = HiddenApplicationCodec.FORMAT_VERSION.toByte()
        bytes[countIndex + 3] = 1
        bytes[firstEntryIndex] = name.size.toByte()
        name.copyInto(bytes, firstEntryIndex + 1)
        writeChecksum(bytes, bytes.size - 4)

        assertNull(HiddenApplicationCodec.decode(bytes))
    }

    @Test
    fun `a duplicated entry is rejected`() {
        val one = HiddenApplicationCodec.encode(listOf(camera))
        val name = camera.packageName.toByteArray(Charsets.UTF_8)
        val entry = 1 + name.size
        val bytes = ByteArray(firstEntryIndex + entry * 2 + 4)
        one.copyInto(bytes, 0, 0, countIndex)
        bytes[countIndex] = 0
        bytes[countIndex + 1] = 0
        bytes[countIndex + 2] = 0
        bytes[countIndex + 3] = 2
        bytes[firstEntryIndex] = name.size.toByte()
        name.copyInto(bytes, firstEntryIndex + 1)
        bytes[firstEntryIndex + entry] = name.size.toByte()
        name.copyInto(bytes, firstEntryIndex + entry + 1)
        writeChecksum(bytes, bytes.size - 4)

        assertNull(
            "the file holds a set, and a set cannot contain the same package twice",
            HiddenApplicationCodec.decode(bytes),
        )
    }

    @Test
    fun `an oversized set is refused rather than written`() {
        val tooMany = List(HiddenApplicationCodec.MAXIMUM_APPLICATIONS + 1) { index ->
            HiddenApplication("com.example.app$index")
        }

        assertThrows(IllegalArgumentException::class.java) { HiddenApplicationCodec.encode(tooMany) }
    }

    @Test
    fun `the format version this build writes is the one it reads`() {
        val bytes = HiddenApplicationCodec.encode(listOf(camera))

        assertEquals(HiddenApplicationCodec.FORMAT_VERSION, bytes[versionIndex].toInt())
        assertTrue(HiddenApplicationCodec.FORMAT_VERSION in 1..255)
    }

    @Test
    fun `decoding does not accept a protected-set file`() {
        // The two formats share a shape and differ by magic on purpose: it must be impossible to read
        // one feature's configuration as the other's.
        val protectedMagic = byteArrayOf('N'.code.toByte(), 'V'.code.toByte(), 'P'.code.toByte(), 'L'.code.toByte())
        val bytes = HiddenApplicationCodec.encode(listOf(camera))
        protectedMagic.copyInto(bytes, 0)

        assertNull(HiddenApplicationCodec.decode(bytes))
    }

    /** Writes the CRC-32 the format expects into the last four bytes. */
    private fun writeChecksum(bytes: ByteArray, offset: Int) {
        val crc = java.util.zip.CRC32()
        crc.update(bytes, 0, offset)
        val value = crc.value.toInt()
        bytes[offset] = (value shr 24).toByte()
        bytes[offset + 1] = (value shr 16).toByte()
        bytes[offset + 2] = (value shr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }
}
