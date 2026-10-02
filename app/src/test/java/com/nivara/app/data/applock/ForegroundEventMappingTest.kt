package com.nivara.app.data.applock

import com.nivara.app.domain.applock.ForegroundApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Local JVM tests for the pure part of foreground detection.
 *
 * These tests do not observe a device and do not claim to: the transitions are values a test
 * constructs. What is verified is the rule the detector applies once it has the platform's events —
 * which types it acts on, which it ignores, how a sequence of transitions resolves to the
 * application in front, and that an event carrying nothing usable cannot invent one.
 *
 * Whether Android really reports these event types, and whether it reports them for the
 * applications a user opens, is only knowable on a device; the instrumented suite checks the first
 * half of that against `UsageEvents.Event` and nothing can check the second without a person using
 * the phone.
 */
class ForegroundEventMappingTest {

    private val camera = ForegroundApplication("com.example.camera")
    private val notes = ForegroundApplication("com.example.notes")

    @Test
    fun `a resume is an application coming to the front`() {
        assertEquals(ForegroundSignal.Entered, foregroundSignalForEventType(USAGE_EVENT_ACTIVITY_RESUMED))
    }

    @Test
    fun `a pause is an application leaving the front`() {
        assertEquals(ForegroundSignal.Left, foregroundSignalForEventType(USAGE_EVENT_ACTIVITY_PAUSED))
    }

    @Test
    fun `every other event type is ignored`() {
        val ignored = listOf(0, 3, 4, 5, 7, 8, 11, 23, 25, -1, Int.MAX_VALUE)

        ignored.forEach { eventType ->
            assertEquals(
                "event type $eventType",
                ForegroundSignal.Ignored,
                foregroundSignalForEventType(eventType),
            )
        }
    }

    @Test
    fun `an application coming to the front becomes the foreground application`() {
        assertEquals(
            camera,
            applyForegroundSignal(current = null, signal = ForegroundSignal.Entered, packageName = "com.example.camera"),
        )
    }

    @Test
    fun `an application leaving the front clears it`() {
        assertNull(
            applyForegroundSignal(
                current = camera,
                signal = ForegroundSignal.Left,
                packageName = "com.example.camera",
            ),
        )
    }

    @Test
    fun `another application pausing does not clear the foreground application`() {
        assertEquals(
            camera,
            applyForegroundSignal(current = camera, signal = ForegroundSignal.Left, packageName = "com.example.notes"),
        )
    }

    @Test
    fun `an unusable package name never becomes the foreground application`() {
        val unusable = listOf(null, "", "   ", "com.example camera", ".")

        unusable.forEach { packageName ->
            assertNull(applyForegroundSignal(current = null, signal = ForegroundSignal.Entered, packageName = packageName))
        }
    }

    @Test
    fun `an unusable package name does not clear a real foreground application`() {
        assertEquals(
            camera,
            applyForegroundSignal(current = camera, signal = ForegroundSignal.Entered, packageName = null),
        )
    }

    @Test
    fun `an ignored signal changes nothing`() {
        assertEquals(camera, applyForegroundSignal(current = camera, signal = ForegroundSignal.Ignored, packageName = null))
        assertNull(applyForegroundSignal(current = null, signal = ForegroundSignal.Ignored, packageName = "com.example.camera"))
    }

    @Test
    fun `a sequence resolves to the newest application`() {
        val transitions = listOf(
            ForegroundTransition(10L, ForegroundSignal.Entered, "com.example.camera"),
            ForegroundTransition(20L, ForegroundSignal.Left, "com.example.camera"),
            ForegroundTransition(30L, ForegroundSignal.Entered, "com.example.notes"),
        )

        assertEquals(notes, resolveForeground(current = null, transitions = transitions))
    }

    @Test
    fun `transitions are applied in time order whatever order they arrive in`() {
        val transitions = listOf(
            ForegroundTransition(30L, ForegroundSignal.Entered, "com.example.notes"),
            ForegroundTransition(10L, ForegroundSignal.Entered, "com.example.camera"),
            ForegroundTransition(20L, ForegroundSignal.Left, "com.example.camera"),
        )

        assertEquals(notes, resolveForeground(current = null, transitions = transitions))
    }

    @Test
    fun `a burst of events for one application leaves it in front`() {
        val transitions = List(5) { index ->
            ForegroundTransition(index * 10L, ForegroundSignal.Entered, "com.example.camera")
        }

        assertEquals(camera, resolveForeground(current = null, transitions = transitions))
    }

    @Test
    fun `an empty window keeps the current answer`() {
        assertEquals(camera, resolveForeground(current = camera, transitions = emptyList()))
        assertNull(resolveForeground(current = null, transitions = emptyList()))
    }

    @Test
    fun `a window that ends with the application leaving reports nothing in front`() {
        val transitions = listOf(
            ForegroundTransition(10L, ForegroundSignal.Entered, "com.example.camera"),
            ForegroundTransition(20L, ForegroundSignal.Left, "com.example.camera"),
        )

        assertNull(resolveForeground(current = null, transitions = transitions))
    }
}
