package com.nivara.app.data.security

import com.nivara.app.domain.security.CryptographicFailure
import com.nivara.app.domain.security.EncryptionContext
import com.nivara.app.domain.security.SecureRandomGenerator
import java.io.InputStream
import java.io.OutputStream
import java.security.InvalidAlgorithmParameterException
import java.security.InvalidKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The streaming authenticated-encryption format, version 1.
 *
 * ```
 *  header (41 bytes), written first
 *  offset 0        4        5         6        7          8          9            13        25
 *        | "NVCO" | version| reserved | context| keyScheme| algorithm | chunkSize(4)| nonce(12)| identity(16) |
 *
 *  record, repeated to the end of the stream
 *  offset 0                  4             12           13            17            17 + length + tag
 *        | plaintextLength(4) | sequence(8) | flags(1) | ciphertextLength(4) | ciphertext + tag |
 *
 *  the record's nonce is derived, not stored:
 *        recordNonce(i) = headerNonce XOR bigEndian64(i + 1)
 * ```
 *
 * ### Why one stream, rather than a sequence of independent envelopes
 *
 * A user file can be gigabytes, so it cannot be one envelope; and independently encrypted chunks are
 * only safe if their *order and completeness* are authenticated too, or a chunk can be dropped from
 * the middle and nothing notices. Every record therefore authenticates — as its associated data —
 * the whole header (magic, version, purpose, key scheme, algorithm, chunk size, nonce, identity) and
 * its own record header (plaintext length, sequence, flags, ciphertext length). The record that
 * carries the end of the file is marked [FLAG_FINAL], and a reader accepts a stream only when it
 * ends with such a record and nothing follows it. Dropping a record, reordering two, duplicating
 * one, editing a length, or truncating the stream all fail authentication; none of them yields a
 * shorter or different file.
 *
 * ### Nonces
 *
 * One fresh random 96-bit nonce per stream from [SecureRandomGenerator], and every record's nonce is
 * that value with the record counter exclusive-ored in — so no record of a stream reuses another's
 * nonce, the header's own value is never used as a record nonce, and a collision under one key would
 * require two streams to draw the same 96 random bits. Nothing derives a nonce from a name, an id, a
 * time or a counter alone.
 *
 * ### What this class is, and is not
 *
 * It is the second and last place in the project that touches `Cipher`, next to [EncryptedEnvelope]:
 * the small in-memory envelope and the streaming format are the two shapes a Nivara ciphertext can
 * have, and everything else — the vault record, wrapped keys, the index — is built on them. Both are
 * AES-256-GCM with a 128-bit tag; neither has a fallback, a weaker mode, or a nonce the caller
 * chooses. Nothing here is held whole in memory: a record is at most 64 KiB of plaintext.
 */
internal object EncryptedStream {

    /** "NVCO" — Nivara content object. */
    val MAGIC: ByteArray = "NVCO".toByteArray(Charsets.US_ASCII)

    /** The only stream version this build writes. */
    const val VERSION: Int = 1

    /** The only key scheme and algorithm: AES-256-GCM, as in every other Nivara ciphertext. */
    const val KEY_SCHEME: Int = 1
    const val ALGORITHM: Int = 1

    const val NONCE_LENGTH: Int = 12
    const val TAG_LENGTH_BITS: Int = 128
    const val TAG_LENGTH_BYTES: Int = TAG_LENGTH_BITS / 8

    /** Bytes of identity a stream is bound to. */
    const val IDENTITY_LENGTH: Int = 16

    /**
     * Plaintext bytes per record (64 KiB).
     *
     * Fixed by the format and written into the header, which is authenticated with every record, so a
     * stream declares the chunk size it was written with and a reader that expects another refuses
     * it. Fixing it also means a reader knows the largest record it can be asked to hold.
     */
    const val CHUNK_SIZE_BYTES: Int = 64 * 1024

    private const val VERSION_OFFSET = 4
    private const val RESERVED_OFFSET = 5
    private const val CONTEXT_OFFSET = 6
    private const val KEY_SCHEME_OFFSET = 7
    private const val ALGORITHM_OFFSET = 8
    private const val CHUNK_SIZE_OFFSET = 9
    private const val NONCE_OFFSET = 13
    private const val IDENTITY_OFFSET = NONCE_OFFSET + NONCE_LENGTH
    private const val RESERVED_VALUE = 0

    const val HEADER_LENGTH: Int = IDENTITY_OFFSET + IDENTITY_LENGTH
    const val RECORD_HEADER_LENGTH: Int = 4 + 8 + 1 + 4

    const val FLAG_FINAL: Int = 0x01

    /** The largest record: a full chunk, its header and its tag. */
    const val MAXIMUM_RECORD_LENGTH: Int = RECORD_HEADER_LENGTH + CHUNK_SIZE_BYTES + TAG_LENGTH_BYTES

    /** A parsed stream header. The bytes are kept so every record can bind them as associated data. */
    class Header internal constructor(
        val context: EncryptionContext,
        val chunkSize: Int,
        val nonce: ByteArray,
        val identity: ByteArray,
        internal val bytes: ByteArray,
    )

    /** Writes a fresh header for [context] and [identity], returning it so records can bind to it. */
    fun writeHeader(
        sink: OutputStream,
        context: EncryptionContext,
        identity: ByteArray,
        nonce: ByteArray,
    ): Header {
        require(identity.size == IDENTITY_LENGTH) { "a stream identity is $IDENTITY_LENGTH bytes" }
        require(nonce.size == NONCE_LENGTH) { "a stream nonce is $NONCE_LENGTH bytes" }
        val bytes = ByteArray(HEADER_LENGTH)
        MAGIC.copyInto(bytes, 0)
        bytes[VERSION_OFFSET] = VERSION.toByte()
        bytes[RESERVED_OFFSET] = RESERVED_VALUE.toByte()
        bytes[CONTEXT_OFFSET] = context.tag.toByte()
        bytes[KEY_SCHEME_OFFSET] = KEY_SCHEME.toByte()
        bytes[ALGORITHM_OFFSET] = ALGORITHM.toByte()
        writeInt(bytes, CHUNK_SIZE_OFFSET, CHUNK_SIZE_BYTES)
        nonce.copyInto(bytes, NONCE_OFFSET)
        identity.copyInto(bytes, IDENTITY_OFFSET)
        sink.write(bytes)
        return Header(
            context = context,
            chunkSize = CHUNK_SIZE_BYTES,
            nonce = nonce.copyOf(),
            identity = identity.copyOf(),
            bytes = bytes,
        )
    }

    /**
     * Reads and validates a stream's header.
     *
     * Everything that is not a header of exactly this version, purpose, scheme and algorithm raises a
     * typed failure before a byte of ciphertext is read.
     */
    fun readHeader(source: InputStream, expectedContext: EncryptionContext): Header {
        val bytes = readExactly(source, HEADER_LENGTH)
            ?: throw CryptographicFailure.MalformedEnvelope
        if (!bytes.hasPrefix(MAGIC)) throw CryptographicFailure.UnsupportedEnvelope
        if (bytes[VERSION_OFFSET].toInt() != VERSION) throw CryptographicFailure.UnsupportedVersion
        if (bytes[RESERVED_OFFSET].toInt() != RESERVED_VALUE) {
            throw CryptographicFailure.MalformedEnvelope
        }
        val context = EncryptionContext.fromTag(bytes[CONTEXT_OFFSET].toInt() and 0xFF)
            ?: throw CryptographicFailure.UnsupportedContext
        if (context != expectedContext) throw CryptographicFailure.ContextMismatch
        if (bytes[KEY_SCHEME_OFFSET].toInt() != KEY_SCHEME) {
            throw CryptographicFailure.UnsupportedKeyScheme
        }
        if (bytes[ALGORITHM_OFFSET].toInt() != ALGORITHM) {
            throw CryptographicFailure.UnsupportedAlgorithm
        }
        if (readInt(bytes, CHUNK_SIZE_OFFSET) != CHUNK_SIZE_BYTES) {
            throw CryptographicFailure.UnsupportedVersion
        }
        return Header(
            context = context,
            chunkSize = CHUNK_SIZE_BYTES,
            nonce = bytes.copyOfRange(NONCE_OFFSET, IDENTITY_OFFSET),
            identity = bytes.copyOfRange(IDENTITY_OFFSET, HEADER_LENGTH),
            bytes = bytes,
        )
    }

    /**
     * Encrypts one record of [length] plaintext bytes from [plaintext] into [sink].
     *
     * The record header is assembled first — a GCM ciphertext is exactly the plaintext plus its
     * tag, so the lengths are known before encrypting — then bound as associated data together with
     * the stream header, then written ahead of the ciphertext.
     */
    fun encryptRecord(
        sink: OutputStream,
        key: SecretKey,
        header: Header,
        sequence: Long,
        plaintext: ByteArray,
        length: Int,
        isFinal: Boolean,
    ) {
        require(length in 0..header.chunkSize) { "a record holds at most one chunk" }
        val recordHeader = recordHeader(
            plaintextLength = length,
            sequence = sequence,
            isFinal = isFinal,
            ciphertextLength = length + TAG_LENGTH_BYTES,
        )
        val cipher = cipher(Cipher.ENCRYPT_MODE, key, recordNonce(header.nonce, sequence))
        cipher.updateAAD(recordHeader)
        cipher.updateAAD(header.bytes)
        val body = cipher.doFinal(plaintext, 0, length)
        sink.write(recordHeader)
        sink.write(body)
    }

    /**
     * Reads, authenticates and decrypts one record, writing its plaintext to [sink].
     *
     * The plaintext is written only after the tag has verified, so a failed record leaves nothing
     * behind it. [RecordRead.NotFound] means the stream ended where a record would have begun, which
     * the caller must treat as a truncated stream rather than as the end of a file.
     */
    fun decryptRecord(
        source: InputStream,
        sink: OutputStream,
        key: SecretKey,
        header: Header,
        expectedSequence: Long,
    ): RecordRead {
        val recordHeader = readExactly(source, RECORD_HEADER_LENGTH) ?: return RecordRead.NotFound
        val plaintextLength = readInt(recordHeader, 0)
        val sequence = readLong(recordHeader, 4)
        val flags = recordHeader[12].toInt() and 0xFF
        val ciphertextLength = readInt(recordHeader, 13)
        if (flags != FLAG_FINAL && flags != 0) throw CryptographicFailure.MalformedEnvelope
        if (plaintextLength !in 0..header.chunkSize) throw CryptographicFailure.MalformedEnvelope
        if (ciphertextLength != plaintextLength + TAG_LENGTH_BYTES) {
            throw CryptographicFailure.MalformedEnvelope
        }
        if (sequence != expectedSequence) {
            // A record out of place: a reordered stream, a duplicated record, or a hole in it.
            throw CryptographicFailure.MalformedEnvelope
        }
        val body = readExactly(source, ciphertextLength) ?: throw CryptographicFailure.MalformedEnvelope
        val cipher = cipher(Cipher.DECRYPT_MODE, key, recordNonce(header.nonce, sequence))
        cipher.updateAAD(recordHeader)
        cipher.updateAAD(header.bytes)
        val plaintext = try {
            cipher.doFinal(body)
        } catch (badTag: AEADBadTagException) {
            throw CryptographicFailure.AuthenticationFailed
        } catch (badPadding: BadPaddingException) {
            throw CryptographicFailure.AuthenticationFailed
        } catch (badSize: IllegalBlockSizeException) {
            throw CryptographicFailure.MalformedEnvelope
        }
        sink.write(plaintext)
        return RecordRead.Read(plaintextLength = plaintextLength.toLong(), isFinal = flags != 0)
    }

    /** The outcome of trying to read one record. */
    sealed interface RecordRead {

        /** No record begins here: the stream ended where a record would have started. */
        data object NotFound : RecordRead

        /** A record was read, authenticated and its plaintext written. */
        data class Read(val plaintextLength: Long, val isFinal: Boolean) : RecordRead
    }

    /**
     * Whether the stream is at its end, consuming one byte when it is not.
     *
     * Used after the record that claims to be final: anything left over means the stream is not the
     * stream that was written, and the caller raises a failure rather than ignoring it.
     */
    fun atEnd(source: InputStream): Boolean = source.read() < 0

    /** The record header, as the associated data and the frame both see it. */
    private fun recordHeader(
        plaintextLength: Int,
        sequence: Long,
        isFinal: Boolean,
        ciphertextLength: Int,
    ): ByteArray {
        val header = ByteArray(RECORD_HEADER_LENGTH)
        writeInt(header, 0, plaintextLength)
        writeLong(header, 4, sequence)
        header[12] = (if (isFinal) FLAG_FINAL else 0).toByte()
        writeInt(header, 13, ciphertextLength)
        return header
    }

    /**
     * The nonce for record [sequence]: the header nonce with the counter exclusive-ored in.
     *
     * The counter starts at one, so the header's own value is never used as a record nonce, and every
     * record of a stream gets a different one.
     */
    fun recordNonce(headerNonce: ByteArray, sequence: Long): ByteArray {
        val nonce = headerNonce.copyOf()
        val counter = sequence + 1
        for (index in 0 until COUNTER_BYTES) {
            val shift = (COUNTER_BYTES - 1 - index) * 8
            val position = NONCE_LENGTH - COUNTER_BYTES + index
            nonce[position] =
                (nonce[position].toInt() xor ((counter ushr shift) and 0xFF).toInt()).toByte()
        }
        return nonce
    }

    /** Reads exactly [size] bytes, or `null` when the stream ends first. */
    fun readExactly(source: InputStream, size: Int): ByteArray? {
        val buffer = ByteArray(size)
        var read = 0
        while (read < size) {
            val count = source.read(buffer, read, size - read)
            if (count <= 0) return null
            read += count
        }
        return buffer
    }

    /** A fresh nonce for one stream, from the only randomness source Nivara permits. */
    fun newNonce(random: SecureRandomGenerator): ByteArray = random.nextByteArray(NONCE_LENGTH)

    private fun cipher(mode: Int, key: SecretKey, nonce: ByteArray): Cipher {
        val cipher = Cipher.getInstance(EncryptedEnvelope.TRANSFORMATION)
        try {
            cipher.init(mode, key, GCMParameterSpec(TAG_LENGTH_BITS, nonce))
        } catch (invalidKey: InvalidKeyException) {
            throw CryptographicFailure.InvalidKey
        } catch (invalidSpec: InvalidAlgorithmParameterException) {
            throw CryptographicFailure.InvalidParameters
        }
        return cipher
    }

    private fun ByteArray.hasPrefix(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (index in prefix.indices) {
            if (this[index] != prefix[index]) return false
        }
        return true
    }

    private fun writeInt(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value ushr 24).toByte()
        buffer[offset + 1] = (value ushr 16).toByte()
        buffer[offset + 2] = (value ushr 8).toByte()
        buffer[offset + 3] = value.toByte()
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun writeLong(buffer: ByteArray, offset: Int, value: Long) {
        for (index in 0 until COUNTER_BYTES) {
            buffer[offset + index] = (value ushr ((COUNTER_BYTES - 1 - index) * 8)).toByte()
        }
    }

    private fun readLong(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until COUNTER_BYTES) {
            value = (value shl 8) or (bytes[offset + index].toLong() and 0xFFL)
        }
        return value
    }

    private const val COUNTER_BYTES = 8
}
