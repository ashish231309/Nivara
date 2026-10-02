package com.nivara.app.data.app

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nivara.app.core.common.valueOrNull
import com.nivara.app.domain.app.inDefaultApplicationOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for launcher discovery.
 *
 * These run on a device or emulator and ask the real package manager. Nothing is faked here, and
 * nothing asserts that a particular third-party application exists — the assertions are invariants
 * that hold whatever is installed: every returned package really has a launcher entry, Nivara is
 * not among them, identities are unique and the order is the documented one.
 *
 * Run with `./gradlew :app:connectedDebugAndroidTest`. Without an attached device these tests are
 * compiled but never executed, and that is exactly what they then prove: nothing.
 */
@RunWith(AndroidJUnit4::class)
class AndroidApplicationRepositoryTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = AndroidApplicationRepository(context)

    @Test
    fun discovery_returns_launchable_applications_with_a_usable_identity() = runBlocking {
        val applications = repository.installedApplications().valueOrNull()
            ?: error("discovery reported a failure on a working device")

        assertTrue("an application without a package name was returned",
            applications.none { it.packageName.isBlank() })
        assertTrue("an application without a label was returned",
            applications.none { it.label.isBlank() })
        assertEquals("an application was returned twice",
            applications.size, applications.map { it.packageName }.toSet().size)
    }

    @Test
    fun every_discovered_package_really_has_a_launcher_entry() = runBlocking {
        val applications = repository.installedApplications().valueOrNull()
            ?: error("discovery reported a failure on a working device")
        val launchablePackages = launcherPackages(context.packageManager)

        applications.forEach { application ->
            assertTrue(
                "${application.packageName} has no launcher entry on this device",
                application.packageName in launchablePackages,
            )
        }
    }

    @Test
    fun nivara_is_not_part_of_the_list_it_would_show() = runBlocking {
        val applications = repository.installedApplications().valueOrNull()
            ?: error("discovery reported a failure on a working device")

        assertTrue(
            "Nivara appears in its own discovery result",
            applications.none { it.packageName == context.packageName },
        )
    }

    @Test
    fun the_default_order_is_the_same_on_every_query() = runBlocking {
        val first = repository.installedApplications().valueOrNull()
            ?: error("discovery reported a failure on a working device")

        val second = repository.installedApplications().valueOrNull()
            ?: error("discovery reported a failure on a working device")

        assertEquals(first, second)
        assertEquals(first.inDefaultApplicationOrder(), first)
    }

    /** The packages with a launcher entry, read the same way the repository reads them. */
    private fun launcherPackages(packageManager: PackageManager): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, 0)
        }
        return resolved.mapNotNull { info -> info.activityInfo?.packageName }.toSet()
    }
}
