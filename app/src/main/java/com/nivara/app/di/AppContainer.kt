package com.nivara.app.di

import android.content.Context
import com.nivara.app.data.security.AndroidDeviceSecurityProvider
import com.nivara.app.data.security.AndroidKeystoreDeviceKeyStore
import com.nivara.app.data.security.HkdfRecoveryKeyEnvelopeService
import com.nivara.app.data.security.JcaEncryptionService
import com.nivara.app.data.security.NivaraContentKeyWrapper
import com.nivara.app.data.security.Pbkdf2KeyDerivationService
import com.nivara.app.domain.security.ContentKeyWrapper
import com.nivara.app.domain.security.DeviceKeyStore
import com.nivara.app.domain.security.DeviceSecurityProvider
import com.nivara.app.domain.security.EncryptionService
import com.nivara.app.domain.security.KeyDerivationService
import com.nivara.app.domain.security.RecoveryKeyEnvelopeService
import com.nivara.app.domain.security.SecureRandomGenerator

/**
 * Application composition root.
 *
 * Interfaces are exposed here and implemented elsewhere, so the UI never depends on a
 * concrete platform implementation. New providers are added as later stages need them.
 */
interface AppContainer {

    /** Read-only view of the device's security posture. */
    val deviceSecurityProvider: DeviceSecurityProvider

    /** The single cryptographically secure random source used for keys, nonces and salts. */
    val secureRandomGenerator: SecureRandomGenerator

    /** Authenticated encryption (AES-256-GCM) for small payloads. */
    val encryptionService: EncryptionService

    /** Credential-based key derivation (PBKDF2-HMAC-SHA-256). */
    val keyDerivationService: KeyDerivationService

    /** Keys that live inside the platform key store. */
    val deviceKeyStore: DeviceKeyStore

    /** Protecting a content key with another key. */
    val contentKeyWrapper: ContentKeyWrapper

    /** Sealing a content key with an independent recovery key. */
    val recoveryKeyEnvelopeService: RecoveryKeyEnvelopeService
}

/**
 * Default [AppContainer] backed by the application context.
 *
 * Implementations are created lazily: nothing is constructed until a caller actually asks for it,
 * which keeps cold start cheap and means the platform key store is not touched during startup.
 */
class DefaultAppContainer(context: Context) : AppContainer {

    private val applicationContext: Context = context.applicationContext

    override val deviceSecurityProvider: DeviceSecurityProvider by lazy {
        AndroidDeviceSecurityProvider(applicationContext)
    }

    override val secureRandomGenerator: SecureRandomGenerator by lazy {
        SecureRandomGenerator()
    }

    override val encryptionService: EncryptionService by lazy {
        JcaEncryptionService(random = secureRandomGenerator)
    }

    override val keyDerivationService: KeyDerivationService by lazy {
        Pbkdf2KeyDerivationService(random = secureRandomGenerator)
    }

    override val deviceKeyStore: DeviceKeyStore by lazy {
        AndroidKeystoreDeviceKeyStore()
    }

    override val contentKeyWrapper: ContentKeyWrapper by lazy {
        NivaraContentKeyWrapper(
            random = secureRandomGenerator,
            encryptionService = encryptionService,
        )
    }

    override val recoveryKeyEnvelopeService: RecoveryKeyEnvelopeService by lazy {
        HkdfRecoveryKeyEnvelopeService(random = secureRandomGenerator)
    }
}
