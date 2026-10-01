package com.nivara.app.ui.camouflage

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nivara.app.R
import com.nivara.app.domain.camouflage.CamouflageProfile
import com.nivara.app.ui.theme.NivaraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the application-identity screen's composition.
 *
 * The composable under test is the stateless one: a state is handed to it and the interactions it
 * reports are observed. Nothing here changes a component, authenticates anybody or reads a
 * repository — the suite says that the screen offers every declared identity, names the one in use,
 * states what the feature does not do, and reports a choice rather than acting on it.
 *
 * Run with `./gradlew :app:connectedDebugAndroidTest`. CI compiles the suite; it is executed only
 * when a device is attached, so none of it is claimed as verified until then.
 */
@RunWith(AndroidJUnit4::class)
class CamouflageScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun every_identity_is_offered_including_nivaras_own() {
        rule.setContent { content(readyState()) }

        CamouflageProfile.entries.forEach { profile ->
            rule.onNodeWithText(string(profile.labelRes())).assertIsDisplayed()
        }
        rule.onNodeWithText(string(R.string.app_name)).assertIsDisplayed()
    }

    @Test
    fun the_identity_in_use_is_named() {
        rule.setContent { content(readyState(selected = CamouflageProfile.Calculator)) }

        rule.onNodeWithText(
            string(R.string.camouflage_current_summary, string(R.string.camouflage_profile_calculator_label)),
        ).assertIsDisplayed()
        // The identity that is already presented offers no change.
        rule.onNodeWithText(string(R.string.camouflage_action_current)).assertIsNotEnabled()
    }

    @Test
    fun the_screen_states_what_camouflage_does_not_do() {
        rule.setContent { content(readyState()) }

        rule.onNodeWithText(string(R.string.camouflage_limitation_title)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.camouflage_limitation)).assertIsDisplayed()
    }

    @Test
    fun the_screen_states_how_to_get_back_to_nivara() {
        rule.setContent { content(readyState()) }

        rule.onNodeWithText(string(R.string.camouflage_recovery_title)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.camouflage_recovery)).assertIsDisplayed()
    }

    @Test
    fun a_choice_is_reported_rather_than_acted_on() {
        var chosen: CamouflageProfile? = null
        rule.setContent { content(readyState(), onSelect = { profile -> chosen = profile }) }

        rule.onNodeWithText(string(R.string.camouflage_action_use)).performClick()

        assertEquals(
            "the first identity offered is the first camouflage one",
            CamouflageProfile.Notes,
            chosen,
        )
    }

    @Test
    fun a_locked_screen_offers_the_existing_unlock_instead_of_changing_anything() {
        var chosen: CamouflageProfile? = null
        var unlocks = 0
        rule.setContent {
            content(
                readyState(sessionAuthenticated = false),
                onSelect = { profile -> chosen = profile },
                onUnlock = { unlocks++ },
            )
        }

        rule.onNodeWithText(string(R.string.camouflage_locked)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.camouflage_unlock_action)).performClick()

        assertEquals("the screen hands the user to the credential screen", 1, unlocks)
        assertNull("and changes nothing itself", chosen)
    }

    // ------------------------------------------------------------------ helpers

    private fun string(id: Int): String = context.getString(id)
    private fun string(id: Int, argument: String): String = context.getString(id, argument)

    private fun readyState(
        selected: CamouflageProfile = CamouflageProfile.Nivara,
        sessionAuthenticated: Boolean = true,
    ) = CamouflageUiState.Ready(
        selected = selected,
        sessionAuthenticated = sessionAuthenticated,
    )
}

@Composable
private fun content(
    state: CamouflageUiState,
    onSelect: (CamouflageProfile) -> Unit = {},
    onUnlock: () -> Unit = {},
) {
    NivaraTheme {
        CamouflageScreen(
            uiState = state,
            onSelect = onSelect,
            onUnlock = onUnlock,
            onMessageShown = {},
        )
    }
}
