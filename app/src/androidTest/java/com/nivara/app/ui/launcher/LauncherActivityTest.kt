package com.nivara.app.ui.launcher

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the Home contract itself: that Android resolves Nivara's launcher activity
 * for the Home intent, that Nivara declares exactly one of them, that nothing else is exported, and
 * that the activity starts as an ordinary home surface.
 *
 * The queries are made through Nivara's own context rather than the test's, because the test package
 * has its own visibility rules and would otherwise be asking a different question.
 *
 * These tests are compiled by CI and executed only when a device is attached. Selecting Nivara as the
 * device's Home application is a user action that no test performs, and nothing here claims that the
 * device was switched to Nivara — only that the platform resolves it as a Home candidate.
 */
@RunWith(AndroidJUnit4::class)
class LauncherActivityTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val packageManager: PackageManager get() = context.packageManager
    private val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    @Test
    fun android_resolves_nivaras_launcher_for_the_home_intent() {
        val candidates = nivaraHomeCandidates()

        assertTrue("Nivara declares no activity Android will accept as Home", candidates.isNotEmpty())
        assertEquals("Nivara must declare exactly one Home activity", 1, candidates.size)
        assertTrue(
            "the resolved component must be the launcher activity",
            candidates.single().activityInfo.name.endsWith(LAUNCHER_ACTIVITY_NAME),
        )
    }

    @Test
    fun the_launcher_activity_is_resolvable_through_the_default_category_as_well() {
        // CATEGORY_DEFAULT is what makes a Home filter usable at all: a filter with HOME but without
        // DEFAULT is ignored by the platform's resolver, and the activity would look present in the
        // manifest while never being offered as a Home application.
        val withDefault = queryIntentActivities(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addCategory(Intent.CATEGORY_DEFAULT),
        )

        assertTrue(
            "the Home activity must also be resolvable through the default category",
            withDefault.any { info -> info.activityInfo.packageName == context.packageName },
        )
    }

    @Test
    fun the_launcher_starts_as_an_ordinary_unprotected_activity() {
        ActivityScenario.launch(LauncherActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse("the launcher must not be finishing on creation", activity.isFinishing)
                assertEquals(
                    "the launcher itself is an ordinary home surface: it takes no screenshot " +
                        "protection until hidden applications are actually on screen",
                    0,
                    activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE,
                )
            }
        }
    }

    @Test
    fun the_exported_surface_is_the_entry_point_the_home_activity_and_the_declared_identities() {
        // The exported surface is what any application on the device can reach, so it is asserted
        // from the outside rather than read off the manifest. Three kinds of component are exported
        // on purpose: the application's own launcher entry, which every installed application has;
        // the Home activity, which Android can only start if it is allowed to; and a declared
        // camouflage identity, which is the same activity presented under another name. Nothing else
        // may be exported, and the two components that must always be there are asserted first.
        val exported = nivaraActivities()
            .filter { activity -> activity.exported }
            .map { activity -> activity.name.removePrefix(context.packageName + ".") }
            .toSet()
        val ownEntry = appEntryActivityName()

        assertTrue(
            "the application's own entry point must stay exported",
            exported.contains(ownEntry),
        )
        assertTrue(
            "the Home activity must stay exported, or Android cannot start Nivara as Home",
            exported.contains(LAUNCHER_ACTIVITY_NAME),
        )
        assertTrue(
            "no component outside the entry point, the Home activity and the declared identities "
                + "may be exported",
            exported.all { name -> name in exportedSurface },
        )
    }

    /**
     * Everything the manifest is allowed to export: the two entry points and one component per
     * declared camouflage identity. The repository checker pins the same list statically.
     */
    private val exportedSurface: Set<String> get() = setOf(
        appEntryActivityName(),
        LAUNCHER_ACTIVITY_NAME,
        "CamouflageNotes",
        "CamouflageCalculator",
        "CamouflageWeather",
    )

    /** The activity Android starts when the user opens Nivara from whichever launcher they use. */
    private fun appEntryActivityName(): String {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)
        val resolved = queryIntentActivities(intent).first { info ->
            info.activityInfo.packageName == context.packageName
        }
        return resolved.activityInfo.name.removePrefix(context.packageName + ".")
    }

    // ------------------------------------------------------------------ platform helpers

    private fun nivaraHomeCandidates(): List<ResolveInfo> =
        queryIntentActivities(homeIntent).filter { info ->
            info.activityInfo.packageName == context.packageName
        }

    private fun queryIntentActivities(intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, 0)
        }

    private fun nivaraActivities(): List<ActivityInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong()),
            ).activities?.toList().orEmpty()
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(context.packageName, PackageManager.GET_ACTIVITIES)
                .activities?.toList().orEmpty()
        }

    private companion object {
        const val LAUNCHER_ACTIVITY_NAME = "ui.launcher.LauncherActivity"
    }
}
