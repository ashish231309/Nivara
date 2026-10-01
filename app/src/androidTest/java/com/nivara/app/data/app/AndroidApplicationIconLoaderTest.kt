package com.nivara.app.data.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.util.TypedValue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the platform icon loader.
 *
 * Rendering an application's icon is something only a device can answer: the icon may be a vector, a
 * bitmap, an adaptive icon or a themed one, and the platform decides how to draw it. These tests ask
 * the real package manager for the application's own icon and for a package that cannot exist, and
 * check that the loader answers with a bitmap or with nothing — never with an exception.
 *
 * No test installs, removes or modifies an application, and none of them asserts anything about the
 * *content* of an icon: an icon is decoration, and a test that compared pixels would be asserting
 * artwork rather than behaviour.
 *
 * Run with `./gradlew :app:connectedDebugAndroidTest`. The suite is compiled by CI but executed only
 * when a device is attached, so none of this is claimed as verified until then.
 */
@RunWith(AndroidJUnit4::class)
class AndroidApplicationIconLoaderTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val loader = AndroidApplicationIconLoader(context)

    @Test
    fun the_application_s_own_icon_can_be_loaded() = runBlocking {
        val icon = loader.iconFor(context.packageName)

        assertNotNull("Nivara's own icon should be loadable on any device", icon)
    }

    @Test
    fun a_loaded_icon_has_the_size_a_row_asks_for() = runBlocking {
        val expected = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            ICON_SIZE_DP.toFloat(),
            context.resources.displayMetrics,
        ).toInt()

        val icon = loader.iconFor(context.packageName)

        assertEquals(expected, icon?.width)
        assertEquals(expected, icon?.height)
    }

    @Test
    fun a_package_that_does_not_exist_produces_no_icon_and_no_crash() = runBlocking {
        val icon = loader.iconFor("com.nivara.this.package.does.not.exist")

        assertNull("a missing icon must be an ordinary answer, not a failure", icon)
    }

    @Test
    fun loading_the_same_icon_twice_gives_the_same_answer() = runBlocking {
        val first = loader.iconFor(context.packageName)
        val second = loader.iconFor(context.packageName)

        assertNotNull(first)
        assertEquals(first?.width, second?.width)
        assertEquals(first?.height, second?.height)
    }

    private companion object {
        /** The size the loader is documented to render at, in density-independent points. */
        const val ICON_SIZE_DP = 48
    }
}
