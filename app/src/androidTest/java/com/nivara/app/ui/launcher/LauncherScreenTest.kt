package com.nivara.app.ui.launcher

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nivara.app.R
import com.nivara.app.domain.app.InstalledApplication
import com.nivara.app.ui.applications.ApplicationIconLoader
import com.nivara.app.ui.applications.ApplicationSortOrder
import com.nivara.app.ui.theme.NivaraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the launcher's composition.
 *
 * The composable under test is the stateless one: a state is handed to it and the interactions it
 * reports are observed. Nothing here reads a repository, starts an application or authenticates
 * anybody, and nothing here changes any device state — the suite says that the surface draws what it
 * is given, that the drawer shows labels and not package names, that a tap is reported rather than
 * acted on, and that the fail-closed states draw no application at all.
 *
 * Run with `./gradlew :app:connectedDebugAndroidTest`. CI compiles the suite; it is executed only
 * when a device is attached, so none of it is claimed as verified until then.
 */
@RunWith(AndroidJUnit4::class)
class LauncherScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun the_home_surface_offers_the_drawer_the_settings_and_the_reveal() {
        rule.setContent {
            NivaraTheme { launcherContent(state = readyState(), onLaunch = {}, onReveal = {}) }
        }

        rule.onNodeWithText(string(R.string.launcher_open_drawer)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.launcher_settings_action)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.launcher_hidden_card_title)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.launcher_reveal_action)).assertIsDisplayed()
    }

    @Test
    fun the_home_surface_says_that_android_still_shows_these_applications() {
        rule.setContent {
            NivaraTheme { launcherContent(state = readyState(), onLaunch = {}, onReveal = {}) }
        }

        rule.onNodeWithText(string(R.string.launcher_home_summary)).assertIsDisplayed()
    }

    @Test
    fun the_drawer_draws_labels_and_never_a_package_name() {
        rule.setContent {
            NivaraTheme { launcherContent(state = readyState(), drawerOpen = true, onLaunch = {}, onReveal = {}) }
        }

        rule.onNodeWithText("Camera").assertIsDisplayed()
        rule.onNodeWithText("Maps").assertIsDisplayed()
        rule.onNodeWithText("com.example.camera").assertDoesNotExist()
    }

    @Test
    fun a_tap_on_an_application_is_reported_rather_than_acted_on() {
        var launched: InstalledApplication? = null
        rule.setContent {
            NivaraTheme {
                launcherContent(
                    state = readyState(),
                    drawerOpen = true,
                    onLaunch = { application -> launched = application },
                    onReveal = {},
                )
            }
        }

        rule.onNodeWithContentDescription("Maps").performClick()

        assertEquals("com.example.maps", launched?.packageName)
    }

    @Test
    fun the_reveal_control_reports_the_request_instead_of_revealing_anything() {
        var revealTaps = 0
        rule.setContent {
            NivaraTheme {
                launcherContent(
                    state = readyState(),
                    onLaunch = {},
                    onReveal = { revealTaps++ },
                )
            }
        }

        rule.onNodeWithText(string(R.string.launcher_reveal_action)).performClick()

        assertTrue("the tap must be reported, not acted on here", revealTaps == 1)
    }

    @Test
    fun an_unreadable_hidden_set_draws_no_application_and_no_drawer_button() {
        rule.setContent {
            NivaraTheme {
                launcherContent(
                    state = LauncherUiState.HiddenStateUnreadable,
                    onLaunch = {},
                    onReveal = {},
                )
            }
        }

        rule.onNodeWithText(string(R.string.launcher_hidden_unreadable_title)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.launcher_hidden_unreadable)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.launcher_open_drawer)).assertDoesNotExist()
        rule.onNodeWithText("Camera").assertDoesNotExist()
    }

    @Test
    fun a_failed_discovery_says_so_and_offers_only_retry_and_settings() {
        rule.setContent {
            NivaraTheme {
                launcherContent(
                    state = LauncherUiState.DiscoveryUnavailable,
                    onLaunch = {},
                    onReveal = {},
                )
            }
        }

        rule.onNodeWithText(string(R.string.launcher_discovery_unavailable_title)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.state_retry_action)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.launcher_settings_action)).assertIsDisplayed()
    }

    // ------------------------------------------------------------------ helpers

    private fun string(id: Int): String = context.getString(id)

    private fun readyState() = LauncherUiState.Ready(
        entries = listOf(
            InstalledApplication("com.example.camera", "Camera"),
            InstalledApplication("com.example.maps", "Maps"),
        ),
        section = LauncherSection.All,
        sort = ApplicationSortOrder.NameAscending,
        query = "",
        revealed = false,
        sessionAuthenticated = true,
        discoveredCount = 3,
        hiddenCount = 1,
        withheldCount = 1,
    )
}

private fun launcherContent(
    state: LauncherUiState,
    drawerOpen: Boolean = false,
    onLaunch: (InstalledApplication) -> Unit,
    onReveal: () -> Unit,
) {
    LauncherScreen(
        uiState = state,
        iconLoader = ApplicationIconLoader { null },
        drawerOpen = drawerOpen,
        onDrawerOpenChange = {},
        onRetry = {},
        onQueryChange = {},
        onSortChange = {},
        onSectionChange = {},
        onLaunch = onLaunch,
        onReveal = onReveal,
        onConceal = {},
        onMessageShown = {},
        onOpenSettings = {},
        onOpenHiddenManagement = {},
    )
}
