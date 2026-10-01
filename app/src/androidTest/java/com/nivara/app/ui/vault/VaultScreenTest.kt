package com.nivara.app.ui.vault

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nivara.app.MainActivity
import com.nivara.app.R
import com.nivara.app.domain.security.SecureRandomGenerator
import com.nivara.app.domain.vault.VaultIdentity
import com.nivara.app.domain.vault.VaultState
import com.nivara.app.domain.vault.VaultUnreadable
import com.nivara.app.ui.components.NivaraMessage
import com.nivara.app.ui.theme.NivaraTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the vault screen's composition and its place in the graph.
 *
 * The stateless screen is handed a state and the actions it reports are observed: nothing here
 * touches storage, opens a document, authenticates anybody or reads a repository. The suite says that
 * each state is drawn with its own words and its own controls, that a locked screen offers the
 * existing unlock instead of a second one, that the destructive control appears only where it is
 * allowed, and that the screen is reachable from the home screen as ordinary navigation.
 *
 * Run with `./gradlew :app:connectedDebugAndroidTest`. CI compiles this suite; it is executed only
 * when a device is attached, so none of it is claimed as verified until then — in particular, no
 * statement about how the Storage Access Framework behaves on a device is made here.
 */
@RunWith(AndroidJUnit4::class)
class VaultScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun a_device_without_a_vault_folder_is_told_so_and_offered_the_picker() {
        var picks = 0
        rule.setContent { content(readyState(VaultState.NotConfigured), onChooseRoot = { picks++ }) }

        rule.onNodeWithText(string(R.string.vault_state_not_configured_title)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_state_not_configured)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_choose_root_action)).performClick()

        assertEquals("the picker is the user's to open", 1, picks)
    }

    @Test
    fun a_ready_vault_is_reported_without_offering_anything_destructive() {
        rule.setContent { content(readyState(ready())) }

        rule.onNodeWithText(string(R.string.vault_state_ready_title)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_state_ready)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_initialize_action)).assertDoesNotExist()
        rule.onNodeWithText(string(R.string.vault_replace_action)).assertDoesNotExist()
    }

    @Test
    fun an_empty_folder_offers_to_create_the_vault() {
        var creations = 0
        rule.setContent { content(readyState(VaultState.Missing), onInitialize = { creations++ }) }

        rule.onNodeWithText(string(R.string.vault_state_missing_title)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_initialize_action)).performClick()

        assertEquals(1, creations)
    }

    @Test
    fun a_locked_screen_offers_the_existing_unlock_and_no_change() {
        var unlocks = 0
        var creations = 0
        rule.setContent {
            content(
                readyState(VaultState.Missing, sessionAuthenticated = false),
                onInitialize = { creations++ },
                onUnlock = { unlocks++ },
            )
        }

        rule.onNodeWithText(string(R.string.vault_locked)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_initialize_action)).assertDoesNotExist()
        rule.onNodeWithText(string(R.string.vault_unlock_action)).performClick()

        assertEquals("the screen hands the user to the credential screen", 1, unlocks)
        assertEquals("and changes nothing itself", 0, creations)
    }

    @Test
    fun an_unreadable_vault_offers_replacement_and_states_what_it_costs() {
        var replacements = 0
        rule.setContent {
            content(
                readyState(VaultState.Unreadable(VaultUnreadable.MetadataDamaged)),
                onReplaceUnreadable = { replacements++ },
            )
        }

        rule.onNodeWithText(string(R.string.vault_state_unreadable_title)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_state_unreadable_metadata)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_initialize_action)).assertDoesNotExist()
        rule.onNodeWithText(string(R.string.vault_replace_warning)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_replace_action)).performClick()

        assertEquals(1, replacements)
    }

    @Test
    fun an_unfinished_setup_is_completed_rather_than_replaced() {
        rule.setContent {
            content(readyState(VaultState.Unreadable(VaultUnreadable.StructureIncomplete)))
        }

        rule.onNodeWithText(string(R.string.vault_state_incomplete_title)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_initialize_action)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_replace_action)).assertDoesNotExist()
    }

    @Test
    fun an_unreachable_folder_offers_a_retry() {
        var retries = 0
        rule.setContent { content(readyState(VaultState.Unavailable), onRetry = { retries++ }) }

        rule.onNodeWithText(string(R.string.vault_state_unavailable_title)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.state_retry_action)).performClick()

        assertEquals(1, retries)
    }

    @Test
    fun every_control_is_disabled_while_a_change_is_running() {
        rule.setContent {
            content(readyState(VaultState.Missing, sessionAuthenticated = false, busy = true))
        }

        rule.onNodeWithText(string(R.string.vault_unlock_action)).assertIsNotEnabled()
        rule.onNodeWithText(string(R.string.vault_change_root_action)).assertIsNotEnabled()
        rule.onNodeWithText(string(R.string.vault_initialize_action)).assertDoesNotExist()
    }

    @Test
    fun a_refused_action_is_shown_and_can_be_dismissed() {
        var dismissals = 0
        rule.setContent {
            content(
                readyState(VaultState.Missing, failure = NivaraMessage(R.string.vault_error_write_failed)),
                onMessageShown = { dismissals++ },
            )
        }

        rule.onNodeWithText(string(R.string.vault_error_write_failed)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_error_write_failed)).performClick()

        assertEquals(1, dismissals)
    }

    @Test
    fun the_screen_states_where_the_vault_lives() {
        rule.setContent { content(readyState(VaultState.NotConfigured)) }

        rule.onNodeWithText(string(R.string.vault_explanation_title)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_explanation)).assertIsDisplayed()
    }

    // ------------------------------------------------------------------ helpers

    private fun string(id: Int): String = context.getString(id)

    private fun ready(): VaultState.Ready = VaultState.Ready(
        identity = VaultIdentity.create(SecureRandomGenerator()),
        formatVersion = 1,
    )

    private fun readyState(
        vault: VaultState,
        sessionAuthenticated: Boolean = true,
        busy: Boolean = false,
        failure: NivaraMessage? = null,
    ): VaultUiState.Ready = VaultUiState.Ready(
        vault = vault,
        sessionAuthenticated = sessionAuthenticated,
        busy = busy,
        failure = failure,
    )
}

/**
 * Instrumented test for the vault's place in the navigation graph.
 *
 * The vault is reached from the home screen like every other settings screen, under whatever name and
 * icon Nivara currently presents: storage has nothing to do with the application's identity, and
 * nothing about the vault is a hidden route. The assertion is deliberately about a card the vault
 * screen always shows, so the test does not depend on what the device's storage happens to contain.
 */
@RunWith(AndroidJUnit4::class)
class VaultNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun the_vault_screen_is_reached_from_the_home_screen() {
        val action = composeRule.activity.getString(R.string.home_vault_action)
        val explanation = composeRule.activity.getString(R.string.vault_explanation_title)

        composeRule.onNodeWithText(action).performClick()

        // The card the vault screen always shows, whichever state the device's storage is in: this
        // asserts that the destination is reachable, not what happens to be stored when it opens.
        composeRule.onNodeWithText(explanation).assertIsDisplayed()
    }
}

@Composable
private fun content(
    state: VaultUiState,
    onChooseRoot: () -> Unit = {},
    onInitialize: () -> Unit = {},
    onReplaceUnreadable: () -> Unit = {},
    onRetry: () -> Unit = {},
    onUnlock: () -> Unit = {},
    onMessageShown: () -> Unit = {},
) {
    NivaraTheme {
        VaultScreen(
            uiState = state,
            onChooseRoot = onChooseRoot,
            onInitialize = onInitialize,
            onReplaceUnreadable = onReplaceUnreadable,
            onRetry = onRetry,
            onUnlock = onUnlock,
            onMessageShown = onMessageShown,
        )
    }
}
