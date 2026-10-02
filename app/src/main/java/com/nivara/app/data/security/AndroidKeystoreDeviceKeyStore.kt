package com.nivara.app.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.nivaraRunCatching
import com.nivara.app.domain.security.CryptographicFailure
import com.nivara.app.domain.security.DeviceKeyConfig
import com.nivara.app.domain.security.DeviceKeyStore
import com.nivara.app.domain.security.EncryptionKey
import java.security.InvalidAlgorithmParameterException
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.NoSuchAlgorithmException
import java.security.NoSuchProviderException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [DeviceKeyStore] implemented on the Android Keystore.
 *
 * Keys are generated as AES-256-GCM keys with randomised encryption required, capable of both
 * encryption and decryption. On devices with a hardware-backed keymaster the material stays in the
 * secure element and never enters this process; on devices without one the platform still keeps
 * the key out of the app's own storage. Nivara does not *require* hardware backing at this stage,
 * because some API 28 devices and emulators do not provide it — a deliberate, documented choice
 * rather than a silent downgrade (see `docs/crypto/README.md`).
 *
 * StrongBox is not requested: it is only available on some API 28 hardware, and silently falling
 * back from it to a trusted-environment key would make the guarantee unpredictable. It stays an
 * explicit future decision.
 *
 * Every method runs on [Dispatchers.IO]: all of them cross into the platform key store.
 */
internal class AndroidKeystoreDeviceKeyStore : DeviceKeyStore {

    override suspend fun exists(alias: String): NivaraResult<Boolean> =
        withContext(Dispatchers.IO) {
            nivaraRunCatching { withKeyStore { keyStore -> keyStore.containsAlias(alias) } }
        }

    override suspend fun getOrCreateKey(
        alias: String,
        config: DeviceKeyConfig,
    ): NivaraResult<EncryptionKey> = withContext(Dispatchers.IO) {
        nivaraRunCatching {
            withKeyStore { keyStore ->
                // Never replace an existing key: data encrypted under it would become unreadable.
                if (!keyStore.containsAlias(alias)) {
                    generateKey(alias = alias, config = config)
                }
            }
            EncryptionKey.DeviceProtected(alias = alias)
        }
    }

    override suspend fun retrieveKey(alias: String): NivaraResult<EncryptionKey> =
        withContext(Dispatchers.IO) {
            nivaraRunCatching {
                withKeyStore { keyStore ->
                    if (!keyStore.containsAlias(alias)) {
                        throw CryptographicFailure.KeyUnavailable
                    }
                    try {
                        // Loading proves the platform can still produce the key. A key that was
                        // invalidated raises an unrecoverable-key failure here.
                        keyStore.getKey(alias, null) ?: throw CryptographicFailure.KeyUnavailable
                    } catch (unrecoverable: UnrecoverableKeyException) {
                        throw CryptographicFailure.KeyInvalidated
                    }
                }
                EncryptionKey.DeviceProtected(alias = alias)
            }
        }

    override suspend fun isKeyUsable(alias: String): NivaraResult<Boolean> =
        withContext(Dispatchers.IO) {
            nivaraRunCatching {
                withKeyStore { keyStore ->
                    if (!keyStore.containsAlias(alias)) {
                        false
                    } else {
                        val key = try {
                            keyStore.getKey(alias, null) as? SecretKey
                        } catch (unrecoverable: UnrecoverableKeyException) {
                            null
                        }
                        key != null && canBeUsed(key)
                    }
                }
            }
        }

    override suspend fun deleteKey(alias: String): NivaraResult<Unit> =
        withContext(Dispatchers.IO) {
            nivaraRunCatching { withKeyStore { keyStore -> keyStore.deleteEntry(alias) } }
        }

    /**
     * Reports whether the platform still allows operations with [key].
     *
     * An invalidated key is not removed from the store — it keeps its alias but fails every
     * operation — so existence alone is not enough. Initialising a cipher is the supported way to
     * detect that.
     */
    private fun canBeUsed(key: SecretKey): Boolean = try {
        Cipher.getInstance(EncryptedEnvelope.TRANSFORMATION).init(Cipher.ENCRYPT_MODE, key)
        true
    } catch (invalidated: KeyPermanentlyInvalidatedException) {
        false
    } catch (notAuthenticated: UserNotAuthenticatedException) {
        // The key is intact; it simply requires the user to authenticate before use. That is a
        // property of the key, not an invalidation, so it stays usable.
        true
    } catch (invalidKey: InvalidKeyException) {
        false
    } catch (unavailable: Exception) {
        false
    }

    private fun generateKey(alias: String, config: DeviceKeyConfig) {
        val keySpec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(EncryptedEnvelope.KEY_SIZE_BITS)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(config.userAuthenticationRequired)
            .build()

        try {
            KeyGenerator.getInstance(AndroidKeystore.KEY_ALGORITHM, AndroidKeystore.PROVIDER)
                .apply { init(keySpec) }
                .generateKey()
        } catch (invalidSpec: InvalidAlgorithmParameterException) {
            throw CryptographicFailure.KeyGenerationFailed
        } catch (missingProvider: NoSuchProviderException) {
            throw CryptographicFailure.KeyStoreUnavailable
        } catch (unsupportedAlgorithm: NoSuchAlgorithmException) {
            throw CryptographicFailure.KeyGenerationFailed
        } catch (providerFailure: ProviderException) {
            // The platform reports most generation problems (missing secure hardware, unusable
            // alias, unsupported combination) as a provider exception.
            throw CryptographicFailure.KeyGenerationFailed
        }
    }

    private inline fun <T> withKeyStore(block: (KeyStore) -> T): T = try {
        block(AndroidKeystore.load())
    } catch (unavailable: KeyStoreException) {
        throw CryptographicFailure.KeyStoreUnavailable
    }
}
