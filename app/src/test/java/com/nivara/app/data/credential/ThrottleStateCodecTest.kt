package com.nivara.app.data.credential

import com.nivara.app.domain.credential.ThrottleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the persisted attempt counters.
 *
 * A corrupt counter file must never turn into a lock: every rejection below ends with the
 * tracker starting from a clean state, which is what the store turns `null` into.
 */
class ThrottleStateCodecTest {

    private val state = ThrottleState(consecutiveFailures = 5, blockedUntilMillis = 1_700_000_300_000L)

    @Test
    fun `round trips both numbers`() {
        val decoded = ThrottleStateCodec.decode(ThrottleStateCodec.encode(state))

        assertEquals(state, decoded)
    }

    @Test
    fun `encodes to a fixed size`() {
        assertEquals(ThrottleStateCodec.LENGTH_BYTES, ThrottleStateCodec.encode(state).size)
    }

    @Test
    fun `rejects data that is not a counter file`() {
        assertNull(ThrottleStateCodec.decode(ByteArray(0)))
        assertNull(ThrottleStateCodec.decode("NVAT".toByteArray()))
        assertNull(ThrottleStateCodec.decode(ByteArray(ThrottleStateCodec.LENGTH_BYTES)))
    }

    @Test
    fun `rejects a file of the wrong length`() {
        val encoded = ThrottleStateCodec.encode(state)

        assertNull(ThrottleStateCodec.decode(encoded.copyOf(encoded.size + 1)))
        assertNull(ThrottleStateCodec.decode(encoded.copyOf(encoded.size - 1)))
    }

    @Test
    fun `rejects an unknown version`() {
        val encoded = ThrottleStateCodec.encode(state)
        encoded[4] = 99

        assertNull(ThrottleStateCodec.decode(encoded))
    }

    @Test
    fun `rejects a corrupted counter`() {
        val encoded = ThrottleStateCodec.encode(state)
        encoded[6] = (encoded[6].toInt() xor 0x40).toByte()

        assertNull(ThrottleStateCodec.decode(encoded))
    }

    @Test
    fun `rejects a negative counter`() {
        val encoded = ThrottleStateCodec.encode(state)
        writeInt(encoded, 5, -1)
        writeInt(encoded, encoded.size - 4, checksum(encoded, 0, encoded.size - 4))

        assertNull(ThrottleStateCodec.decode(encoded))
    }

    @Test
    fun `rejects a negative block end`() {
        val encoded = ThrottleStateCodec.encode(state)
        writeLong(encoded, 9, -1L)
        writeInt(encoded, encoded.size - 4, checksum(encoded, 0, encoded.size - 4))

        assertNull(ThrottleStateCodec.decode(encoded))
    }
}
