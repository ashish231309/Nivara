package com.nivara.app.core.common

import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for the result wrapper used across Nivara's layers.
 */
class NivaraResultTest {

    @Test
    fun `successful block produces a success result carrying the value`() {
        val result = nivaraRunCatching { "nivara" }

        assertTrue(result.isSuccess)
        assertFalse(result.isFailure)
        assertEquals("nivara", result.valueOrNull())
    }

    @Test
    fun `throwing block produces a failure result and keeps the cause`() {
        val cause = IllegalStateException("keystore unavailable")

        val result = nivaraRunCatching { throw cause }

        assertFalse(result.isSuccess)
        assertNull(result.valueOrNull())
        val failure = result as? NivaraResult.Failure
        assertEquals(cause, failure?.error)
    }

    @Test
    fun `fold runs the matching branch`() {
        val success = nivaraRunCatching { 7 }
        val failure = nivaraRunCatching { throw IllegalStateException("nope") }

        assertEquals("value 7", success.fold(onSuccess = { "value $it" }, onFailure = { "failed" }))
        assertEquals("failed", failure.fold(onSuccess = { "value $it" }, onFailure = { "failed" }))
    }

    @Test
    fun `cancellation is rethrown instead of being reported as a failure`() {
        val cancellation = CancellationException("cancelled")

        assertThrows(CancellationException::class.java) {
            nivaraRunCatching { throw cancellation }
        }
    }

    @Test
    fun `failure can be created without a cause`() {
        val result: NivaraResult<Boolean> = NivaraResult.Failure()

        assertFalse(result.isSuccess)
        assertNull(result.valueOrNull())
    }
}
