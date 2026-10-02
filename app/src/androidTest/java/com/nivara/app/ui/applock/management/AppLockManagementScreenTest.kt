package com.nivara.app.ui.applock.management

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nivara.app.R
import com.nivara.app.domain.app.InstalledApplication
import com.nivara.app.domain.applock.ApplicationProtectionState
import com.nivara.app.domain.permissions.AppLockPrerequisite
import com.nivara.app.ui.applications.ApplicationIconLoader
import com.nivara.app.ui.applications.ApplicationSortOrder
import com.nivara.app.ui.applock.ProtectionRunState
import com.nivara.app.ui.theme.NivaraTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the App Lock settings screen's composition.
 *
 * The screen under test is the stateless one: a state is handed to it and the interactions it
 * reports are observed. Nothing here touches a repository, a permission or a session, so the tests
 * say nothing about those — they say that the screen draws what the state says, that the state is
 * written out in words rather than left to a colour, and that a tap is reported rather than acted
 * on inside the composable.
 *
 * No test grants anything and none needs a special device state. Run with
 * `./gradlew :app:connectedDebugAndroidTest`; the suite is compiled by CI but executed only when a
 * device is attached, so none of this is claimed as verified until then.
 */
@RunWith(AndroidJUnit4::class)
class AppLockManagementScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun the_applications_are_drawn_with_their_protection_state() {
        rule.setContent {
            NivaraTheme {
                AppLockManagementScreen(
                    uiState = readyState(),
                    iconLoader = ApplicationIconLoader { null },
                    onRetry = {},
                    onQueryChange = {},
                    onSortChange = {},
                    onSectionChange = {},
                    onProtect = {},
                    onUnprotect = {},
                    onOpenPreparation = {},
                    onUnlock = {},
                )
            }
        }

        rule.onNodeWithText("Camera").assertIsDisplayed()
        rule.onNodeWithText(string(R.string.applock_manage_state_protected)).assertIsDisplayed()
        rule.onNodeWithText("Notes").assertIsDisplayed()
        rule.onNodeWithText(string(R.string.applock_manage_state_not_protected)).assertIsDisplayed()
    }

    @Test
    fun a_missing_capability_is_named_rather_than_summarised() {
        val prerequisite = string(R.string.applock_setup_prerequisite_overlay)
        rule.setContent {
            NivaraTheme {
                AppLockManagementScreen(
                    uiState = readyState().copy(
                        missingPrerequisites = listOf(AppLockPrerequisite.Overlay),
                    ),
                    iconLoader = ApplicationIconLoader { null },
                    onRetry = {},
                    onQueryChange = {},
                    onSortChange = {},
                    onSectionChange = {},
                    onProtect = {},
                    onUnprotect = {},
                    onOpenPreparation = {},
                    onUnlock = {},
                )
            }
        }

        // The wording is the preparation screen's own copy, and the capability is named on its own
        // line: the screen reports what is missing instead of one collapsed readiness state.
        rule.onNodeWithText(string(R.string.applock_manage_missing)).assertIsDisplayed()
        rule.onNodeWithText(
            context.getString(R.string.applock_manage_missing_item, prerequisite),
        ).assertIsDisplayed()
    }

    @Test
    fun a_locked_screen_offers_the_existing_credential_screen_and_reports_the_tap() {
        var unlockTaps = 0
        rule.setContent {
            NivaraTheme {
                AppLockManagementScreen(
                    uiState = readyState().copy(sessionAuthenticated = false),
                    iconLoader = ApplicationIconLoader { null },
                    onRetry = {},
                    onQueryChange = {},
                    onSortChange = {},
                    onSectionChange = {},
                    onProtect = {},
                    onUnprotect = {},
                    onOpenPreparation = {},
                    onUnlock = { unlockTaps++ },
                )
            }
        }

        rule.onNodeWithText(string(R.string.applock_manage_locked)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.applock_manage_unlock_action)).performClick()

        assertTrue("the tap must be reported, not acted on here", unlockTaps == 1)
    }

    @Test
    fun an_empty_search_result_says_so_rather_than_showing_nothing() {
        rule.setContent {
            NivaraTheme {
                AppLockManagementScreen(
                    uiState = readyState().copy(
                        rows = emptyList(),
                        query = "zzz",
                        emptiness = AppLockListEmptiness.NoSearchResults,
                    ),
                    iconLoader = ApplicationIconLoader { null },
                    onRetry = {},
                    onQueryChange = {},
                    onSortChange = {},
                    onSectionChange = {},
                    onProtect = {},
                    onUnprotect = {},
                    onOpenPreparation = {},
                    onUnlock = {},
                )
            }
        }

        rule.onNodeWithText(string(R.string.applock_manage_empty_search)).assertIsDisplayed()
    }

    private fun string(id: Int): String = context.getString(id)

    private fun readyState() = AppLockManagementUiState.Ready(
        rows = listOf(
            ManagedApplication(
                application = InstalledApplication("com.example.camera", "Camera"),
                state = ApplicationProtectionState.Protected,
            ),
            ManagedApplication(
                application = InstalledApplication("com.example.notes", "Notes"),
                state = ApplicationProtectionState.NotProtected,
            ),
        ),
        section = ApplicationSection.All,
        sort = ApplicationSortOrder.NameAscending,
        query = "",
        missingPrerequisites = emptyList(),
        runState = ProtectionRunState.Stopped,
        storedSetUnreadable = false,
        sessionAuthenticated = true,
        discoveredCount = 2,
        protectedCount = 1,
        protectedNotInstalledCount = 0,
    )
}
