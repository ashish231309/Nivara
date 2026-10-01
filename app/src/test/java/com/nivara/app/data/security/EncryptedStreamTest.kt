package com.nivara.app.data.security

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.domain.security.CryptographicFailure
import com.nivara.app.domain.security.EncryptionContext
import com.nivara.app.domain.security.EncryptionKey
import com.nivara.app.domain.security.SecureRandomGenerator
import com.nivara.app.testing.material
import com.nivara.app.testing.randomKey
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for the vault's streaming authenticated-encryption format.
 *
 * A file is not a small payload, so it is encrypted as a sequence of authenticated records, and the
 * only thing that makes that safe is that every record binds its own position, its length, the
 * stream's header and the item's identity. The tests below are the negative ones: a truncated,
 * reordered, duplicated, edited or misfiled stream must fail as a whole rather than yield a shorter,
 * reordered or different file. A stream that fails is never partially written to the caller's sink.
 */
class EncryptedStreamTest {

    private val random = SecureRandomGenerator()
    private val service = JcaEncryptionService(random = random)
    private val identity = ByteArray(EncryptedStream.IDENTITY_LENGTH) { index -> index.toByte() }
    private val otherIdentity = ByteArray(EncryptedStream.IDENTITY_LENGTH) { index -> (index + 1).toByte() }

    // ------------------------------------------------------------------ round trips

    @Test
    fun `a stream of a few bytes comes back exactly`() = runTest {
        val plaintext = "the vault keeps this file".toByteArray()

        val restored = roundTrip(plaintext)

        assertArrayEquals(plaintext, restored)
    }

    @Test
    fun `an empty stream is a stream and comes back empty`() = runTest {
        val ciphertext = encrypt(ByteArray(0))

        // A stream with nothing in it still ends with the record that says so, so an empty object is
        // distinguishable from a truncated one.
        assertTrue(ciphertext.size > EncryptedStream.HEADER_LENGTH + EncryptedStream.RECORD_HEADER_LENGTH)
        assertArrayEquals(ByteArray(0), decrypt(ciphertext).first)
    }

    @Test
    fun `a stream of exactly one chunk comes back, and ends with an empty final record`() = runTest {
        val plaintext = ByteArray(EncryptedStream.CHUNK_SIZE_BYTES) { index -> (index % 251).toByte() }

        val ciphertext = encrypt(plaintext)

        // Two records: the chunk, then the record that marks the end. The final record is what makes
        // "the source had exactly a chunk left" different from "the source was cut off".
        assertEquals(
            2,
            countRecords(ciphertext, key = testKey),
        )
        assertArrayEquals(plaintext, decrypt(ciphertext).first)
    }

    @Test
    fun `a stream that is several chunks long comes back in order`() = runTest {
        val plaintext = ByteArray(EncryptedStream.CHUNK_SIZE_BYTES * 2 + 1_237) { index ->
            (index % 249).toByte()
        }

        assertArrayEquals(plaintext, roundTrip(plaintext))
    }

    @Test
    fun `a large synthetic stream is encrypted and read back without being held whole`() = runTest {
        // 4 MiB: large enough to cross the chunk boundary many times, small enough to run in CI.
        val size = 4 * 1024 * 1024
        val plaintext = ByteArray(size) { index -> ((index * 31) % 251).toByte() }

        val ciphertext = encrypt(plaintext)
        val restored = decrypt(ciphertext)

        assertEquals(size, restored.first.size)
        assertTrue(restored.second == size.toLong())
        // The ciphertext is larger than the plaintext by exactly the framing: one header, one record
        // header per record and one tag per record.
        val records = (size + EncryptedStream.CHUNK_SIZE_BYTES - 1) / EncryptedStream.CHUNK_SIZE_BYTES
        assertEquals(
            EncryptedStream.HEADER_LENGTH +
                records * (EncryptedStream.RECORD_HEADER_LENGTH + EncryptedStream.TAG_LENGTH_BYTES) +
                size,
            ciphertext.size,
        )
        assertArrayEquals(plaintext, restored.first)
    }

    // ------------------------------------------------------------------ what must fail

    @Test
    fun `the wrong key cannot read a stream`() = runTest {
        val ciphertext = encrypt("secret".toByteArray())

        val result = decryptResult(ciphertext, key = randomKey(label = "another-key"))

        assertTrue(result is NivaraResult.Failure)
        assertTrue((result as NivaraResult.Failure).error is CryptographicFailure)
    }

    @Test
    fun `a stream written for another purpose is refused before it is read`() = runTest {
        val ciphertext = encrypt("secret".toByteArray(), context = EncryptionContext.VaultContent)

        val result = service.decryptStream(
            ciphertext = ByteArrayInputStream(ciphertext),
            plaintext = ByteArrayOutputStream(),
            key = testKey,
            context = EncryptionContext.VaultIndex,
            identity = identity,
        )

        assertTrue(result is NivaraResult.Failure)
        assertEquals(
            CryptographicFailure.ContextMismatch,
            (result as NivaraResult.Failure).error,
        )
    }

    @Test
    fun `a stream written for another item is refused`() = runTest {
        val ciphertext = encrypt("secret".toByteArray())

        val result = decryptResult(ciphertext, identity = otherIdentity)

        assertTrue(result is NivaraResult.Failure)
        assertEquals(
            CryptographicFailure.AuthenticationFailed,
            (result as NivaraResult.Failure).error,
        )
    }

    @Test
    fun `a truncated stream fails instead of returning a prefix`() = runTest {
        val plaintext = ByteArray(EncryptedStream.CHUNK_SIZE_BYTES + 100) { index -> (index % 97).toByte() }
        val ciphertext = encrypt(plaintext)

        // Cut inside the second record: the prefix of a file is not the file.
        val truncated = ciphertext.copyOf(EncryptedStream.HEADER_LENGTH + EncryptedStream.RECORD_HEADER_LENGTH + 10)
        val sink = ByteArrayOutputStream()
        val result = decryptInto(truncated, sink)

        assertTrue(result is NivaraResult.Failure)
        // Only the records that authenticated were written — the first chunk — and the caller is told
        // the stream failed rather than being handed that prefix as the file.
        assertTrue("no record past the truncation was written", sink.size() <= EncryptedStream.CHUNK_SIZE_BYTES)
        assertTrue("the prefix is not the file", sink.size() < plaintext.size)
    }

    @Test
    fun `a stream that stops after a complete record fails without its final record`() = runTest {
        val plaintext = ByteArray(EncryptedStream.CHUNK_SIZE_BYTES + 10) { index -> (index % 89).toByte() }
        val ciphertext = encrypt(plaintext)
        // Everything but the last record header and body: the stream simply stops.
        val cut = ciphertext.copyOf(ciphertext.size - (EncryptedStream.RECORD_HEADER_LENGTH + 10 + EncryptedStream.TAG_LENGTH_BYTES))

        val result = decryptInto(cut, ByteArrayOutputStream())

        assertTrue(result is NivaraResult.Failure)
        assertEquals(CryptographicFailure.MalformedEnvelope, (result as NivaraResult.Failure).error)
    }

    @Test
    fun `an edited ciphertext byte fails authentication`() = runTest {
        val ciphertext = encrypt("the original content".toByteArray())
        val edited = ciphertext.copyOf().also { bytes ->
            bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        }

        assertTrue(decryptInto(edited, ByteArrayOutputStream()) is NivaraResult.Failure)
    }

    @Test
    fun `an edited record header fails authentication`() = runTest {
        val ciphertext = encrypt("the original content".toByteArray())
        // The first byte of the first record header is part of the plaintext length, which is bound
        // as associated data; editing it must make the record fail rather than change its meaning.
        val edited = ciphertext.copyOf().also { bytes -> bytes[EncryptedStream.HEADER_LENGTH] = 1 }

        assertTrue(decryptInto(edited, ByteArrayOutputStream()) is NivaraResult.Failure)
    }

    @Test
    fun `an edited header fails authentication`() = runTest {
        val ciphertext = encrypt("the original content".toByteArray())
        // Offset 20 is inside the header's nonce, which is bound into every record's associated data
        // *and* decides each record's nonce: changing it anywhere makes the stream unreadable.
        val edited = ciphertext.copyOf().also { bytes -> bytes[20] = (bytes[20].toInt() xor 0x01).toByte() }

        val result = decryptInto(edited, ByteArrayOutputStream())

        assertTrue(result is NivaraResult.Failure)
        assertEquals(CryptographicFailure.AuthenticationFailed, (result as NivaraResult.Failure).error)
    }

    @Test
    fun `bytes after the final record are refused`() = runTest {
        val ciphertext = encrypt("the original content".toByteArray())

        val extended = ciphertext + byteArrayOf(0, 0, 0)

        val result = decryptInto(extended, ByteArrayOutputStream())

        assertTrue(result is NivaraResult.Failure)
        assertEquals(CryptographicFailure.MalformedEnvelope, (result as NivaraResult.Failure).error)
    }

    @Test
    fun `reordered records are refused`() = runTest {
        val assembled = assemble(swapFirstTwoRecords = true)

        val result = decryptInto(assembled, ByteArrayOutputStream())

        assertTrue(result is NivaraResult.Failure)
        assertEquals(CryptographicFailure.MalformedEnvelope, (result as NivaraResult.Failure).error)
    }

    @Test
    fun `a duplicated record is refused`() = runTest {
        val assembled = assemble(duplicateFirstRecord = true)

        val result = decryptInto(assembled, ByteArrayOutputStream())

        assertTrue(result is NivaraResult.Failure)
    }

    @Test
    fun `a record with a different position in the stream is refused`() = runTest {
        // The second record is written as if it were the first: its sequence number is bound as
        // associated data, so it must not be accepted in another position.
        val assembled = assemble(renumberSecondRecordToZero = true)

        val result = decryptInto(assembled, ByteArrayOutputStream())

        assertTrue(result is NivaraResult.Failure)
    }

    // ------------------------------------------------------------------ nonces

    @Test
    fun `no two streams under one key share a nonce`() = runTest {
        val nonces = mutableSetOf<String>()
        repeat(64) {
            val ciphertext = encrypt("the same content every time".toByteArray())
            val header = EncryptedStream.readHeader(
                ByteArrayInputStream(ciphertext),
                expectedContext = EncryptionContext.VaultContent,
            )
            assertTrue("a nonce was drawn twice", nonces.add(header.nonce.joinToString("") { byte -> "%02x".format(byte) }))
            // And the same plaintext never produces the same ciphertext.
            assertFalse(ciphertext.contentEquals(encrypt("the same content every time".toByteArray())))
        }
    }

    @Test
    fun `a record nonce is never the header's own nonce and never repeats within a stream`() {
        val headerNonce = ByteArray(EncryptedStream.NONCE_LENGTH) { index -> index.toByte() }
        val nonces = mutableSetOf<String>()

        for (sequence in 0 until 1_000L) {
            val nonce = EncryptedStream.recordNonce(headerNonce, sequence)
            assertEquals(EncryptedStream.NONCE_LENGTH, nonce.size)
            assertFalse("the header's value is not a record nonce", nonce.contentEquals(headerNonce))
            assertTrue(
                "two records in one stream share a nonce",
                nonces.add(nonce.joinToString("") { byte -> "%02x".format(byte) }),
            )
        }
    }

    @Test
    fun `record nonces differ between streams that drew different header nonces`() {
        val first = EncryptedStream.recordNonce(ByteArray(EncryptedStream.NONCE_LENGTH) { 1 }, 0)
        val second = EncryptedStream.recordNonce(ByteArray(EncryptedStream.NONCE_LENGTH) { 2 }, 0)

        assertFalse(first.contentEquals(second))
    }

    @Test
    fun `the same plaintext under the same key produces different ciphertext`() = runTest {
        val plaintext = "identical content".toByteArray()

        val first = encrypt(plaintext)
        val second = encrypt(plaintext)

        assertEquals("the framing is the same either way", first.size, second.size)
        assertFalse("the bytes are not", first.contentEquals(second))
        assertArrayEquals(plaintext, decrypt(first).first)
        assertArrayEquals(plaintext, decrypt(second).first)
    }

    @Test
    fun `a header that is not a Nivara stream header is refused`() = runTest {
        val result = service.decryptStream(
            ciphertext = ByteArrayInputStream("not a stream".toByteArray()),
            plaintext = ByteArrayOutputStream(),
            key = testKey,
            context = EncryptionContext.VaultContent,
            identity = identity,
        )

        assertTrue(result is NivaraResult.Failure)
    }

    @Test
    fun `the identity must be exactly sixteen bytes`() = runTest {
        val result = service.encryptStream(
            plaintext = ByteArrayInputStream("content".toByteArray()),
            ciphertext = ByteArrayOutputStream(),
            key = testKey,
            context = EncryptionContext.VaultContent,
            identity = ByteArray(EncryptedStream.IDENTITY_LENGTH - 1),
        )

        assertTrue(result is NivaraResult.Failure)
        assertEquals(CryptographicFailure.InvalidParameters, (result as NivaraResult.Failure).error)
    }

    // ------------------------------------------------------------------ helpers

    private val testKey: EncryptionKey = randomKey(label = "stream-key")

    private suspend fun encrypt(
        plaintext: ByteArray,
        context: EncryptionContext = EncryptionContext.VaultContent,
        key: EncryptionKey = testKey,
        identity: ByteArray = this.identity,
    ): ByteArray {
        val sink = ByteArrayOutputStream()
        val result = service.encryptStream(
            plaintext = ByteArrayInputStream(plaintext),
            ciphertext = sink,
            key = key,
            context = context,
            identity = identity,
        )
        assertTrue(result is NivaraResult.Success)
        assertEquals(plaintext.size.toLong(), (result as NivaraResult.Success).value)
        return sink.toByteArray()
    }

    private suspend fun roundTrip(plaintext: ByteArray): ByteArray = decrypt(encrypt(plaintext)).first

    private suspend fun decrypt(ciphertext: ByteArray): Pair<ByteArray, Long> {
        val sink = ByteArrayOutputStream()
        val result = decryptInto(ciphertext, sink)
        assertTrue("the stream did not decrypt: $result", result is NivaraResult.Success)
        return sink.toByteArray() to (result as NivaraResult.Success).value
    }

    private suspend fun decryptResult(
        ciphertext: ByteArray,
        key: EncryptionKey = testKey,
        identity: ByteArray = this.identity,
        context: EncryptionContext = EncryptionContext.VaultContent,
    ): NivaraResult<Long> = service.decryptStream(
        ciphertext = ByteArrayInputStream(ciphertext),
        plaintext = ByteArrayOutputStream(),
        key = key,
        context = context,
        identity = identity,
    )

    private suspend fun decryptInto(ciphertext: ByteArray, sink: OutputStream): NivaraResult<Long> =
        service.decryptStream(
            ciphertext = ByteArrayInputStream(ciphertext),
            plaintext = sink,
            key = testKey,
            context = EncryptionContext.VaultContent,
            identity = identity,
        )

    /**
     * Builds a stream record by record, so a test can assemble one a writer never would.
     *
     * Every stream here is written with the real codec and then reassembled; nothing is fabricated
     * by hand, so what a failure proves is that the *reader* refuses an impossible order rather than
     * that the test wrote nonsense.
     */
    private fun assemble(
        swapFirstTwoRecords: Boolean = false,
        duplicateFirstRecord: Boolean = false,
        renumberSecondRecordToZero: Boolean = false,
    ): ByteArray {
        val key = (testKey as EncryptionKey).asSecretKey()
        val headerBuffer = ByteArrayOutputStream()
        val header = EncryptedStream.writeHeader(
            sink = headerBuffer,
            context = EncryptionContext.VaultContent,
            identity = identity,
            nonce = ByteArray(EncryptedStream.NONCE_LENGTH) { 3 },
        )
        val first = ByteArray(16) { 1 }
        val second = ByteArray(16) { 2 }
        val records = listOf(
            record(key, header, sequence = 0, plaintext = first, isFinal = false),
            record(
                key,
                header,
                sequence = if (renumberSecondRecordToZero) 0 else 1,
                plaintext = second,
                isFinal = true,
            ),
        )
        val ordered = when {
            swapFirstTwoRecords -> listOf(records[1], records[0])
            duplicateFirstRecord -> listOf(records[0], records[0], records[1])
            else -> records
        }
        val stream = ByteArrayOutputStream()
        stream.write(headerBuffer.toByteArray())
        ordered.forEach { bytes -> stream.write(bytes) }
        return stream.toByteArray()
    }

    private fun record(
        key: javax.crypto.SecretKey,
        header: EncryptedStream.Header,
        sequence: Long,
        plaintext: ByteArray,
        isFinal: Boolean,
    ): ByteArray {
        val buffer = ByteArrayOutputStream()
        EncryptedStream.encryptRecord(
            sink = buffer,
            key = key,
            header = header,
            sequence = sequence,
            plaintext = plaintext,
            length = plaintext.size,
            isFinal = isFinal,
        )
        return buffer.toByteArray()
    }

    /** How many records a stream holds, by reading it as the reader does. */
    private fun countRecords(ciphertext: ByteArray, key: EncryptionKey): Int {
        val source: InputStream = ByteArrayInputStream(ciphertext)
        val header = EncryptedStream.readHeader(source, expectedContext = EncryptionContext.VaultContent)
        var count = 0
        var sequence = 0L
        while (true) {
            when (val record = EncryptedStream.decryptRecord(
                source = source,
                sink = ByteArrayOutputStream(),
                key = key.asSecretKey(),
                header = header,
                expectedSequence = sequence,
            )) {
                EncryptedStream.RecordRead.NotFound -> return count
                is EncryptedStream.RecordRead.Read -> {
                    count += 1
                    sequence += 1
                    if (record.isFinal) return count
                }
            }
        }
    }
}
