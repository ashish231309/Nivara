package com.nivara.app.data.security

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.nivaraRunCatching
import com.nivara.app.domain.security.CryptographicFailure
import com.nivara.app.domain.security.EncryptionContext
import com.nivara.app.domain.security.EncryptionKey
import com.nivara.app.domain.security.EncryptionService
import com.nivara.app.domain.security.SecureRandomGenerator
import com.nivara.app.domain.security.StreamSource
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [EncryptionService] implemented on the JCA provider that Android ships.
 *
 * AES-256-GCM with a fresh random 96-bit nonce per encryption and a 128-bit tag. The provider is
 * the platform's own (`Conscrypt` on Android, hardware-backed for keystore keys where the device
 * supports it), so no third-party crypto library and no native code enter the application.
 *
 * Work runs off the main thread — on [Dispatchers.Default] for the in-memory calls, and on
 * [Dispatchers.IO] for the streaming ones, which are dominated by reading and writing. A
 * device-protected key makes `Cipher.init` call into the platform key store, which must never happen
 * on the main thread, and both dispatchers keep that off it.
 */
internal class JcaEncryptionService(
    private val random: SecureRandomGenerator,
) : EncryptionService {

    override suspend fun encrypt(
        plaintext: ByteArray,
        key: EncryptionKey,
        context: EncryptionContext,
        clearPlaintextAfterUse: Boolean,
    ): NivaraResult<ByteArray> = withContext(Dispatchers.Default) {
        nivaraRunCatching {
            val nonce = random.nextByteArray(EncryptedEnvelope.NONCE_LENGTH)
            val envelope = EncryptedEnvelope.encrypt(plaintext, key.asSecretKey(), context, nonce)
            // Only clear the caller's buffer once the ciphertext exists: if encryption failed,
            // the caller still has their data and can decide what to do about the failure.
            if (clearPlaintextAfterUse) {
                plaintext.fill(0)
            }
            envelope
        }
    }

    override suspend fun decrypt(
        envelope: ByteArray,
        key: EncryptionKey,
        context: EncryptionContext,
    ): NivaraResult<ByteArray> = withContext(Dispatchers.Default) {
        nivaraRunCatching {
            EncryptedEnvelope.decrypt(envelope, key.asSecretKey(), context)
        }
    }

    /**
     * Streams [plaintext] into [ciphertext] as the [EncryptedStream] format.
     *
     * Two fixed chunk buffers are used for the whole file — one being encrypted while the other is
     * being filled — so the memory cost of encrypting a file does not depend on its size. The record
     * that ends the stream is the one after which the source has nothing more to give, which is why
     * the next chunk is read before the current one is written.
     */
    override suspend fun encryptStream(
        plaintext: StreamSource,
        ciphertext: OutputStream,
        key: EncryptionKey,
        context: EncryptionContext,
        identity: ByteArray,
        onProgress: (Long) -> Unit,
    ): NivaraResult<Long> = withContext(Dispatchers.IO) {
        nivaraRunCatching {
            if (identity.size != EncryptedStream.IDENTITY_LENGTH) {
                throw CryptographicFailure.InvalidParameters
            }
            val secretKey = key.asSecretKey()
            val header = EncryptedStream.writeHeader(
                sink = ciphertext,
                context = context,
                identity = identity,
                nonce = EncryptedStream.newNonce(random),
            )

            var current = ByteArray(EncryptedStream.CHUNK_SIZE_BYTES)
            var following = ByteArray(EncryptedStream.CHUNK_SIZE_BYTES)
            var filled = readChunk(plaintext, current)
            var sequence = 0L
            var total = 0L
            while (true) {
                val read = readChunk(plaintext, following)
                val isFinal = read == 0
                EncryptedStream.encryptRecord(
                    sink = ciphertext,
                    key = secretKey,
                    header = header,
                    sequence = sequence,
                    plaintext = current,
                    length = filled,
                    isFinal = isFinal,
                )
                total += filled
                onProgress(total)
                sequence += 1
                if (isFinal) break
                val swap = current
                current = following
                following = swap
                filled = read
            }
            ciphertext.flush()
            total
        }
    }

    /**
     * Streams [ciphertext] back into [plaintext], authenticating every record before its plaintext
     * is written.
     *
     * A stream that ends without the record that marks it complete is a failure, not a short file,
     * and so is one that carries bytes after it: both mean the object is not the object that was
     * written.
     */
    override suspend fun decryptStream(
        ciphertext: InputStream,
        plaintext: OutputStream,
        key: EncryptionKey,
        context: EncryptionContext,
        identity: ByteArray,
        onProgress: (Long) -> Unit,
    ): NivaraResult<Long> = withContext(Dispatchers.IO) {
        nivaraRunCatching {
            if (identity.size != EncryptedStream.IDENTITY_LENGTH) {
                throw CryptographicFailure.InvalidParameters
            }
            val secretKey = key.asSecretKey()
            val header = EncryptedStream.readHeader(ciphertext, expectedContext = context)
            if (!header.identity.contentEquals(identity)) {
                // An early, cheap refusal. The binding itself is the associated data: a header edited
                // to name another item makes every record's tag fail, so this check can be strict
                // without being the thing the security rests on.
                throw CryptographicFailure.AuthenticationFailed
            }

            var sequence = 0L
            var total = 0L
            while (true) {
                when (val record = EncryptedStream.decryptRecord(
                    source = ciphertext,
                    sink = plaintext,
                    key = secretKey,
                    header = header,
                    expectedSequence = sequence,
                )) {
                    EncryptedStream.RecordRead.NotFound ->
                        // The stream stopped where the next record should have begun: it was cut
                        // short, and a prefix of a file is not the file.
                        throw CryptographicFailure.MalformedEnvelope

                    is EncryptedStream.RecordRead.Read -> {
                        total += record.plaintextLength
                        onProgress(total)
                        if (record.isFinal) {
                            if (!EncryptedStream.atEnd(ciphertext)) {
                                throw CryptographicFailure.MalformedEnvelope
                            }
                            break
                        }
                    }
                }
                sequence += 1
            }
            plaintext.flush()
            total
        }
    }

    /** Fills [buffer] from [source], returning how many bytes were read. */
    /**
     * One piece of plaintext, or nothing when the source has ended.
     *
     * The source decides how much it hands over, so a provider that returns less than a full buffer
     * simply produces a shorter record — the record's own length is authenticated, so nothing about
     * the format depends on pieces being full. What cannot be accepted is a source that stops
     * *saying* it ended: it returns `-1`, and anything that means "unreadable" is thrown.
     */
    private suspend fun readChunk(source: StreamSource, buffer: ByteArray): Int {
        val read = source.read(buffer)
        return if (read < 0) 0 else read
    }
}
