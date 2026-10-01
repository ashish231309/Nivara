package com.nivara.app.di

import android.content.Context
import com.nivara.app.data.credential.FileAttemptStore
import com.nivara.app.data.credential.FileCredentialRecordStore
import com.nivara.app.data.credential.NivaraCredentialManager
import com.nivara.app.data.credential.PersistedAttemptTracker
import com.nivara.app.data.biometric.AndroidBiometricAuthenticator
import com.nivara.app.data.biometric.BiometricTokenStore
import com.nivara.app.data.app.AndroidApplicationIconLoader
import com.nivara.app.data.app.AndroidApplicationRepository
import com.nivara.app.data.credential.SystemTimeProvider
import com.nivara.app.data.applock.AndroidAppLockProtectionRunner
import com.nivara.app.data.applock.AndroidForegroundApplicationDetector
import com.nivara.app.data.applock.FileProtectedApplicationRepository
import com.nivara.app.data.applock.NivaraAppLockMonitor
import com.nivara.app.data.permissions.AndroidOverlayCapabilityRepository
import com.nivara.app.data.permissions.AndroidUsageAccessRepository
import com.nivara.app.data.session.InMemorySessionManager
import com.nivara.app.domain.app.ApplicationRepository
import com.nivara.app.domain.applock.AppLockMonitor
import com.nivara.app.domain.applock.AppLockOverlayHost
import com.nivara.app.domain.applock.AppLockOverlayPresenter
import com.nivara.app.domain.applock.AppLockProtectionRunner
import com.nivara.app.domain.applock.ForegroundApplicationDetector
import com.nivara.app.domain.applock.ProtectedApplicationRepository
import com.nivara.app.domain.applock.ProtectionDecisionEngine
import com.nivara.app.domain.permissions.OverlayCapabilityRepository
import com.nivara.app.domain.permissions.UsageAccessRepository
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
import com.nivara.app.domain.security.SessionManager
import com.nivara.app.domain.security.SessionTimeoutPolicy
import com.nivara.app.domain.security.SecureRandomGenerator
import com.nivara.app.ui.applock.management.ApplicationIconLoader
import com.nivara.app.ui.applock.overlay.AppLockSurfaceController
import com.nivara.app.ui.applock.overlay.WindowManagerOverlaySurface
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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

    /**
     * The single authentication session gate, shared by the whole process.
     *
     * One instance per process on purpose: a session is an authorization state, and two of them
     * would be two answers to the same question. It holds nothing on disk, so ending the process
     * ends the session.
     */
    val sessionManager: SessionManager

    /**
     * Applications the user can launch on this device, discovered from the launcher entries.
     *
     * The list is built on demand and kept in memory only: it is never cached, persisted or sent
     * anywhere.
     */
    val applicationRepository: ApplicationRepository

    /**
     * Android's Usage Access capability for Nivara: whether the grant exists, and the settings
     * screen where the user changes it.
     *
     * Nothing here requests the grant; Usage Access is not a runtime permission.
     */
    val usageAccessRepository: UsageAccessRepository

    /**
     * Which applications the user asked Nivara to protect.
     *
     * Configuration only: it says what to protect, never whether Nivara is unlocked.
     */
    val protectedApplicationRepository: ProtectedApplicationRepository

    /**
     * Reports the application currently in the foreground.
     *
     * Exposed for the monitor, which owns the loop; nothing in the UI layer queries it directly.
     */
    val foregroundApplicationDetector: ForegroundApplicationDetector

    /**
     * The single App Lock monitoring loop, shared by the whole process.
     *
     * One instance on purpose, like the session gate: two monitors would be two answers to the same
     * question. Nothing starts it during application start-up — the component that needs it starts
     * it, and stops it again.
     */
    val appLockMonitor: AppLockMonitor

    /**
     * Android's overlay capability for Nivara: whether the protection surface may be drawn above
     * another application, and the settings screen where the user changes it.
     *
     * Detection and presentation are separate capabilities, and this is the second one. Nothing
     * here draws anything or requests the grant; the component that owns the window does the first,
     * and only the user does the second.
     */
    val overlayCapabilityRepository: OverlayCapabilityRepository

    /**
     * Decides whether the protection surface should be on screen, and routes authentication.
     *
     * Exposed so the composition root can wire the surface to it — and so nothing else grows a
     * second answer to the same question.
     */
    val appLockOverlayPresenter: AppLockOverlayPresenter

    /**
     * The component that owns the protection window.
     *
     * Started and stopped by the same platform component that owns detection, and implemented by the
     * presentation layer: the domain defines when a surface is needed, the UI knows how to draw one.
     */
    val appLockOverlayHost: AppLockOverlayHost

    /**
     * Supplies the image a management row draws for an application.
     *
     * An icon is presentation: the platform produces it, the screen draws it, and nothing about
     * protection depends on it. The container chooses the platform implementation; the presentation
     * layer only asks for a bitmap, which is why the contract lives with the screen that uses it
     * and this property is the only place the two meet.
     */
    val applicationIconLoader: ApplicationIconLoader

    /**
     * Turns protection on and off for the device.
     *
     * A screen asks for protection through this contract; how it keeps running while Nivara is not
     * on screen is the implementation's business.
     */
    val appLockProtectionRunner: AppLockProtectionRunner
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

    override val sessionManager: SessionManager by lazy {
        // Held in memory for the life of the process, and built on the same clock as every other
        // timed rule in the application. Nothing here reaches storage.
        InMemorySessionManager(
            timeProvider = timeProvider,
            policy = SessionTimeoutPolicy.Default,
        )
    }

    override val applicationRepository: ApplicationRepository by lazy {
        AndroidApplicationRepository(applicationContext)
    }

    override val usageAccessRepository: UsageAccessRepository by lazy {
        AndroidUsageAccessRepository(applicationContext)
    }

    override val protectedApplicationRepository: ProtectedApplicationRepository by lazy {
        FileProtectedApplicationRepository(File(appLockDirectory, PROTECTED_APPLICATIONS_FILE))
    }

    override val foregroundApplicationDetector: ForegroundApplicationDetector by lazy {
        AndroidForegroundApplicationDetector(
            context = applicationContext,
            timeProvider = timeProvider,
        )
    }

    override val overlayCapabilityRepository: OverlayCapabilityRepository by lazy {
        AndroidOverlayCapabilityRepository(applicationContext)
    }

    override val appLockOverlayPresenter: AppLockOverlayPresenter by lazy {
        AppLockOverlayPresenter(
            monitor = appLockMonitor,
            sessionManager = sessionManager,
            credentialManager = credentialManager,
            biometrics = biometricAuthenticator,
            overlayCapability = overlayCapabilityRepository,
            // Its own scope, not the lifetime of a screen: the surface has to work while no screen
            // of Nivara's is in front.
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }

    override val appLockOverlayHost: AppLockOverlayHost by lazy {
        AppLockSurfaceController(
            surface = WindowManagerOverlaySurface(applicationContext, appLockOverlayPresenter),
            presenter = appLockOverlayPresenter,
            // Window operations belong on the main thread, and so does the platform's callback that
            // reports a window going away.
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }

    override val appLockProtectionRunner: AppLockProtectionRunner by lazy {
        AndroidAppLockProtectionRunner(applicationContext)
    }

    override val applicationIconLoader: ApplicationIconLoader by lazy {
        AndroidApplicationIconLoader(applicationContext)
    }

    override val appLockMonitor: AppLockMonitor by lazy {
        NivaraAppLockMonitor(
            detector = foregroundApplicationDetector,
            protectedApplications = protectedApplicationRepository,
            usageAccess = usageAccessRepository,
            sessionManager = sessionManager,
            // Nivara's own package name is injected here, from the platform, so the rule that
            // excludes Nivara from protection exists in exactly one place.
            decisions = ProtectionDecisionEngine(nivaraPackageName = applicationContext.packageName),
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

    /**
     * Directory holding App Lock's configuration.
     *
     * Created on first write, inside the application's private storage, and excluded from backup
     * with the rest of the application's data. It holds package names and nothing else — no
     * session state, no unlock flag and no authentication material.
     */
    private val appLockDirectory: File by lazy { File(applicationContext.filesDir, APP_LOCK_DIRECTORY) }

    /** One wall clock for every throttling rule in the application. */
    private val timeProvider: TimeProvider by lazy { SystemTimeProvider() }

    private companion object {
        const val SECURITY_DIRECTORY = "security"
        const val CREDENTIAL_RECORD_FILE = "credential.nvc"
        const val ATTEMPT_STATE_FILE = "credential-attempts.nva"
        const val BIOMETRIC_TOKEN_FILE = "biometric.token"
        const val BIOMETRIC_ATTEMPT_FILE = "biometric-attempts.nva"
        const val APP_LOCK_DIRECTORY = "applock"
        const val PROTECTED_APPLICATIONS_FILE = "protected-applications.nvl"
    }
}
