package com.nivara.app.data.security

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.fold
import com.nivara.app.core.common.nivaraRunCatching
import com.nivara.app.domain.security.ContentKeyWrapper
import com.nivara.app.domain.security.CryptographicFailure
import com.nivara.app.domain.security.EncryptionContext
import com.nivara.app.domain.security.EncryptionKey
import com.nivara.app.domain.security.EncryptionService
import com.nivara.app.domain.security.SecureRandomGenerator
import com.nivara.app.domain.security.SensitiveBytes

/**
 * Wraps content keys with either kind of key Nivara has.
 *
 * Two schemes exist because the two kinds of wrapping key have different capabilities:
 *
 * - **Device-protected wrapping key** — the material never leaves the platform key store, so it
 *   cannot drive a KDF here. The content key is wrapped by an ordinary AES-256-GCM envelope
 *   ([EncryptedEnvelope]) created inside the platform, bound to [EncryptionContext.KeyWrapping].
 * - **In-process wrapping key** — for example a key derived from a credential, or a recovery key.
 *   The material is available, so the content key is sealed with [SealedKeyContainer]
 *   (HKDF-SHA-256 subkeys, XOR keystream, HMAC-SHA-256 tag, fresh random nonce).
 *
 * Both schemes start with a four-byte magic, so [unwrap] can tell them apart from the blob itself
 * and never has to be told which one was used. Callers hold an opaque blob; the content key never
 * appears in it in the clear.
 */
internal class NivaraContentKeyWrapper(
    private val random: SecureRandomGenerator,
    private val encryptionService: EncryptionService,
) : ContentKeyWrapper {

    override suspend fun wrap(
        contentKey: EncryptionKey,
        wrappingKey: EncryptionKey,
        context: EncryptionContext,
    ): NivaraResult<ByteArray> =
        if (wrappingKey.isDeviceProtected) {
            wrapWithPlatformKey(contentKey, wrappingKey, context)
        } else {
            wrapWithInProcessKey(contentKey, wrappingKey)
        }

    override suspend fun unwrap(
        wrappedKey: ByteArray,
        wrappingKey: EncryptionKey,
        context: EncryptionContext,
    ): NivaraResult<EncryptionKey> =
        if (EncryptedEnvelope.hasMagic(wrappedKey)) {
            // Produced by the platform-key scheme: it is an ordinary envelope.
            encryptionService.decrypt(wrappedKey, wrappingKey, context).fold(
                onSuccess = { material -> NivaraResult.Success(contentKeyOf(material)) },
                onFailure = { failure -> failure },
            )
        } else {
            unwrapFromContainer(wrappedKey, wrappingKey, context)
        }

    /**
     * The content key is never exported from the device for this scheme, and the platform key
     * stream is cleared by the encryption service once the envelope exists.
     */
    private suspend fun wrapWithPlatformKey(
        contentKey: EncryptionKey,
        wrappingKey: EncryptionKey,
        context: EncryptionContext,
    ): NivaraResult<ByteArray> {
        val contentKeyMaterial = try {
            contentKey.exportKeyMaterial()
        } catch (failure: CryptographicFailure) {
            return NivaraResult.Failure(failure)
        }
        return encryptionService.encrypt(
            plaintext = contentKeyMaterial,
            key = wrappingKey,
            context = context,
            clearPlaintextAfterUse = true,
        )
    }

    private fun wrapWithInProcessKey(
        contentKey: EncryptionKey,
        wrappingKey: EncryptionKey,
    ): NivaraResult<ByteArray> = nivaraRunCatching {
        val contentKeyMaterial = contentKey.exportKeyMaterial()
        try {
            val wrappingKeyMaterial = wrappingKey.exportKeyMaterial()
            try {
                val nonce = random.nextByteArray(SealedKeyContainer.NONCE_LENGTH)
                SealedKeyContainer.seal(
                    format = SealedKeyFormat.KeyWrapping,
                    wrappingKey = wrappingKeyMaterial,
                    contentKey = contentKeyMaterial,
                    nonce = nonce,
                )
            } finally {
                wrappingKeyMaterial.fill(0)
            }
        } finally {
            contentKeyMaterial.fill(0)
        }
    }

    private fun unwrapFromContainer(
        wrappedKey: ByteArray,
        wrappingKey: EncryptionKey,
        context: EncryptionContext,
    ): NivaraResult<EncryptionKey> = nivaraRunCatching {
        if (context != SealedKeyFormat.KeyWrapping.context) {
            // Never ignore the caller's context: a key sealed for another purpose must not be
            // accepted here just because the container happens to parse.
            throw CryptographicFailure.ContextMismatch
        }
        val wrappingKeyMaterial = wrappingKey.exportKeyMaterial()
        try {
            contentKeyOf(
                SealedKeyContainer.open(
                    format = SealedKeyFormat.KeyWrapping,
                    wrappingKey = wrappingKeyMaterial,
                    container = wrappedKey,
                ),
            )
        } finally {
            wrappingKeyMaterial.fill(0)
        }
    }

    private fun contentKeyOf(material: ByteArray): EncryptionKey =
        EncryptionKey.fromRawBytes(
            material = SensitiveBytes.wrap(material),
            label = UNWRAPPED_CONTENT_KEY_LABEL,
        )

    private companion object {
        const val UNWRAPPED_CONTENT_KEY_LABEL = "unwrapped-content-key"
    }
}
