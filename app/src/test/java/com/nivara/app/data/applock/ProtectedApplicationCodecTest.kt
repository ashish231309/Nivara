package com.nivara.app.data.applock

import com.nivara.app.data.credential.checksum
import com.nivara.app.data.credential.writeInt
import com.nivara.app.domain.applock.ProtectedApplication
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Local JVM tests for the stored format of the protected application set.
 *
 * The format is the one the application writes to its private storage, so these tests use the real
 * encoder and decoder. Three properties are load-bearing and are tested as such: the bytes are
 * deterministic (so an unchanged set is an unchanged file), the file contains nothing but package
 * names (nothing about the applications is recorded), and anything that cannot be read exactly is
 * rejected rather than guessed at.
 */
class ProtectedApplicationCodecTest {

    private val camera = ProtectedApplication("com.example.camera")
    private val notes = ProtectedApplication("com.example.notes")
    private val maps = ProtectedApplication("com.example.maps")

    @Test
    fun `an empty set round-trips`() {
        val bytes = ProtectedApplicationCodec.encode(emptySet())

        assertEquals(ProtectedApplicationCodec.MINIMUM_BYTES, bytes.size)
        assertEquals(emptySet<ProtectedApplication>(), ProtectedApplicationCodec.decode(bytes))
    }

    @Test
    fun `a set round-trips`() {
        val applications = setOf(camera, notes, maps)

        val decoded = ProtectedApplicationCodec.decode(ProtectedApplicationCodec.encode(applications))

        assertEquals(applications, decoded)
    }

    @Test
    fun `the bytes do not depend on the order the set was built in`() {
        val first = ProtectedApplicationCodec.encode(linkedSetOf(camera, notes, maps))
        val second = ProtectedApplicationCodec.encode(linkedSetOf(maps, camera, notes))

        assertArrayEquals(first, second)
    }

    @Test
    fun `the file holds package names and nothing else`() {
        val applications = setOf(camera, notes)

        val bytes = ProtectedApplicationCodec.encode(applications)

        val expectedSize = ProtectedApplicationCodec.MINIMUM_BYTES +
            applications.sumOf { application -> application.packageName.toByteArray(Charsets.UTF_8).size + 1 }
        assertEquals("the format must not carry anything beyond the names", expectedSize, bytes.size)
        val text = String(bytes, Charsets.UTF_8)
        assertEquals(1, text.split(camera.packageName).size - 1)
        assertEquals(1, text.split(notes.packageName).size - 1)
    }

    @Test
    fun `an empty file is not a set`() {
        assertNull(ProtectedApplicationCodec.decode(ByteArray(0)))
    }

    @Test
    fun `a truncated file is refused`() {
        val bytes = ProtectedApplicationCodec.encode(setOf(camera))

        assertNull(ProtectedApplicationCodec.decode(bytes.copyOf(bytes.size - 1)))
        assertNull(ProtectedApplicationCodec.decode(bytes.copyOf(ProtectedApplicationCodec.MINIMUM_BYTES - 1)))
    }

    @Test
    fun `a file with the wrong magic is refused`() {
        val bytes = ProtectedApplicationCodec.encode(setOf(camera))
        bytes[0] = 'X'.code.toByte()

        assertNull(ProtectedApplicationCodec.decode(bytes))
    }

    @Test
    fun `a file from a future format version is refused`() {
        val bytes = ProtectedApplicationCodec.encode(setOf(camera))
        bytes[4] = 2

        assertNull(ProtectedApplicationCodec.decode(bytes))
    }

    @Test
    fun `an edited byte is refused`() {
        val bytes = ProtectedApplicationCodec.encode(setOf(camera))
        // Change a character of the package name without touching the checksum.
        val index = bytes.indexOfFirst { byte -> byte == 'c'.code.toByte() }
        bytes[index] = 'd'.code.toByte()

        assertNull(ProtectedApplicationCodec.decode(bytes))
    }

    @Test
    fun `a name that is not a package name is refused`() {
        assertNull(ProtectedApplicationCodec.decode(fileOf(listOf("com.example camera"))))
    }

    @Test
    fun `a blank name is refused`() {
        assertNull(ProtectedApplicationCodec.decode(fileOf(listOf(""))))
    }

    @Test
    fun `a repeated name is refused`() {
        assertNull(ProtectedApplicationCodec.decode(fileOf(listOf("com.example.camera", "com.example.camera"))))
    }

    @Test
    fun `an oversized count is refused before anything is read`() {
        val bytes = ProtectedApplicationCodec.encode(setOf(camera))
        writeInt(bytes, 5, 1_000_000)
        writeInt(bytes, bytes.size - 4, checksum(bytes, 0, bytes.size - 4))

        assertNull(ProtectedApplicationCodec.decode(bytes))
    }

    @Test
    fun `a count larger than the file is refused`() {
        val bytes = ProtectedApplicationCodec.encode(setOf(camera))
        writeInt(bytes, 5, 5)
        writeInt(bytes, bytes.size - 4, checksum(bytes, 0, bytes.size - 4))

        assertNull(ProtectedApplicationCodec.decode(bytes))
    }

    @Test
    fun `a file with bytes left over is refused`() {
        // Well-formed at the byte level — magic, version, count and checksum all agree — but one
        // byte of content belongs to no name, so the file is not the set it claims to be.
        val bytes = fileOf(names = listOf("com.example.camera"), trailingBytes = 1)

        assertNull(ProtectedApplicationCodec.decode(bytes))
    }

    @Test
    fun `a long package name round-trips`() {
        val long = ProtectedApplication("com.example." + "a".repeat(200))

        assertEquals(setOf(long), ProtectedApplicationCodec.decode(ProtectedApplicationCodec.encode(setOf(long))))
    }

    /**
     * Builds a well-formed file that declares [names], so a test can state what a decoder must
     * reject without depending on the encoder agreeing with it.
     *
     * A checksum that matches, a version this build knows and a count that agrees with the header
     * are all present; what a caller wants to be refused is whatever is wrong with the contents.
     */
    private fun fileOf(names: List<String>, trailingBytes: Int = 0): ByteArray {
        val parts = names.map { name -> name.toByteArray(Charsets.UTF_8) }
        val contentBytes = parts.sumOf { part -> 1 + part.size } + trailingBytes
        val bytes = ByteArray(9 + contentBytes + 4)
        bytes[0] = 'N'.code.toByte()
        bytes[1] = 'V'.code.toByte()
        bytes[2] = 'P'.code.toByte()
        bytes[3] = 'L'.code.toByte()
        bytes[4] = ProtectedApplicationCodec.FORMAT_VERSION.toByte()
        writeInt(bytes, 5, parts.size)
        var offset = 9
        parts.forEach { part ->
            bytes[offset] = part.size.toByte()
            part.copyInto(bytes, offset + 1)
            offset += 1 + part.size
        }
        offset += trailingBytes
        writeInt(bytes, offset, checksum(bytes, 0, offset))
        return bytes
    }
}
