package com.nivara.app.domain.security

import com.nivara.app.core.common.NivaraResult
import java.io.InputStream
import java.io.OutputStream

/**
 * Authenticated encryption for small, in-memory payloads.
 *
 * Every call produces or consumes a versioned envelope (see `docs/crypto/envelope-format.md`):
 * AES-256-GCM under a 256-bit key, a fresh random nonce per encryption, a 128-bit authentication
 * tag, and the declared [EncryptionContext] bound into the authenticated data.
 *
 * Callers never build a `Cipher`, choose a mode or supply a nonce. That is deliberate: the
 * limited ways of misusing an AEAD (fixed nonce, ECB, missing tag check, unauthenticated
 * metadata) are all outside this API.
 *
 * Scope: metadata, keys and other small values. User files enter the vault through
 * [encryptStream], which consumes and produces streams and never holds a whole file.
 *
 * Implementations must run off the main thread when a device-protected key is involved, because
 * that path calls into the platform key store.
 */
interface EncryptionService {

    /**
     * Encrypts [plaintext] into a new envelope bound to [context].
     *
     * A fresh random nonce is generated for every call, so encrypting the same plaintext with the
     * same key twice produces different ciphertext and never reuses a nonce.
     *
     * @param clearPlaintextAfterUse overwrites the caller's [plaintext] buffer once the ciphertext
     *   exists. Use it for values that will not be needed again; it never touches the returned
     *   envelope. As with all erasure on a managed runtime, this reduces exposure without
     *   guaranteeing it (see [SensitiveBytes]).
     */
    suspend fun encrypt(
        plaintext: ByteArray,
        key: EncryptionKey,
        context: EncryptionContext,
        clearPlaintextAfterUse: Boolean = false,
    ): NivaraResult<ByteArray>

    /**
     * Decrypts an envelope produced by [encrypt].
     *
     * Fails with [CryptographicFailure.AuthenticationFailed] when the envelope, its header, its
     * nonce, the associated data or the key do not agree — before returning any plaintext. Fails
     * with a typed envelope failure for unsupported versions, schemes, algorithms and purposes,
     * and with [CryptographicFailure.ContextMismatch] when [context] differs from the purpose the
     * envelope was created for. There is no fallback path and no partial output.
     */
    suspend fun decrypt(
        envelope: ByteArray,
        key: EncryptionKey,
        context: EncryptionContext,
    ): NivaraResult<ByteArray>

    /**
     * Encrypts everything [plaintext] produces into [ciphertext], in bounded pieces.
     *
     * The result is one authenticated stream, not a file of unrelated ciphertext blocks: it carries
     * a framed header bound to [identity] and ends with a record that says the stream is complete,
     * so a truncated, reordered, duplicated or edited stream is rejected as a whole. The format and
     * its security argument are in `docs/crypto/envelope-format.md`.
     *
     * Neither stream is held in memory: the implementation reads a bounded chunk, encrypts it and
     * writes it, so a file of any size costs the same amount of memory.
     *
     * @param identity the non-secret identity the ciphertext must be bound to, exactly sixteen
     *   bytes — for vault content, the item's id. A stream encrypted for one identity cannot be
     *   read as another's, because the identity is inside the authenticated data of every piece.
     * @param onProgress called with the number of plaintext bytes processed so far, for a screen
     *   that wants to show progress. It never affects what is written.
     * @return the number of plaintext bytes that were encrypted.
     */
    suspend fun encryptStream(
        plaintext: InputStream,
        ciphertext: OutputStream,
        key: EncryptionKey,
        context: EncryptionContext,
        identity: ByteArray,
        onProgress: (Long) -> Unit = {},
    ): NivaraResult<Long>

    /**
     * Decrypts a stream produced by [encryptStream] into [plaintext].
     *
     * Every piece is authenticated before its plaintext is written, and the stream is accepted only
     * if it ends with the record that marks it complete. A truncated stream is a failure, not a
     * shorter file: the caller is told the object could not be read rather than given a prefix of
     * it. Nothing is written for a piece that does not authenticate.
     *
     * @param identity the identity the caller expects the stream to be bound to, exactly sixteen
     *   bytes. A stream written for another item fails, even under the right key.
     * @return the number of plaintext bytes that were written.
     */
    suspend fun decryptStream(
        ciphertext: InputStream,
        plaintext: OutputStream,
        key: EncryptionKey,
        context: EncryptionContext,
        identity: ByteArray,
        onProgress: (Long) -> Unit = {},
    ): NivaraResult<Long>
}
