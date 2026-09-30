package com.nivara.app.data.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.nivaraRunCatching
import com.nivara.app.domain.security.CryptographicFailure
import java.security.InvalidAlgorithmParameterException
import java.security.InvalidKeyException
import java.security.KeyStoreException
import java.security.NoSuchAlgorithmException
import java.security.NoSuchPaddingException
import java.security.NoSuchProviderException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Android Keystore key that gates biometric unlock.
 *
 * The key never leaves the platform: it is created as an AES-256-GCM key that requires user
 * authentication *for every use*, is only authorized by a **strong biometric**, and is invalidated
 * when the device's biometric enrolment changes. Nivara therefore never holds biometric-bound key
 * material, never writes it anywhere, and cannot export it — [encryptionCipher] and
 * [decryptionCipher] return a platform cipher that performs the operation inside the key store.
 *
 * The key protects one thing: a random token that Nivara stores next to it. That token is not used
 * as an encryption key and nothing else is derived from it — it exists so that a successful
 * authentication has a cryptographic consequence (the cipher can only complete after the platform
 * accepted the user) and so that a key invalidated by a new enrolment is detectable before the
 * user is asked for anything.
 *
 * Replacing the key is safe by construction: the only data it protects is the token record, which
 * is rewritten together with the key. [create] therefore removes a leftover entry before
 * generating, so an interrupted setup cannot wedge biometric unlock.
 */
internal class AndroidBiometricKeyStore {

    /** Creates the biometric key, replacing any entry left over from an interrupted setup. */
    suspend fun create(alias: String): NivaraResult<Unit> = guard {
        val keyStore = AndroidKeystore.load()
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }
        generate(alias)
    }

    /** Removes the key. Succeeds when nothing is stored under [alias]. */
    suspend fun delete(alias: String): NivaraResult<Unit> = guard {
        AndroidKeystore.load().deleteEntry(alias)
    }

    /**
     * Reports whether the key under [alias] exists *and* can still be used.
     *
     * `false` covers "no such key" and "the platform permanently invalidated it". Detecting the
     * second case is why this initialises a cipher instead of only looking the entry up: an
     * invalidated key keeps its alias but fails every operation. A key that merely awaits
     * authentication is usable — that is what the prompt is for.
     *
     * The result is a [NivaraResult] so an unreachable key store is distinguishable from a missing
     * key; callers that only need "usable or not" treat a failure as not usable, which fails
     * closed and leaves the primary credential as the way in.
     */
    suspend fun isUsable(alias: String): NivaraResult<Boolean> = guard {
        val keyStore = AndroidKeystore.load()
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

    /**
     * Prepares the single encryption the platform will authorize with a biometric prompt.
     *
     * The returned cipher must be handed to the prompt as its crypto object and used only after the
     * platform reports success; using it before that raises an authentication error from the key
     * store, which is exactly the guarantee Nivara relies on.
     */
    suspend fun encryptionCipher(alias: String): NivaraResult<Cipher> = guard {
        Cipher.getInstance(EncryptedEnvelope.TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, AndroidKeystore.secretKeyFor(alias))
        }
    }

    /** Prepares the decryption of a previously stored token, to be authorized by the prompt. */
    suspend fun decryptionCipher(alias: String, iv: ByteArray): NivaraResult<Cipher> = guard {
        val parameters = GCMParameterSpec(EncryptedEnvelope.TAG_LENGTH_BITS, iv)
        Cipher.getInstance(EncryptedEnvelope.TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, AndroidKeystore.secretKeyFor(alias), parameters)
        }
    }

    private suspend fun <T> guard(block: () -> T): NivaraResult<T> = withContext(Dispatchers.IO) {
        nivaraRunCatching(block).asKeyFailure()
    }

    /**
     * Reports whether the platform still allows operations with [key].
     *
     * A key awaiting user authentication is usable; only a permanent invalidation or an unusable
     * key reports `false`.
     */
    private fun canBeUsed(key: SecretKey): Boolean = try {
        Cipher.getInstance(EncryptedEnvelope.TRANSFORMATION).init(Cipher.ENCRYPT_MODE, key)
        true
    } catch (invalidated: KeyPermanentlyInvalidatedException) {
        false
    } catch (notAuthenticated: UserNotAuthenticatedException) {
        true
    } catch (invalidKey: InvalidKeyException) {
        false
    } catch (unavailable: Exception) {
        false
    }

    /**
     * Generates the biometric key.
     *
     * The parameters are the security contract of this file:
     *
     * - `setUserAuthenticationRequired(true)` with an auth-per-use configuration — a zero-second
     *   window on API 30+, the equivalent per-use duration below it — so the key can never be used
     *   without the platform authorizing that single operation through a prompt.
     * - `AUTH_BIOMETRIC_STRONG` on API 30+: only a biometric the platform classifies as strong
     *   (Class 3) may authorize the key. Weak biometrics are still reported as the reason biometric
     *   unlock is unavailable rather than silently weakening this.
     * - `setInvalidatedByBiometricEnrollment(true)`: enrolling a new biometric destroys the key.
     *   That is the platform protecting the user, and Nivara handles it by falling back — it never
     *   recreates the key silently.
     */
    private fun generate(alias: String) {
        val specification = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(EncryptedEnvelope.KEY_SIZE_BITS)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(
                        BIOMETRIC_PER_USE_TIMEOUT_SECONDS,
                        KeyProperties.AUTH_BIOMETRIC_STRONG,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(BIOMETRIC_PER_USE_SECONDS)
                }
            }
            .build()

        try {
            KeyGenerator.getInstance(AndroidKeystore.KEY_ALGORITHM, AndroidKeystore.PROVIDER)
                .apply { init(specification) }
                .generateKey()
        } catch (invalidSpecification: InvalidAlgorithmParameterException) {
            throw CryptographicFailure.KeyGenerationFailed
        } catch (missingProvider: NoSuchProviderException) {
            throw CryptographicFailure.KeyStoreUnavailable
        } catch (unsupportedAlgorithm: NoSuchAlgorithmException) {
            throw CryptographicFailure.KeyGenerationFailed
        } catch (providerFailure: ProviderException) {
            throw CryptographicFailure.KeyGenerationFailed
        }
    }

    /**
     * Replaces platform exceptions with the typed failures the rest of Nivara handles.
     *
     * Nothing here reports *why* the platform refused beyond the cases the caller can act on, and
     * no platform message is kept.
     */
    private fun <T> NivaraResult<T>.asKeyFailure(): NivaraResult<T> {
        // Generic payloads are erased, so the failure branch is taken by identity rather than by
        // matching on a type argument.
        val failure = this as? NivaraResult.Failure ?: return this
        return NivaraResult.Failure(
            when (val error = failure.error) {
                is CryptographicFailure -> error
                // An invalidated key and a key that refuses to be prepared both mean the stored
                // configuration can no longer be used, which is what the caller needs to know.
                is KeyPermanentlyInvalidatedException -> CryptographicFailure.KeyInvalidated
                is UserNotAuthenticatedException -> CryptographicFailure.KeyInvalidated
                is KeyStoreException,
                is NoSuchProviderException,
                is ProviderException,
                is NoSuchAlgorithmException,
                is NoSuchPaddingException,
                -> CryptographicFailure.KeyStoreUnavailable
                is InvalidAlgorithmParameterException -> CryptographicFailure.KeyGenerationFailed
                is InvalidKeyException -> CryptographicFailure.KeyUnavailable
                else -> CryptographicFailure.KeyStoreUnavailable
            },
        )
    }

    private companion object {
        /** Auth-per-use on API 30+: the key is authorized for exactly one operation. */
        const val BIOMETRIC_PER_USE_TIMEOUT_SECONDS: Int = 0

        /** The pre-API-30 way of saying the same thing: authentication is required for every use. */
        const val BIOMETRIC_PER_USE_SECONDS: Int = -1
    }
}
