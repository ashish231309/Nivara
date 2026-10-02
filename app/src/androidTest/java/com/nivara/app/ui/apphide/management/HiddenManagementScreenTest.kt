package com.nivara.app.ui.apphide.management

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nivara.app.R
import com.nivara.app.domain.app.InstalledApplication
import com.nivara.app.domain.apphide.ApplicationVisibility
import com.nivara.app.ui.applications.ApplicationIconLoader
import com.nivara.app.ui.applications.ApplicationSortOrder
import com.nivara.app.ui.theme.NivaraTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the hidden-application management screen's composition.
 *
 * As with the App Lock settings screen, the composable under test is the stateless one: a state is
 * handed to it and the interactions it reports are observed. Nothing here touches a repository, a
 * file or a session, and nothing here hides anything on the device — the suite says only that the
 * screen draws what it is given, that each row's state is written out in words, and that a tap is
 * reported rather than acted on inside the composable.
 *
 * No test needs a special device state. Run with `./gradlew :app:connectedDebugAndroidTest`; CI
 * compiles the suite but it is executed only when a device is attached, so none of this is claimed
 * as verified until then.
 */
@RunWith(AndroidJUnit4::class)
class HiddenManagementScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun the_applications_are_drawn_with_their_hidden_state() {
        rule.setContent {
            NivaraTheme {
                HiddenManagementScreen(
                    uiState = readyState(),
                    iconLoader = ApplicationIconLoader { null },
                    onRetry = {},
                    onQueryChange = {},
                    onSortChange = {},
                    onSectionChange = {},
                    onHide = {},
                    onUnhide = {},
                    onUnlock = {},
                )
            }
        }

        rule.onNodeWithText("Camera").assertIsDisplayed()
        rule.onNodeWithText(string(R.string.apphide_manage_state_hidden)).assertIsDisplayed()
        rule.onNodeWithText("Notes").assertIsDisplayed()
        rule.onNodeWithText(string(R.string.apphide_manage_state_visible)).assertIsDisplayed()
    }

    @Test
    fun the_screen_says_that_android_still_shows_these_applications() {
        rule.setContent {
            NivaraTheme {
                HiddenManagementScreen(
                    uiState = readyState(),
                    iconLoader = ApplicationIconLoader { null },
                    onRetry = {},
                    onQueryChange = {},
                    onSortChange = {},
                    onSectionChange = {},
                    onHide = {},
                    onUnhide = {},
                    onUnlock = {},
                )
            }
        }

        // The distinction the whole feature rests on, said on the screen rather than only in the
        // documentation: this is Nivara's own preference, not a change to the device.
        rule.onNodeWithText(string(R.string.apphide_manage_launcher_note)).assertIsDisplayed()
    }

    @Test
    fun a_hide_tap_is_reported_rather_than_acted_on() {
        var hideTaps = 0
        rule.setContent {
            NivaraTheme {
                HiddenManagementScreen(
                    uiState = readyState(),
                    iconLoader = ApplicationIconLoader { null },
                    onRetry = {},
                    onQueryChange = {},
                    onSortChange = {},
                    onSectionChange = {},
                    onHide = { hideTaps++ },
                    onUnhide = {},
                    onUnlock = {},
                )
            }
        }

        rule.onNodeWithContentDescription(
            string(R.string.apphide_manage_action_hide_description, "Notes"),
        ).performClick()

        assertTrue("the tap must be reported, not acted on here", hideTaps == 1)
    }

    @Test
    fun an_unreadable_stored_set_claims_nothing_and_offers_nothing() {
        rule.setContent {
            NivaraTheme {
                HiddenManagementScreen(
                    uiState = readyState().copy(
                        rows = listOf(
                            ManagedHiddenApplication(
                                application = InstalledApplication("com.example.camera", "Camera"),
                                visibility = null,
                            ),
                        ),
                        hiddenState = HiddenStateAvailability.Unreadable,
                    ),
                    iconLoader = ApplicationIconLoader { null },
                    onRetry = {},
                    onQueryChange = {},
                    onSortChange = {},
                    onSectionChange = {},
                    onHide = {},
                    onUnhide = {},
                    onUnlock = {},
                )
            }
        }

        // The row may not say "Visible" or "Hidden", and the control may not be offered: a claim
        // about an application that cannot be read is exactly what this state exists to prevent.
        rule.onNodeWithText(string(R.string.apphide_manage_state_unknown)).assertIsDisplayed()
        rule.onNodeWithContentDescription(
            string(R.string.apphide_manage_action_indeterminate_description, "Camera"),
        ).assertIsNotEnabled()
        rule.onNodeWithText(string(R.string.apphide_manage_state_unreadable)).assertIsDisplayed()
    }

    @Test
    fun an_empty_hidden_section_says_nothing_is_hidden_rather_than_nothing_was_found() {
        rule.setContent {
            NivaraTheme {
                HiddenManagementScreen(
                    uiState = readyState().copy(
                        rows = emptyList(),
                        section = HiddenSection.Hidden,
                        emptiness = HiddenListEmptiness.NothingHidden,
                    ),
                    iconLoader = ApplicationIconLoader { null },
                    onRetry = {},
                    onQueryChange = {},
                    onSortChange = {},
                    onSectionChange = {},
                    onHide = {},
                    onUnhide = {},
                    onUnlock = {},
                )
            }
        }

        rule.onNodeWithText(string(R.string.apphide_manage_empty_hidden)).assertIsDisplayed()
    }

    @Test
    fun a_locked_screen_offers_the_existing_credential_screen_and_reports_the_tap() {
        var unlockTaps = 0
        rule.setContent {
            NivaraTheme {
                HiddenManagementScreen(
                    uiState = readyState().copy(sessionAuthenticated = false),
                    iconLoader = ApplicationIconLoader { null },
                    onRetry = {},
                    onQueryChange = {},
                    onSortChange = {},
                    onSectionChange = {},
                    onHide = {},
                    onUnhide = {},
                    onUnlock = { unlockTaps++ },
                )
            }
        }

        rule.onNodeWithText(string(R.string.apphide_manage_locked)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.apphide_manage_unlock_action)).performClick()

        assertTrue("the tap must be reported, not acted on here", unlockTaps == 1)
    }

    private fun string(id: Int): String = context.getString(id)

    private fun string(id: Int, argument: Any): String = context.getString(id, argument)

    private fun readyState() = HiddenManagementUiState.Ready(
        rows = listOf(
            ManagedHiddenApplication(
                application = InstalledApplication("com.example.camera", "Camera"),
                visibility = ApplicationVisibility.Hidden,
            ),
            ManagedHiddenApplication(
                application = InstalledApplication("com.example.notes", "Notes"),
                visibility = ApplicationVisibility.Visible,
            ),
        ),
        section = HiddenSection.All,
        sort = ApplicationSortOrder.NameAscending,
        query = "",
        hiddenState = HiddenStateAvailability.Available(hiddenCount = 1, notInstalledCount = 0),
        sessionAuthenticated = true,
        discoveredCount = 2,
    )
}
