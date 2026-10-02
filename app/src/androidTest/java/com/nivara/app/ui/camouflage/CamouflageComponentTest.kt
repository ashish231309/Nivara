package com.nivara.app.ui.camouflage

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the components camouflage works with, as the platform sees them.
 *
 * These read the installed application rather than the source tree: which launcher entries exist,
 * which of them are enabled, and what targets what. They are the runtime half of the manifest rules
 * the repository checker enforces statically, and they cover the state that matters most — that a
 * device sitting in front of a user always has exactly one launcher entry for Nivara, and that it is
 * Nivara's own.
 *
 * They do not change any component: selecting an identity is a user action, and a test that
 * camouflaged the device would leave it that way.
 *
 * Run with `./gradlew :app:connectedDebugAndroidTest`. CI compiles the suite; it is executed only
 * when a device is attached, so none of it is claimed as verified until then.
 */
@RunWith(AndroidJUnit4::class)
class CamouflageComponentTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val packageManager: PackageManager get() = context.packageManager
    private val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    @Test
    fun a_fresh_install_presents_exactly_one_launcher_entry_and_it_is_nivaras_own() {
        val entries = enabledLauncherEntries()

        assertEquals(
            "a device must never offer two or more Nivara entries, and never none",
            1,
            entries.size,
        )
        assertEquals(
            "the shipped default is Nivara's own identity",
            "$PACKAGE.$REAL_ENTRY",
            entries.single(),
        )
    }

    @Test
    fun every_camouflage_identity_is_declared_but_shows_no_launcher_entry_by_default() {
        val declared = declaredAliases()
        val visible = enabledLauncherEntries()

        assertEquals(
            "every identity the model declares has a component",
            setOf("$PACKAGE.$CALCULATOR_ALIAS", "$PACKAGE.$NOTES_ALIAS", "$PACKAGE.$WEATHER_ALIAS"),
            declared.map { alias -> alias.name }.toSet(),
        )
        declared.forEach { alias ->
            // What matters is the effective state — what the launcher would offer — and the setting
            // behind it: an explicitly enabled alias is an identity the user chose, and on a device
            // nobody has configured there must be none.
            assertFalse(
                "an alias in the launcher would be a second Nivara entry",
                visible.contains(alias.name),
            )
            assertNotEquals(
                "an alias enabled before the user chose it would be camouflage nobody asked for",
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                packageManager.getComponentEnabledSetting(ComponentName(PACKAGE, alias.name)),
            )
        }
    }

    @Test
    fun every_alias_presents_the_same_activity() {
        // An identity is a name and an icon over the same component, never a second screen or a
        // second task.
        declaredAliases().forEach { alias ->
            assertEquals("$PACKAGE.$REAL_ENTRY", alias.targetActivity)
        }
    }

    @Test
    fun the_home_contract_is_still_exactly_what_stage_eleven_left() {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val homeCandidates = queryIntentActivities(homeIntent)
            .filter { info -> info.activityInfo.packageName == PACKAGE }

        assertEquals("Nivara must still offer exactly one Home activity", 1, homeCandidates.size)
        assertEquals("$PACKAGE.ui.launcher.LauncherActivity", homeCandidates.single().activityInfo.name)
    }

    @Test
    fun no_identity_component_replaces_the_home_entry() {
        val homeComponent = ComponentName(PACKAGE, "ui.launcher.LauncherActivity")

        declaredAliases().forEach { alias ->
            assertTrue(
                "an alias targets the application's own entry, never the Home activity",
                alias.targetActivity != homeComponent.className,
            )
        }
        val homeSetting = packageManager.getComponentEnabledSetting(homeComponent)
        assertFalse(
            "camouflage must never disable the Home entry: that is the user's only way back",
            homeSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                homeSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
        )
    }

    // ------------------------------------------------------------------ platform helpers

    private fun enabledLauncherEntries(): List<String> =
        queryIntentActivities(launcherIntent)
            .filter { info -> info.activityInfo.packageName == PACKAGE }
            .map { info -> info.activityInfo.name }

    private fun declaredAliases(): List<ActivityInfo> =
        declaredActivities().filter { activity ->
            activity.targetActivity != null && activity.name.startsWith("$PACKAGE.$ALIAS_PREFIX")
        }

    private fun declaredActivities(): List<ActivityInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                PACKAGE,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong()),
            ).activities?.toList().orEmpty()
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(PACKAGE, PackageManager.GET_ACTIVITIES)
                .activities?.toList().orEmpty()
        }

    private fun queryIntentActivities(intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, 0)
        }

    private companion object {
        val PACKAGE = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        const val REAL_ENTRY = "MainActivity"
        const val ALIAS_PREFIX = "Camouflage"
        const val CALCULATOR_ALIAS = "CamouflageCalculator"
        const val NOTES_ALIAS = "CamouflageNotes"
        const val WEATHER_ALIAS = "CamouflageWeather"
    }
}
