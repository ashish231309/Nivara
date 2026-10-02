package com.nivara.app.ui.applock.overlay

import android.graphics.PixelFormat
import android.os.Build
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nivara.app.NivaraApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the protection window's configuration and for the host's cleanup.
 *
 * What a test can honestly check here without a person watching the screen is the window's
 * *configuration*: its type, its security flag, its size and the absence of the pass-through flag.
 * That is the part of "an overlay" that is written down in code and read back from the platform's
 * own constants. What is deliberately not asserted: that a window appears above a protected
 * application, because that needs the user's overlay grant and an application to cover — neither of
 * which a test may arrange.
 *
 * No test grants anything and none opens Android's settings screen: the grant is the user's
 * decision, and a test that could grant a permission would be a test that proves nothing about the
 * real one.
 *
 * Run with `./gradlew :app:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class AppLockOverlaySurfaceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun the_window_is_an_application_overlay() {
        val params = appLockOverlayLayoutParams()

        assertEquals(
            "an overlay window is the only unprivileged way to draw above another application",
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            params.type,
        )
    }

    @Test
    fun the_window_blocks_capture_and_does_not_let_input_through() {
        val params = appLockOverlayLayoutParams()

        assertTrue(
            "the protection surface must stay out of screenshots and recordings",
            params.flags and WindowManager.LayoutParams.FLAG_SECURE != 0,
        )
        assertFalse(
            "touches must be consumed by the surface, not passed to the application underneath",
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0,
        )
        assertEquals(
            "nothing of the protected application may show through",
            PixelFormat.OPAQUE,
            params.format,
        )
    }

    @Test
    fun the_window_covers_the_whole_display() {
        val params = appLockOverlayLayoutParams()

        assertEquals(WindowManager.LayoutParams.MATCH_PARENT, params.width)
        assertEquals(WindowManager.LayoutParams.MATCH_PARENT, params.height)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            assertEquals(
                "the surface covers the display, including the areas the system usually reserves",
                0,
                params.fitInsetsTypes,
            )
        } else {
            assertTrue(
                "below API 30 the same effect comes from FLAG_LAYOUT_IN_SCREEN",
                params.flags and WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN != 0,
            )
        }
    }

    @Test
    fun starting_and_stopping_the_host_without_a_requirement_is_harmless() {
        val host = (context.applicationContext as NivaraApplication).container.appLockOverlayHost

        // Nothing requires the surface in a fresh process: detection is stopped, and no application
        // is protected, so the host must attach no window — and its cleanup must be safe to run
        // twice and to run without a window ever having existed.
        host.start()
        host.start()
        host.stop()
        host.stop()
    }
}
