package com.nivara.app.di

import android.content.Context
import com.nivara.app.data.credential.FileAttemptStore
import com.nivara.app.data.credential.FileCredentialRecordStore
import com.nivara.app.data.credential.NivaraCredentialManager
import com.nivara.app.data.credential.PersistedAttemptTracker
import com.nivara.app.data.biometric.AndroidBiometricAuthenticator
import com.nivara.app.data.biometric.BiometricTokenStore
import com.nivara.app.data.credential.SystemTimeProvider
import com.nivara.app.data.security.AndroidBiometricKeyStore
import com.nivara.app.data.security.AndroidDeviceSecurityProvider
import com.nivara.app.data.security.AndroidKeystoreDeviceKeyStore
import com.nivara.app.data.security.HkdfRecoveryKeyEnvelopeService
import com.nivara.app.data.security.JcaEncryptionService
import com.nivara.app.data.security.NivaraContentKeyWrapper
import com.nivara.app.data.security.Pbkdf2KeyDerivationService
import com.nivara.app.domain.credential.CredentialManager
import com.nivara.app.domain.credential.TimeProvider
import com.nivara.app.domain.security.BiometricAuthenticator
import com.nivara.app.domain.security.BiometricThrottlePolicy
import com.nivara.app.domain.security.ContentKeyWrapper
import com.nivara.app.domain.security.DeviceKeyStore
import com.nivara.app.domain.security.DeviceSecurityProvider
import com.nivara.app.domain.security.EncryptionService
import com.nivara.app.domain.security.KeyDerivationService
import com.nivara.app.domain.security.RecoveryKeyEnvelopeService
import com.nivara.app.domain.security.SecureRandomGenerator
import java.io.File

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

    /** Enrolling, verifying and changing the primary authentication credential. */
    val credentialManager: CredentialManager

    /**
     * Android biometric authentication, as a secondary path beside the primary credential.
     *
     * Biometric unlock can only be enabled once a primary credential exists, and it can never
     * replace one.
     */
    val biometricAuthenticator: BiometricAuthenticator
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

    override val credentialManager: CredentialManager by lazy {
        NivaraCredentialManager(
            keyDerivationService = keyDerivationService,
            store = FileCredentialRecordStore(File(securityDirectory, CREDENTIAL_RECORD_FILE)),
            attemptTracker = PersistedAttemptTracker(
                store = FileAttemptStore(File(securityDirectory, ATTEMPT_STATE_FILE)),
                timeProvider = timeProvider,
            ),
        )
    }

    override val biometricAuthenticator: BiometricAuthenticator by lazy {
        AndroidBiometricAuthenticator(
            context = applicationContext,
            credentialManager = credentialManager,
            keyStore = AndroidBiometricKeyStore(),
            tokenStore = BiometricTokenStore(File(securityDirectory, BIOMETRIC_TOKEN_FILE)),
            // A separate counter file, so a biometric failure can never influence the credential's
            // own throttling.
            attempts = PersistedAttemptTracker(
                store = FileAttemptStore(File(securityDirectory, BIOMETRIC_ATTEMPT_FILE)),
                timeProvider = timeProvider,
                policy = BiometricThrottlePolicy.Default,
            ),
            random = secureRandomGenerator,
            timeProvider = timeProvider,
        )
    }

    /**
     * Directory holding the credential record and the attempt counters.
     *
     * Created on first write. It sits inside the application's private storage, which other
     * applications cannot read, and it is excluded from backup and device transfer along with
     * the rest of the application's data.
     */
    private val securityDirectory: File by lazy { File(applicationContext.filesDir, SECURITY_DIRECTORY) }

    /** One wall clock for every throttling rule in the application. */
    private val timeProvider: TimeProvider by lazy { SystemTimeProvider() }

    private companion object {
        const val SECURITY_DIRECTORY = "security"
        const val CREDENTIAL_RECORD_FILE = "credential.nvc"
        const val ATTEMPT_STATE_FILE = "credential-attempts.nva"
        const val BIOMETRIC_TOKEN_FILE = "biometric.token"
        const val BIOMETRIC_ATTEMPT_FILE = "biometric-attempts.nva"
    }
}
