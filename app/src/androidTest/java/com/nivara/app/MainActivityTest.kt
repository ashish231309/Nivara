package com.nivara.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented smoke test for the application shell. Requires an attached device or emulator
 * (`./gradlew :app:connectedDebugAndroidTest`).
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeScreen_showsBrandingAndNavigatesToAbout() {
        val tagline = composeRule.activity.getString(R.string.home_tagline)
        val aboutAction = composeRule.activity.getString(R.string.home_about_action)
        val applicationIdLabel = composeRule.activity.getString(R.string.about_package_label)

        composeRule.onNodeWithText(tagline).assertIsDisplayed()

        composeRule.onNodeWithText(aboutAction).performClick()

        composeRule.onNodeWithText(applicationIdLabel).assertIsDisplayed()
    }
}
