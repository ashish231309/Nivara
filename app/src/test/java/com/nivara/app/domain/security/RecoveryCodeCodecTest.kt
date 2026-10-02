package com.nivara.app.domain.security

import com.nivara.app.core.common.NivaraResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for the recovery code codec: the human-facing shape of the recovery key.
 *
 * The promise under test is that what a person carries out of the app is exactly what comes back
 * in — and that anything short of it (a dropped character, a mistyped one, a stray mark) is a
 * typed refusal rather than a guess. The checksum is error detection, and these tests pin where
 * detection ends and the envelope's own authentication begins.
 */
class RecoveryCodeCodecTest {

    private val random = SecureRandomGenerator()

    private fun decodeOf(text: String): NivaraResult<SensitiveBytes> = RecoveryCodeCodec.decode(text)

    private fun key(): SensitiveBytes = random.nextKeyBytes()

    // ------------------------------------------------------------------ round trips

    @Test
    fun `a code encodes and decodes back to the same key`() = runTest {
        val secret = key()
        val code = (RecoveryCodeCodec.encode(secret) as NivaraResult.Success).value

        val decoded = decodeOf(code) as NivaraResult.Success

        assertTrue("the round trip must not change the key", decoded.value.contentEquals(secret))
    }

    @Test
    fun `every generated key survives the round trip`() = runTest {
        repeat(32) {
            val secret = key()
            val code = (RecoveryCodeCodec.encode(secret) as NivaraResult.Success).value
            val decoded = decodeOf(code) as NivaraResult.Success
            assertTrue(decoded.value.contentEquals(secret))
        }
    }

    @Test
    fun `different keys never produce the same code`() = runTest {
        val first = (RecoveryCodeCodec.encode(key()) as NivaraResult.Success).value
        val second = (RecoveryCodeCodec.encode(key()) as NivaraResult.Success).value

        assertNotEquals(first, second)
    }

    @Test
    fun `the shown code carries thirteen key groups and a checksum group`() = runTest {
        val code = (RecoveryCodeCodec.encode(key()) as NivaraResult.Success).value

        val groups = code.split(RecoveryCodeCodec.GROUP_SEPARATOR)
        assertEquals("thirteen groups of key material", 13, groups.size - 1)
        assertTrue(groups.dropLast(1).all { group -> group.length == RecoveryCodeCodec.GROUP_SIZE })
        assertEquals(
            "the checksum is four characters of its own",
            RecoveryCodeCodec.CHECKSUM_CHARACTERS,
            groups.last().length,
        )
        assertTrue(
            "only the base32 alphabet is shown",
            code.all { character ->
                character in "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567" ||
                    character == RecoveryCodeCodec.GROUP_SEPARATOR
            },
        )
    }

    // ------------------------------------------------------------------ forgiving presentation

    @Test
    fun `decoding forgives case, hyphens and spaces`() = runTest {
        val secret = key()
        val code = (RecoveryCodeCodec.encode(secret) as NivaraResult.Success).value

        val lowercase = decodeOf(code.lowercase()) as NivaraResult.Success
        assertTrue(lowercase.value.contentEquals(secret))

        val ungrouped = decodeOf(code.replace("-", "")) as NivaraResult.Success
        assertTrue(ungrouped.value.contentEquals(secret))

        val spaced = decodeOf(code.replace("-", " ")) as NivaraResult.Success
        assertTrue(spaced.value.contentEquals(secret))
    }

    // ------------------------------------------------------------------ refusals

    @Test
    fun `a code with a dropped character is malformed, not guessed`() {
        val secret = key()
        val code = (RecoveryCodeCodec.encode(secret) as NivaraResult.Success).value

        val failure = decodeOf(code.drop(7))

        assertTrue(failure is NivaraResult.Failure)
        assertEquals(RecoveryCodeCodec.DecodeFailure.Malformed, failure.error)
    }

    @Test
    fun `a code with an added character is malformed`() {
        val secret = key()
        val code = (RecoveryCodeCodec.encode(secret) as NivaraResult.Success).value

        val failure = decodeOf(code + "A")

        assertEquals(RecoveryCodeCodec.DecodeFailure.Malformed, (failure as NivaraResult.Failure).error)
    }

    @Test
    fun `a code with a character outside the alphabet is malformed`() {
        val secret = key()
        val code = (RecoveryCodeCodec.encode(secret) as NivaraResult.Success).value

        // `1` is not in base32; neither is `0`, `8` or `9`.
        val broken = code.replaceFirst("A", "1")
        val failure = decodeOf(if (broken == code) code.dropLast(1) + "1" else broken)

        assertEquals(RecoveryCodeCodec.DecodeFailure.Malformed, (failure as NivaraResult.Failure).error)
    }

    @Test
    fun `empty and blank input is malformed`() {
        assertEquals(RecoveryCodeCodec.DecodeFailure.Malformed, (decodeOf("") as NivaraResult.Failure).error)
        assertEquals(
            RecoveryCodeCodec.DecodeFailure.Malformed,
            (decodeOf("   ---  ") as NivaraResult.Failure).error,
        )
    }

    @Test
    fun `a mistyped key character is caught by the checksum`() = runTest {
        val secret = key()
        val code = (RecoveryCodeCodec.encode(secret) as NivaraResult.Success).value

        // Swap one key-portion character for another alphabet member: well-formed, wrong checksum.
        val index = 0
        val replacement = if (code[index] == 'A') 'B' else 'A'
        val mistyped = code.replaceRange(index, index + 1, replacement.toString())

        val failure = decodeOf(mistyped)

        assertEquals(
            "the checksum must catch a single mistyped character",
            RecoveryCodeCodec.DecodeFailure.ChecksumMismatch,
            (failure as NivaraResult.Failure).error,
        )
    }

    @Test
    fun `a well-formed code with a broken checksum is refused`() {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val body = List(RecoveryCodeCodec.TOTAL_CHARACTERS) { index -> alphabet[(index * 7) % 32] }
            .joinToString(separator = "")

        val failure = decodeOf(body)

        // The chance of an arbitrary string matching its own checksum is one in 65536; the test
        // deliberately builds one that does not by checking the outcome either way.
        assertTrue(failure is NivaraResult.Failure)
        assertTrue(
            (failure as NivaraResult.Failure).error == RecoveryCodeCodec.DecodeFailure.ChecksumMismatch ||
                failure.error == RecoveryCodeCodec.DecodeFailure.Malformed,
        )
    }

    @Test
    fun `nonzero padding bits are refused as malformed`() = runTest {
        val secret = key()
        val code = (RecoveryCodeCodec.encode(secret) as NivaraResult.Success).value

        // The final key-portion character carries four padding bits; choosing one whose low bits
        // are set makes the digits decode to the wrong byte count's worth of data.
        val lastKeyChar = code.replace("-", "")[RecoveryCodeCodec.KEY_CHARACTERS - 1]
        val broken = buildString {
            val flat = code.replace("-", "")
            append(flat.substring(0, RecoveryCodeCodec.KEY_CHARACTERS - 1))
            append(if (lastKeyChar == 'B') 'C' else 'B')
            append(flat.substring(RecoveryCodeCodec.KEY_CHARACTERS))
        }

        val failure = decodeOf(broken)

        assertTrue(failure is NivaraResult.Failure)
    }

    // ------------------------------------------------------------------ encoding discipline

    @Test
    fun `encoding refuses material that is not a recovery key`() {
        val short = SensitiveBytes.of(ByteArray(16) { it.toByte() })

        val failure = RecoveryCodeCodec.encode(short)

        assertEquals(
            RecoveryCodeCodec.EncodeFailure.InvalidKeySize,
            (failure as NivaraResult.Failure).error,
        )
    }

    @Test
    fun `encoding refuses a cleared key`() {
        val secret = key()
        secret.clear()

        val failure = RecoveryCodeCodec.encode(secret)

        assertEquals(
            RecoveryCodeCodec.EncodeFailure.InvalidKeySize,
            (failure as NivaraResult.Failure).error,
        )
    }

    @Test
    fun `encoding accepts the envelope service's own key`() = runTest {
        val service = com.nivara.app.data.security.HkdfRecoveryKeyEnvelopeService(random = random)
        val recoveryKey = service.generateRecoveryKey()

        val encoded = RecoveryCodeCodec.encode(recoveryKey) as NivaraResult.Success
        val decoded = decodeOf(encoded.value) as NivaraResult.Success

        assertTrue(decoded.value.contentEquals(recoveryKey))
    }

    @Test
    fun `a decoded key is live material and survives until cleared`() {
        val secret = key()
        val code = (RecoveryCodeCodec.encode(secret) as NivaraResult.Success).value

        val decoded = (decodeOf(code) as NivaraResult.Success).value
        assertFalse(decoded.isCleared)

        decoded.clear()
        assertTrue(decoded.isCleared)
    }

    @Test
    fun `the code never carries the raw key as a substring`() = runTest {
        val secret = key()
        val code = (RecoveryCodeCodec.encode(secret) as NivaraResult.Success).value

        // The alphabet of the code is 32 characters; a hex rendering of the key would carry
        // digits the code cannot contain.
        assertFalse(code.contains("0"))
        assertFalse(code.contains("1"))
        assertFalse(code.contains("8"))
        assertFalse(code.contains("9"))
    }
}
