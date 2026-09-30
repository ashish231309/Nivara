package com.nivara.app.data.biometric

import androidx.biometric.BiometricManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nivara.app.NivaraApplication
import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.valueOrNull
import com.nivara.app.domain.credential.CredentialStatus
import com.nivara.app.domain.security.BiometricAuthenticationOutcome
import com.nivara.app.domain.security.BiometricAuthenticator
import com.nivara.app.domain.security.BiometricFailure
import com.nivara.app.domain.security.BiometricStatus
import com.nivara.app.domain.security.BiometricUnavailability
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for biometric authentication on a real device.
 *
 * These run against the *implementation the application uses* — the container's authenticator, the
 * real Android Keystore and the real biometric service. Nothing is faked, and nothing here claims
 * more than it checked.
 *
 * Two things are deliberately absent, and they are the reason this file is not the whole story:
 *
 * - **The prompt is never shown.** Android's prompt needs a foreground activity and a person to
 *   present a biometric. Every case below is chosen so that the authenticator answers *before* it
 *   would ask, and the cases that need a prompt are skipped rather than faked.
 * - **No test drives a successful authentication.** Enabling biometric unlock, authenticating
 *   successfully, invalidating the key by changing the device's enrolment and turning the feature
 *   off again remain a manual pass on a device with an enrolled biometric. The state machine and
 *   the record format are covered by the JVM suite; the platform's part of the flow is covered by
 *   these tests only up to the point where a human has to touch the sensor.
 *
 * Run with `./gradlew :app:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class AndroidBiometricAuthenticatorTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val application = context.applicationContext as NivaraApplication

    private val authenticator: BiometricAuthenticator = application.container.biometricAuthenticator

    /**
     * The device's own capability report, straight from the platform API, before Nivara's mapping.
     *
     * Read here rather than through Nivara's own code so a test cannot pass by agreeing with a
     * mistake in the mapping.
     */
    private val capabilityCode: Int = BiometricManager.from(context)
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)

    private val canAuthenticate: Boolean = capabilityCode == BiometricManager.BIOMETRIC_SUCCESS

    @Before
    fun requireTargetApplication() {
        assertEquals(
            "these tests exercise the application's own container",
            "com.nivara.app",
            context.packageName,
        )
    }

    @Test
    fun aDeviceThatCanAuthenticateIsNeverReportedAsUnableTo() {
        assumeTrue("this device cannot perform a strong biometric authentication", canAuthenticate)

        val state = runBlocking { authenticator.state() }

        // No hardware reason may be invented for a device the platform just said yes about, and a
        // capability report is never turned into a delay.
        assertFalse(state.status is BiometricStatus.Unavailable)
        assertEquals(0L, state.retryAfterMillis)
    }

    @Test
    fun aDeviceWithoutHardwareIsReportedAsSuch() {
        assumeTrue(
            "this device reports hardware it does not have",
            capabilityCode == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
        )

        val status = runBlocking { authenticator.state().status }

        assertEquals(BiometricStatus.Unavailable(BiometricUnavailability.NoHardware), status)
    }

    @Test
    fun biometricUnlockCannotBeTurnedOnWithoutAPrimaryCredential() {
        val credentialStatus = runBlocking { application.container.credentialManager.status().valueOrNull() }
        assumeTrue("a credential is already configured on this device", credentialStatus is CredentialStatus.NotConfigured)

        val result = runBlocking { authenticator.enable() }

        // There is no biometric-only account state: without the credential the request fails, and
        // nothing is enabled afterwards.
        assertTrue("expected a typed failure", result is NivaraResult.Failure)
        assertEquals(BiometricFailure.PrimaryCredentialRequired, (result as NivaraResult.Failure).error)

        val status = runBlocking { authenticator.state().status }
        assertFalse("biometrics must not be on without a credential", status is BiometricStatus.Enabled)
    }

    @Test
    fun authenticatingWithNothingEnabledReportsThatNothingIsEnabled() {
        val status = runBlocking { authenticator.state().status }
        val delay = runBlocking { authenticator.state().retryAfterMillis }
        assumeTrue("biometric unlock is already set up on this device", status is BiometricStatus.Disabled)
        assumeTrue("Nivara is currently delaying attempts on this device", delay == 0L)

        // No prompt is reached: with nothing stored, the authenticator answers from state, which is
        // the behaviour that keeps a throttled or unconfigured path away from the sensor.
        assertEquals(BiometricAuthenticationOutcome.NotEnabled, runBlocking { authenticator.authenticate() })
    }

    @Test
    fun anInvalidatedConfigurationIsReportedRatherThanRecreated() {
        val status = runBlocking { authenticator.state().status }
        val delay = runBlocking { authenticator.state().retryAfterMillis }
        assumeTrue("no invalidated configuration on this device", status is BiometricStatus.Invalidated)
        assumeTrue("Nivara is currently delaying attempts on this device", delay == 0L)

        // The key is dead: the answer is that the configuration must be set up again, and the
        // stored record is left exactly as it is rather than being replaced behind the user's back.
        assertEquals(BiometricAuthenticationOutcome.Invalidated, runBlocking { authenticator.authenticate() })
        assertEquals(BiometricStatus.Invalidated, runBlocking { authenticator.state().status })
    }

    @Test
    fun turningOffSomethingThatWasNeverOnIsRefused() {
        val status = runBlocking { authenticator.state().status }
        assumeTrue("biometric unlock is set up on this device", status is BiometricStatus.Disabled)

        val result = runBlocking { authenticator.disable() }

        assertTrue("expected a typed failure", result is NivaraResult.Failure)
        assertEquals(BiometricFailure.NotEnabled, (result as NivaraResult.Failure).error)
        assertEquals(BiometricStatus.Disabled, runBlocking { authenticator.state().status })
    }
}
