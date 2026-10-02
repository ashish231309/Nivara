package com.nivara.app.ui.vault.recovery

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nivara.app.R
import com.nivara.app.ui.components.NivaraMessage
import com.nivara.app.ui.theme.NivaraTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the recovery screen.
 *
 * The stateless screen is handed a phase and the taps it reports are observed: no repository, no
 * storage, no key and no file is involved. What is verified is the composition — that each phase
 * draws its own words and its own controls, that the fingerprint is what identifies the vault, and
 * that a refusal is never drawn as an empty vault.
 *
 * Run with `./gradlew :app:connectedDebugAndroidTest`. CI compiles this suite; it is executed only
 * when a device is attached, so nothing here is claimed as verified until it has actually run.
 */
@RunWith(AndroidJUnit4::class)
class VaultRecoveryScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun string(id: Int): String = context.getString(id)

    private fun setContent(
        uiState: VaultRecoveryUiState,
        onChooseLocation: () -> Unit = {},
        onCodeChanged: (String) -> Unit = {},
        onRecover: () -> Unit = {},
        onRestart: () -> Unit = {},
        onDone: () -> Unit = {},
    ) {
        rule.setContent {
            NivaraTheme {
                VaultRecoveryScreen(
                    uiState = uiState,
                    onChooseLocation = onChooseLocation,
                    onCodeChanged = onCodeChanged,
                    onRecover = onRecover,
                    onRestart = onRestart,
                    onDone = onDone,
                )
            }
        }
    }

    @Test
    fun the_selection_phase_explains_recovery_and_offers_the_picker() {
        setContent(uiState = VaultRecoveryUiState())

        rule.onNodeWithText(string(R.string.vault_recovery_intro)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_recovery_choose_action)).assertIsDisplayed()
    }

    @Test
    fun choosing_a_folder_reports_the_tap() {
        var chosen = 0
        setContent(
            uiState = VaultRecoveryUiState(),
            onChooseLocation = { chosen += 1 },
        )

        rule.onNodeWithText(string(R.string.vault_recovery_choose_action)).performClick()

        assertEquals(1, chosen)
    }

    @Test
    fun a_folder_that_is_not_a_vault_is_said_and_offers_another_choice() {
        setContent(uiState = VaultRecoveryUiState(phase = VaultRecoveryPhase.NotAVault))

        rule.onNodeWithText(string(R.string.vault_recovery_not_a_vault)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_recovery_choose_action)).assertIsDisplayed()
    }

    @Test
    fun a_vault_without_recovery_material_is_said_as_that() {
        setContent(uiState = VaultRecoveryUiState(phase = VaultRecoveryPhase.RecoveryNotSetUp))

        rule.onNodeWithText(string(R.string.vault_recovery_not_set_up)).assertIsDisplayed()
    }

    @Test
    fun a_damaged_recovery_record_is_said_as_damage() {
        setContent(uiState = VaultRecoveryUiState(phase = VaultRecoveryPhase.VaultDamaged))

        rule.onNodeWithText(string(R.string.vault_recovery_damaged)).assertIsDisplayed()
    }

    @Test
    fun a_newer_recovery_record_is_said_as_unsupported() {
        setContent(uiState = VaultRecoveryUiState(phase = VaultRecoveryPhase.VaultUnsupported))

        rule.onNodeWithText(string(R.string.vault_recovery_unsupported)).assertIsDisplayed()
    }

    @Test
    fun the_code_entry_shows_the_fingerprint_and_the_actions() {
        setContent(
            uiState = VaultRecoveryUiState(
                phase = VaultRecoveryPhase.RecoveryRequired(identityFingerprint = "abcd ef01 2345 6789"),
            ),
        )

        rule.onNodeWithText("abcd ef01 2345 6789").assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_recovery_identity_explainer)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_recovery_recover_action)).assertIsDisplayed()
    }

    @Test
    fun the_recover_action_hands_over_the_entered_code() {
        var recovered = 0
        setContent(
            uiState = VaultRecoveryUiState(
                phase = VaultRecoveryPhase.RecoveryRequired(identityFingerprint = "abcd ef01"),
                codeInput = "AAAA-BBBB-CCCC",
            ),
            onRecover = { recovered += 1 },
        )

        rule.onNodeWithText(string(R.string.vault_recovery_recover_action)).performClick()

        assertEquals(1, recovered)
    }

    @Test
    fun a_refusal_is_drawn_as_its_own_message() {
        setContent(
            uiState = VaultRecoveryUiState(
                phase = VaultRecoveryPhase.RecoveryRequired(identityFingerprint = "abcd ef01"),
                failure = NivaraMessage(textRes = R.string.vault_recovery_wrong_material),
            ),
        )

        rule.onNodeWithText(string(R.string.vault_recovery_wrong_material)).assertIsDisplayed()
    }

    @Test
    fun the_reconnected_vault_reports_the_fingerprint_and_the_way_back() {
        var done = 0
        setContent(
            uiState = VaultRecoveryUiState(
                phase = VaultRecoveryPhase.Reconnected(identityFingerprint = "abcd ef01 2345 6789"),
            ),
            onDone = { done += 1 },
        )

        rule.onNodeWithText(string(R.string.vault_recovery_success_title)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_recovery_success)).assertIsDisplayed()
        rule.onNodeWithText("abcd ef01 2345 6789").assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_recovery_done_action)).performClick()
        assertEquals(1, done)
    }
}
