package com.nivara.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented smoke test for the application shell. Requires an attached device or emulator
 * (`./gradlew :app:connectedDebugAndroidTest`).
 *
 * A fresh install holds none of the three required capabilities, so the very first screen is
 * the permission gate: it names the application and every required row. Once all three are
 * granted the gate never appears again, which is exactly what the second test asserts cannot
 * be told from this side — the home itself is exercised by the screen tests.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun firstLaunch_showsThePermissionGate() {
        val title = composeRule.activity.getString(R.string.onboarding_title)
        val usageRow = composeRule.activity.getString(R.string.onboarding_usage_title)
        val overlayRow = composeRule.activity.getString(R.string.onboarding_overlay_title)
        val batteryRow = composeRule.activity.getString(R.string.onboarding_battery_title)

        composeRule.onNodeWithText(title).assertIsDisplayed()
        composeRule.onNodeWithText(usageRow).assertIsDisplayed()
        composeRule.onNodeWithText(overlayRow).assertIsDisplayed()
        composeRule.onNodeWithText(batteryRow).assertIsDisplayed()
    }
}
