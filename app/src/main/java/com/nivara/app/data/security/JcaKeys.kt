package com.nivara.app.data.security

import com.nivara.app.domain.security.CryptographicFailure
import com.nivara.app.domain.security.EncryptionKey
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * The one place that knows how to reach the Android Keystore.
 *
 * Nothing above this file handles a `KeyStore`, an entry or an alias: the rest of Nivara sees
 * [EncryptionKey], which carries either in-process material or a keystore alias.
 */
internal object AndroidKeystore {

    const val PROVIDER: String = "AndroidKeyStore"
    const val KEY_ALGORITHM: String = "AES"

    /** Opens the platform key store. */
    fun load(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    /**
     * Returns the key stored under [alias].
     *
     * The returned key cannot be exported: `getEncoded()` is null for keys held by the platform,
     * which is why callers must use them through `Cipher`/`Mac` rather than reading their bytes.
     */
    fun secretKeyFor(alias: String): SecretKey {
        val keyStore = try {
            load()
        } catch (unavailable: Exception) {
            throw CryptographicFailure.KeyStoreUnavailable
        }
        // The lookup is wrapped on its own so that the typed failure below is not swallowed by the
        // platform-failure branch: a missing key must be reported as missing, not as an
        // unreachable key store.
        val key = try {
            keyStore.getKey(alias, null) as? SecretKey
        } catch (unrecoverable: UnrecoverableKeyException) {
            // The platform destroyed or invalidated the key: for example the user removed the
            // screen lock, or enrolled a new biometric that invalidates existing keys.
            throw CryptographicFailure.KeyInvalidated
        } catch (unavailable: Exception) {
            throw CryptographicFailure.KeyStoreUnavailable
        }
        return key ?: throw CryptographicFailure.KeyUnavailable
    }
}

/**
 * Converts a domain key into the JCA key a cipher needs.
 *
 * A device-protected key is resolved from the platform key store here and never becomes an
 * in-memory key; an in-process key must be a 256-bit AES key, so a wrongly sized or already
 * cleared buffer is refused instead of being padded or truncated to fit.
 */
internal fun EncryptionKey.asSecretKey(): SecretKey = when (this) {
    is EncryptionKey.InProcess -> {
        if (material.isCleared || material.size != EncryptedEnvelope.KEY_SIZE_BYTES) {
            throw CryptographicFailure.InvalidKey
        }
        SecretKeySpec(material.unsafeByteArray(), AndroidKeystore.KEY_ALGORITHM)
    }

    is EncryptionKey.DeviceProtected -> AndroidKeystore.secretKeyFor(alias)
}

/**
 * Copies the raw material of a key so it can be wrapped by another key.
 *
 * Only in-process keys can be exported. A device-protected key must never be copied out of the
 * platform key store: for those, wrapping is performed by the platform itself (see
 * [NivaraContentKeyWrapper]). The caller owns the returned array and must clear it.
 */
internal fun EncryptionKey.exportKeyMaterial(): ByteArray = when (this) {
    is EncryptionKey.InProcess -> {
        if (material.isCleared || material.size != EncryptedEnvelope.KEY_SIZE_BYTES) {
            throw CryptographicFailure.InvalidKey
        }
        material.copyBytes()
    }

    is EncryptionKey.DeviceProtected -> throw CryptographicFailure.InvalidKey
}
