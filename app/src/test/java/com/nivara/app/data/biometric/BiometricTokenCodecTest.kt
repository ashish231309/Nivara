package com.nivara.app.data.biometric

import com.nivara.app.core.common.isSuccess
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests for the file that holds the biometric record, and for the store that writes it.
 *
 * The record contains no key material: it is a random token the platform key encrypts, together
 * with the IV of that one operation. What these tests pin down is that the format is strict — a
 * file written by a different construction, a truncated file or a corrupted byte is rejected
 * instead of guessed at — and that the store survives the cases that matter: no file, a deleted
 * file and a damaged file.
 */
class BiometricTokenCodecTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val recordFile: File get() = File(temporaryFolder.root, "biometric.token")

    private val token = BiometricToken(
        iv = ByteArray(BiometricTokenCodec.IV_BYTES) { index -> (index + 1).toByte() },
        ciphertext = ByteArray(BiometricTokenCodec.CIPHERTEXT_BYTES) { index -> (index * 3 + 5).toByte() },
    )

    @Test
    fun `a record round-trips exactly`() {
        val decoded = BiometricTokenCodec.decode(BiometricTokenCodec.encode(token))

        assertTrue("a record this build wrote must decode", decoded != null)
        assertArrayEquals(token.iv, decoded!!.iv)
        assertArrayEquals(token.ciphertext, decoded.ciphertext)
    }

    @Test
    fun `the encoded form has the documented size`() {
        // 4 magic + 1 version + 1 IV length + 12 IV + 1 ciphertext length + 48 ciphertext + 4 CRC.
        assertEquals(71, BiometricTokenCodec.encode(token).size)
        assertEquals(71, BiometricTokenCodec.LENGTH_BYTES)
    }

    @Test
    fun `a truncated or padded record is rejected`() {
        val encoded = BiometricTokenCodec.encode(token)

        assertNull(BiometricTokenCodec.decode(encoded.copyOf(encoded.size - 1)))
        assertNull(BiometricTokenCodec.decode(encoded + byteArrayOf(0)))
        assertNull(BiometricTokenCodec.decode(ByteArray(0)))
    }

    @Test
    fun `a foreign magic or version is rejected`() {
        val encoded = BiometricTokenCodec.encode(token)

        val wrongMagic = encoded.copyOf().also { it[0] = 'X'.code.toByte() }
        assertNull(BiometricTokenCodec.decode(wrongMagic))

        val wrongVersion = encoded.copyOf().also { it[4] = (BiometricTokenCodec.FORMAT_VERSION + 1).toByte() }
        assertNull(BiometricTokenCodec.decode(wrongVersion))
    }

    @Test
    fun `a record whose lengths disagree with the format is rejected`() {
        val encoded = BiometricTokenCodec.encode(token)

        // The IV length byte (offset 5) and the ciphertext length byte (offset 18).
        val wrongIvLength = encoded.copyOf().also { it[5] = (BiometricTokenCodec.IV_BYTES + 1).toByte() }
        assertNull(BiometricTokenCodec.decode(wrongIvLength))

        val wrongCiphertextLength =
            encoded.copyOf().also { it[18] = (BiometricTokenCodec.CIPHERTEXT_BYTES - 1).toByte() }
        assertNull(BiometricTokenCodec.decode(wrongCiphertextLength))
    }

    @Test
    fun `a corrupted byte is rejected by the checksum`() {
        val encoded = BiometricTokenCodec.encode(token)
        val flipped = encoded.copyOf().also { it[10] = (it[10] + 1).toByte() }

        assertNull(BiometricTokenCodec.decode(flipped))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an IV of the wrong size cannot be encoded`() {
        BiometricTokenCodec.encode(
            BiometricToken(
                iv = ByteArray(BiometricTokenCodec.IV_BYTES - 1),
                ciphertext = token.ciphertext,
            ),
        )
    }

    @Test
    fun `nothing stored reads as nothing stored`() = runTest {
        val store = BiometricTokenStore(recordFile)

        assertNull(store.load())
        assertFalse(recordFile.exists())
    }

    @Test
    fun `a saved record can be read back and removed`() = runTest {
        val store = BiometricTokenStore(recordFile)

        assertTrue(store.save(token).isSuccess)

        val loaded = store.load()
        assertTrue("a saved record must be readable", loaded != null)
        assertArrayEquals(token.iv, loaded!!.iv)
        assertArrayEquals(token.ciphertext, loaded.ciphertext)

        store.delete()

        assertNull(store.load())
        assertFalse(recordFile.exists())
    }

    @Test
    fun `a damaged record reads as nothing stored rather than failing`() = runTest {
        val store = BiometricTokenStore(recordFile)
        val corrupted = BiometricTokenCodec.encode(token).also { it[10] = (it[10] + 1).toByte() }
        recordFile.writeBytes(corrupted)

        // A damaged record reads as "not enabled", never as a hard error: the worst consequence is
        // that the user turns biometric unlock on again, and nothing is left stuck.
        assertNull(store.load())
    }
}
