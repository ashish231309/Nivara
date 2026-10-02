package com.nivara.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Local JVM tests for the motion scale.
 *
 * What is pinned here is the promise the rest of the application relies on: every duration passes
 * through one function, the device's animator setting is applied proportionally, a user who asked
 * for no animation gets an instant resolution rather than a shorter animation, and nothing the
 * application animates can be negative or silently vanish.
 */
class NivaraMotionTest {

    @Test
    fun `an ordinary scale keeps the base duration`() {
        assertEquals(
            NivaraMotion.STANDARD_MILLIS,
            scaledDurationMillis(NivaraMotion.STANDARD_MILLIS, 1f),
        )
    }

    @Test
    fun `a doubled scale doubles the duration`() {
        assertEquals(500, scaledDurationMillis(250, 2f))
    }

    @Test
    fun `a halved scale halves the duration`() {
        assertEquals(125, scaledDurationMillis(250, 0.5f))
    }

    @Test
    fun `a zero scale resolves instantly`() {
        assertEquals(
            "a user who asked for no animation gets none",
            NivaraMotion.INSTANT_MILLIS,
            scaledDurationMillis(NivaraMotion.STANDARD_MILLIS, 0f),
        )
    }

    @Test
    fun `a negative scale resolves instantly`() {
        assertEquals(NivaraMotion.INSTANT_MILLIS, scaledDurationMillis(250, -1f))
    }

    @Test
    fun `a zero base is instant at any scale`() {
        assertEquals(NivaraMotion.INSTANT_MILLIS, scaledDurationMillis(0, 1.5f))
    }

    @Test
    fun `a positive duration never rounds down to nothing`() {
        assertEquals(1, scaledDurationMillis(1, 0.01f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative duration is refused`() {
        scaledDurationMillis(-1, 1f)
    }
}
