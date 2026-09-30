package com.nivara.app.data.credential

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.isSuccess
import com.nivara.app.domain.credential.AttemptTracker
import com.nivara.app.domain.credential.AuthenticationOutcome
import com.nivara.app.domain.credential.CredentialFailure
import com.nivara.app.domain.credential.CredentialInput
import com.nivara.app.domain.credential.CredentialManager
import com.nivara.app.domain.credential.CredentialStatus
import com.nivara.app.domain.credential.PrimaryCredentialType
import com.nivara.app.domain.credential.ThrottleState
import com.nivara.app.domain.security.KeyDerivationConfig
import com.nivara.app.domain.security.KeyDerivationService
import com.nivara.app.domain.security.SensitiveBytes
import com.nivara.app.testing.MutableTimeProvider
import com.nivara.app.testing.containsSequence
import com.nivara.app.testing.material
import com.nivara.app.testing.password
import com.nivara.app.testing.pin
import com.nivara.app.testing.pattern
import com.nivara.app.testing.testAttemptStore
import com.nivara.app.testing.testAttemptTracker
import com.nivara.app.testing.testCredentialManager
import com.nivara.app.testing.testKeyDerivationService
import com.nivara.app.testing.testRecordStore
import com.nivara.app.testing.valueOrFail
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests for the credential layer.
 *
 * Real cryptography throughout: the platform's PBKDF2-HMAC-SHA-256, real random salts, the real
 * record format and the real file store. Only the iteration count is reduced (to the lowest the
 * policy permits) and the clock is controlled, so the suite finishes in seconds while still
 * exercising the code that runs on a device. One test uses the production iteration count to
 * prove enrolment really stores the default parameters.
 */
class NivaraCredentialManagerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var timeProvider: MutableTimeProvider
    private lateinit var keyDerivationService: KeyDerivationService
    private lateinit var attemptTracker: AttemptTracker
    private lateinit var recordFile: File
    private lateinit var attemptFile: File

    @Before
    fun setUp() {
        timeProvider = MutableTimeProvider()
        keyDerivationService = testKeyDerivationService()
        recordFile = File(temporaryFolder.root, "credential.nvc")
        attemptFile = File(temporaryFolder.root, "credential-attempts.nva")
        attemptTracker = testAttemptTracker(testAttemptStore(attemptFile), timeProvider)
    }

    /** A manager over the files of this test, at the reduced test cost. */
    private fun manager(
        service: KeyDerivationService = keyDerivationService,
    ): CredentialManager = testCredentialManager(
        keyDerivationService = service,
        store = testRecordStore(recordFile),
        attemptTracker = attemptTracker,
    )

    private fun storedRecord() = testRecordStore(recordFile).load().valueOrFail()

    // ------------------------------------------------------------------ status and selection

    @Test
    fun `nothing is configured before enrolment`() = runTest {
        assertEquals(NivaraResult.Success(CredentialStatus.NotConfigured), manager().status())
    }

    @Test
    fun `a PIN can be selected and enrolled`() = runTest {
        val credentialManager = manager()

        assertTrue(credentialManager.enroll(pin("5731"), pin("5731")).isSuccess)
        assertEquals(
            CredentialStatus.Configured(PrimaryCredentialType.Pin),
            credentialManager.status().valueOrFail(),
        )
    }

    @Test
    fun `a password can be selected and enrolled`() = runTest {
        val credentialManager = manager()

        assertTrue(
            credentialManager.enroll(
                password("correct horse battery"),
                password("correct horse battery"),
            ).isSuccess,
        )
        assertEquals(
            CredentialStatus.Configured(PrimaryCredentialType.Password),
            credentialManager.status().valueOrFail(),
        )
    }

    @Test
    fun `a pattern can be selected and enrolled`() = runTest {
        val credentialManager = manager()

        assertTrue(credentialManager.enroll(pattern(0, 4, 8, 1), pattern(0, 4, 8, 1)).isSuccess)
        assertEquals(
            CredentialStatus.Configured(PrimaryCredentialType.Pattern),
            credentialManager.status().valueOrFail(),
        )
    }

    @Test
    fun `only one credential can be active`() = runTest {
        val credentialManager = manager()
        credentialManager.enroll(pin("5731"), pin("5731"))

        val second = credentialManager.enroll(
            password("correct horse battery"),
            password("correct horse battery"),
        )

        assertTrue(second is NivaraResult.Failure)
        assertEquals(CredentialFailure.AlreadyConfigured, (second as NivaraResult.Failure).error)
        assertEquals(
            CredentialStatus.Configured(PrimaryCredentialType.Pin),
            credentialManager.status().valueOrFail(),
        )
        // One record of the fixed size, not two.
        assertEquals(CredentialRecordCodec.MINIMUM_BYTES, recordFile.readBytes().size)
    }

    // ------------------------------------------------------------------ verification

    @Test
    fun `a PIN is verified and a different one is rejected`() = runTest {
        val credentialManager = manager()
        credentialManager.enroll(pin("5731"), pin("5731"))

        assertEquals(AuthenticationOutcome.Succeeded, credentialManager.verify(pin("5731")))
        assertTrue(credentialManager.verify(pin("9182")) is AuthenticationOutcome.Failed)
    }

    @Test
    fun `a password is verified and a different one is rejected`() = runTest {
        val credentialManager = manager()
        val secret = password("correct horse battery")
        credentialManager.enroll(secret, password("correct horse battery"))

        assertEquals(AuthenticationOutcome.Succeeded, credentialManager.verify(password("correct horse battery")))
        assertTrue(credentialManager.verify(password("correct horse batterz")) is AuthenticationOutcome.Failed)
    }

    @Test
    fun `a pattern is verified through its canonical form`() = runTest {
        val credentialManager = manager()
        credentialManager.enroll(pattern(0, 4, 8, 1), pattern(0, 4, 8, 1))

        assertEquals(AuthenticationOutcome.Succeeded, credentialManager.verify(pattern(0, 4, 8, 1)))
        // A different drawing is rejected, and so is the same drawing in the wrong order.
        assertTrue(credentialManager.verify(pattern(0, 4, 8, 2)) is AuthenticationOutcome.Failed)
        assertTrue(credentialManager.verify(pattern(1, 8, 4, 0)) is AuthenticationOutcome.Failed)
    }

    @Test
    fun `a pattern that crosses a dot verifies from either drawing of the same line`() = runTest {
        val credentialManager = manager()
        // Enrol the diagonal crossed explicitly, then confirm with the same drawing.
        credentialManager.enroll(pattern(0, 4, 8, 5), pattern(0, 4, 8, 5))

        assertEquals(AuthenticationOutcome.Succeeded, credentialManager.verify(pattern(0, 4, 8, 5)))
        // Drawing the same line without touching the centre produces the same canonical form,
        // which is the point of canonicalisation: the user is not punished for tracing style.
        assertEquals(AuthenticationOutcome.Succeeded, credentialManager.verify(pattern(0, 8, 5)))
    }

    @Test
    fun `a credential of the wrong type cannot authenticate`() = runTest {
        val credentialManager = manager()
        // The canonical form of this pattern is "0481".
        credentialManager.enroll(pattern(0, 4, 8, 1), pattern(0, 4, 8, 1))

        // A PIN with the same digits is a different credential: the method is part of the
        // derivation, so relabelling the stored record cannot make one stand in for the other.
        assertTrue(credentialManager.verify(pin("0481")) is AuthenticationOutcome.Failed)
    }

    @Test
    fun `an empty credential is rejected rather than throwing`() = runTest {
        val credentialManager = manager()
        credentialManager.enroll(pin("5731"), pin("5731"))

        assertTrue(credentialManager.verify(CredentialInput.Pin(CharArray(0))) is AuthenticationOutcome.Failed)
    }

    @Test
    fun `verification fails when nothing is configured`() = runTest {
        assertEquals(AuthenticationOutcome.NotConfigured, manager().verify(pin("5731")))
    }

    // ------------------------------------------------------------------ enrollment rules

    @Test
    fun `a mismatched confirmation is rejected and nothing is written`() = runTest {
        val credentialManager = manager()

        val result = credentialManager.enroll(pin("5731"), pin("5732"))

        assertEquals(CredentialFailure.ConfirmationMismatch, (result as NivaraResult.Failure).error)
        assertFalse(recordFile.exists())
        assertEquals(NivaraResult.Success(CredentialStatus.NotConfigured), credentialManager.status())
    }

    @Test
    fun `a pattern whose confirmation differs is rejected`() = runTest {
        val credentialManager = manager()

        val result = credentialManager.enroll(pattern(0, 1, 2, 3), pattern(0, 1, 2, 4))

        assertEquals(CredentialFailure.ConfirmationMismatch, (result as NivaraResult.Failure).error)
        assertFalse(recordFile.exists())
    }

    @Test
    fun `weak choices are rejected with the rule that rejected them`() = runTest {
        val credentialManager = manager()

        assertEquals(
            CredentialFailure.TooShort(4),
            (credentialManager.enroll(pin("12"), pin("12")) as NivaraResult.Failure).error,
        )
        assertEquals(
            CredentialFailure.TooCommon,
            (credentialManager.enroll(pin("1234"), pin("1234")) as NivaraResult.Failure).error,
        )
        assertEquals(
            CredentialFailure.InvalidCharacters,
            (credentialManager.enroll(pin("12a4"), pin("12a4")) as NivaraResult.Failure).error,
        )
        assertEquals(
            CredentialFailure.TooShort(8),
            (credentialManager.enroll(password("short"), password("short")) as NivaraResult.Failure).error,
        )
        assertEquals(
            CredentialFailure.TooCommon,
            (credentialManager.enroll(password("password"), password("password")) as NivaraResult.Failure).error,
        )
        assertEquals(
            CredentialFailure.PatternTooShort(4),
            (credentialManager.enroll(pattern(0, 1, 2), pattern(0, 1, 2)) as NivaraResult.Failure).error,
        )
        assertFalse(recordFile.exists())
    }

    @Test
    fun `a pattern that leaves the grid is rejected`() = runTest {
        val credentialManager = manager()

        val result = credentialManager.enroll(pattern(0, 1, 2, 9), pattern(0, 1, 2, 9))

        assertEquals(CredentialFailure.PatternInvalid, (result as NivaraResult.Failure).error)
    }

    @Test
    fun `entries of different types are rejected`() = runTest {
        val credentialManager = manager()

        val result = credentialManager.enroll(pin("5731"), password("correct horse battery"))

        assertEquals(CredentialFailure.ConfirmationTypeMismatch, (result as NivaraResult.Failure).error)
    }

    // ------------------------------------------------------------------ change

    @Test
    fun `changing from a PIN to a password invalidates the PIN`() = runTest {
        val credentialManager = manager()
        credentialManager.enroll(pin("5731"), pin("5731"))

        val changed = credentialManager.change(
            current = pin("5731"),
            credential = password("correct horse battery"),
            confirmation = password("correct horse battery"),
        )

        assertTrue(changed .isSuccess)
        assertEquals(
            CredentialStatus.Configured(PrimaryCredentialType.Password),
            credentialManager.status().valueOrFail(),
        )
        assertEquals(AuthenticationOutcome.Succeeded, credentialManager.verify(password("correct horse battery")))
        assertTrue(credentialManager.verify(pin("5731")) is AuthenticationOutcome.Failed)
    }

    @Test
    fun `changing from a password to a pattern invalidates the password`() = runTest {
        val credentialManager = manager()
        credentialManager.enroll(password("correct horse battery"), password("correct horse battery"))

        val changed = credentialManager.change(
            current = password("correct horse battery"),
            credential = pattern(0, 4, 8, 1),
            confirmation = pattern(0, 4, 8, 1),
        )

        assertTrue(changed .isSuccess)
        assertEquals(
            CredentialStatus.Configured(PrimaryCredentialType.Pattern),
            credentialManager.status().valueOrFail(),
        )
        assertEquals(AuthenticationOutcome.Succeeded, credentialManager.verify(pattern(0, 4, 8, 1)))
        assertTrue(credentialManager.verify(password("correct horse battery")) is AuthenticationOutcome.Failed)
    }

    @Test
    fun `changing from a pattern to a PIN invalidates the pattern`() = runTest {
        val credentialManager = manager()
        credentialManager.enroll(pattern(0, 4, 8, 1), pattern(0, 4, 8, 1))

        val changed = credentialManager.change(
            current = pattern(0, 4, 8, 1),
            credential = pin("5731"),
            confirmation = pin("5731"),
        )

        assertTrue(changed .isSuccess)
        assertEquals(
            CredentialStatus.Configured(PrimaryCredentialType.Pin),
            credentialManager.status().valueOrFail(),
        )
        assertEquals(AuthenticationOutcome.Succeeded, credentialManager.verify(pin("5731")))
        assertTrue(credentialManager.verify(pattern(0, 4, 8, 1)) is AuthenticationOutcome.Failed)
    }

    @Test
    fun `a change is refused when the current credential is wrong`() = runTest {
        val credentialManager = manager()
        credentialManager.enroll(pin("5731"), pin("5731"))

        val result = credentialManager.change(
            current = pin("9182"),
            credential = password("correct horse battery"),
            confirmation = password("correct horse battery"),
        )

        assertEquals(CredentialFailure.CurrentCredentialIncorrect, (result as NivaraResult.Failure).error)
        assertEquals(
            CredentialStatus.Configured(PrimaryCredentialType.Pin),
            credentialManager.status().valueOrFail(),
        )
        // The original credential still works.
        assertEquals(AuthenticationOutcome.Succeeded, credentialManager.verify(pin("5731")))
    }

    @Test
    fun `a change with a mismatched confirmation leaves the current credential in place`() = runTest {
        val credentialManager = manager()
        credentialManager.enroll(pin("5731"), pin("5731"))

        val result = credentialManager.change(
            current = pin("5731"),
            credential = password("correct horse battery"),
            confirmation = password("correct horse batters"),
        )

        assertEquals(CredentialFailure.ConfirmationMismatch, (result as NivaraResult.Failure).error)
        assertEquals(
            CredentialStatus.Configured(PrimaryCredentialType.Pin),
            credentialManager.status().valueOrFail(),
        )
    }

    @Test
    fun `changing is refused when nothing is configured`() = runTest {
        val result = manager().change(
            current = pin("5731"),
            credential = password("correct horse battery"),
            confirmation = password("correct horse battery"),
        )

        assertEquals(CredentialFailure.NotConfigured, (result as NivaraResult.Failure).error)
    }

    // ------------------------------------------------------------------ persistence

    @Test
    fun `the stored configuration survives a restart`() = runTest {
        manager().enroll(pin("5731"), pin("5731"))

        // A new manager over the same files, as a restarted process would build.
        val restarted = manager()

        assertEquals(
            CredentialStatus.Configured(PrimaryCredentialType.Pin),
            restarted.status().valueOrFail(),
        )
        assertEquals(AuthenticationOutcome.Succeeded, restarted.verify(pin("5731")))

        val record = storedRecord()
        requireNotNull(record)
        assertEquals(keyDerivationService.config.iterations, record.config.iterations)
        assertEquals(KeyDerivationConfig.MINIMUM_SALT_BYTES, record.salt.size)
    }

    @Test
    fun `enrolment stores the production parameters when the service uses them`() = runTest {
        val productionService = testKeyDerivationService(iterations = KeyDerivationConfig.DEFAULT_ITERATIONS)
        val credentialManager = manager(service = productionService)

        assertTrue(credentialManager.enroll(pin("5731"), pin("5731")).isSuccess)

        val record = storedRecord()
        requireNotNull(record)
        assertEquals(KeyDerivationConfig.DEFAULT_ITERATIONS, record.config.iterations)
        assertEquals(AuthenticationOutcome.Succeeded, credentialManager.verify(pin("5731")))
    }

    @Test
    fun `the record contains neither the credential nor the derived key`() = runTest {
        val credentialManager = manager()
        val secret = "correct horse battery"
        credentialManager.enroll(password(secret), password(secret))

        val bytes = recordFile.readBytes()
        assertFalse(
            "the credential must not be written to disk",
            containsSequence(bytes, secret.toByteArray()),
        )

        val record = storedRecord()
        requireNotNull(record)
        val derived = keyDerivationService.deriveKey(
            password = secret.toCharArray(),
            salt = SensitiveBytes.of(record.salt),
            config = record.config,
        ).valueOrFail()
        val keyMaterial = derived.material()

        assertFalse(
            "the derived key must never be stored",
            containsSequence(bytes, keyMaterial),
        )
        assertFalse(
            "the stored verifier must not be the derived key",
            containsSequence(record.verifier, keyMaterial),
        )
    }

    @Test
    fun `a pattern is not stored as a visible sequence`() = runTest {
        val credentialManager = manager()
        credentialManager.enroll(pattern(0, 4, 8, 1), pattern(0, 4, 8, 1))

        val bytes = recordFile.readBytes()

        // The canonical encoding of the drawing is "0481"; none of it may appear on disk.
        assertFalse(containsSequence(bytes, "0481".toByteArray()))
    }

    @Test
    fun `an unreadable record is reported as an invalid configuration`() = runTest {
        recordFile.writeBytes("not a credential record".toByteArray())
        val credentialManager = manager()

        assertEquals(
            NivaraResult.Failure(CredentialFailure.InvalidConfiguration),
            credentialManager.status(),
        )
        assertEquals(AuthenticationOutcome.InvalidConfiguration, credentialManager.verify(pin("5731")))
    }

    @Test
    fun `enrolment is refused when the stored record cannot be read`() = runTest {
        recordFile.writeBytes("not a credential record".toByteArray())

        val result = manager().enroll(pin("5731"), pin("5731"))

        assertEquals(CredentialFailure.InvalidConfiguration, (result as NivaraResult.Failure).error)
    }

    // ------------------------------------------------------------------ attempt tracking

    @Test
    fun `repeated failures introduce a delay and it expires`() = runTest {
        val credentialManager = manager()
        credentialManager.enroll(pin("5731"), pin("5731"))

        // Two attempts are free.
        assertEquals(AuthenticationOutcome.Failed(0L), credentialManager.verify(pin("9182")))
        assertEquals(AuthenticationOutcome.Failed(0L), credentialManager.verify(pin("9183")))

        // The third starts a delay, reported to the caller.
        val third = credentialManager.verify(pin("9184"))
        assertTrue(third is AuthenticationOutcome.Failed)
        val blockedFor = (third as AuthenticationOutcome.Failed).blockedForMillis
        assertTrue("a delay was expected after the third failure", blockedFor > 0L)

        // While the delay runs, an attempt is refused without being evaluated.
        assertEquals(
            AuthenticationOutcome.TemporarilyBlocked(blockedFor),
            credentialManager.verify(pin("5731")),
        )

        // After it expires, the correct credential is accepted again.
        timeProvider.advanceBy(blockedFor)
        assertEquals(AuthenticationOutcome.Succeeded, credentialManager.verify(pin("5731")))
    }

    @Test
    fun `a successful verification clears the failure state`() = runTest {
        val credentialManager = manager()
        credentialManager.enroll(pin("5731"), pin("5731"))
        credentialManager.verify(pin("9182"))
        assertEquals(1, attemptTracker.currentState().consecutiveFailures)

        assertEquals(AuthenticationOutcome.Succeeded, credentialManager.verify(pin("5731")))

        assertEquals(ThrottleState.Clear, attemptTracker.currentState())
        assertEquals(0L, attemptTracker.remainingBlockMillis())
    }

    @Test
    fun `the delay survives a restart`() = runTest {
        val credentialManager = manager()
        credentialManager.enroll(pin("5731"), pin("5731"))
        credentialManager.verify(pin("9182"))
        credentialManager.verify(pin("9183"))
        credentialManager.verify(pin("9184"))

        // A new manager and a new tracker over the same files must still refuse the attempt.
        val restarted = testCredentialManager(
            keyDerivationService = keyDerivationService,
            store = testRecordStore(recordFile),
            attemptTracker = testAttemptTracker(testAttemptStore(attemptFile), timeProvider),
        )

        assertTrue(restarted.verify(pin("5731")) is AuthenticationOutcome.TemporarilyBlocked)
    }

    // ------------------------------------------------------------------ secret handling

    @Test
    fun `the manager clears the buffers it is given`() = runTest {
        val credentialManager = manager()

        val entry = pin("5731")
        val confirmation = pin("5731")
        credentialManager.enroll(entry, confirmation)

        assertTrue("the entry buffer must be cleared", entry.digits.all { it == ZERO_CHAR })
        assertTrue("the confirmation buffer must be cleared", confirmation.digits.all { it == ZERO_CHAR })

        val attempt = pin("5731")
        assertEquals(AuthenticationOutcome.Succeeded, credentialManager.verify(attempt))
        assertTrue("the verification buffer must be cleared", attempt.digits.all { it == ZERO_CHAR })
    }

    @Test
    fun `buffers are cleared even when the operation fails`() = runTest {
        val credentialManager = manager()

        val entry = password("short")
        val confirmation = password("different")
        val result = credentialManager.enroll(entry, confirmation)

        assertTrue(result is NivaraResult.Failure)
        assertTrue(entry.characters.all { it == ZERO_CHAR })
        assertTrue(confirmation.characters.all { it == ZERO_CHAR })

        val points = intArrayOf(0, 1, 2)
        val rejected = credentialManager.verify(CredentialInput.Pattern(points))

        assertTrue(rejected is AuthenticationOutcome.NotConfigured)
        assertTrue("pattern points must be cleared", points.all { it == -1 })
    }

    private companion object {
        const val ZERO_CHAR: Char = '\u0000'
    }
}
