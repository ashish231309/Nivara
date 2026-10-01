package com.nivara.app.data.vault.viewer

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.data.vault.BoundedCollector
import com.nivara.app.data.security.JcaEncryptionService
import com.nivara.app.data.vault.FakeReadyVaultRepository
import com.nivara.app.data.vault.FakeVaultContentStorage
import com.nivara.app.data.vault.FakeVaultKeyAccess
import com.nivara.app.data.vault.FakeVaultRootStorage
import com.nivara.app.data.vault.FakeVaultSourceOpener
import com.nivara.app.data.vault.FakeVaultContentSource
import com.nivara.app.data.vault.asContentFailure
import com.nivara.app.data.vault.NivaraVaultIndexRepository
import com.nivara.app.domain.security.CryptographicFailure
import com.nivara.app.domain.security.SecureRandomGenerator
import com.nivara.app.domain.vault.VaultContentException
import com.nivara.app.domain.vault.VaultContentFailure
import com.nivara.app.domain.vault.VaultContentReader
import com.nivara.app.domain.vault.VaultItem
import com.nivara.app.domain.vault.VaultItemId
import com.nivara.app.domain.vault.VaultSourceReference
import com.nivara.app.testing.valueOrFail
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for the engine layer: the bounds a decode obeys, the bound a text preview obeys,
 * and the vocabulary a failure is translated into on its way to a screen.
 *
 * The platform decoders themselves (`BitmapFactory`, `MediaPlayer`, `PdfRenderer`) cannot run on the
 * JVM, so what is verified here is everything around them: the arithmetic that decides how large a
 * decode may be, the buffer that decides how much text is kept, and the mapping that decides what a
 * failure is *called*. The decoders are exercised by the instrumented suite on a device, and the
 * documentation says exactly that.
 */
class VaultViewerEngineTest {

    private val random = SecureRandomGenerator()
    private val encryptionService = JcaEncryptionService(random = random)
    private val metadata = FakeVaultRootStorage().apply {
        metadataDirectory = true
        contentDirectory = true
    }
    private val content = FakeVaultContentStorage()
    private val keyAccess = FakeVaultKeyAccess()
    private val opener = FakeVaultSourceOpener()

    private fun repository(): NivaraVaultIndexRepository = NivaraVaultIndexRepository(
        vaultRepository = FakeReadyVaultRepository(),
        keyAccess = keyAccess,
        metadataStorageFactory = { metadata },
        contentStorageFactory = { content },
        sourceOpener = opener,
        encryptionService = encryptionService,
        random = random,
        clock = { 1_700_000_000_000L },
    )

    private suspend fun importFile(bytes: ByteArray, name: String, mimeType: String?): VaultItem {
        opener.source = FakeVaultContentSource(bytes, displayName = name, mimeType = mimeType)
        return repository().importFile(
            source = VaultSourceReference.create("content://com.nivara.test/document/one")!!,
            authorize = { true },
        ).valueOrFail()
    }

    // ------------------------------------------------------------------ the decode bound

    @Test
    fun `a small picture is decoded at its own size`() {
        assertEquals(1, VaultImageBounded.sampleSize(width = 800, height = 600))
        assertEquals(1, VaultImageBounded.sampleSize(width = 2_048, height = 1_000))
    }

    @Test
    fun `a picture larger than the bound is sampled down, by dimension`() {
        val sample = VaultImageBounded.sampleSize(width = 8_192, height = 8_192)

        assertTrue("sampling must reduce the longest side", 8_192 / sample <= VaultImageBounded.MAXIMUM_DIMENSION)
        // Powers of two, so a decoder can use the sample exactly.
        assertEquals(0, sample and (sample - 1))
    }

    @Test
    fun `a long thin picture is bounded by its pixels as well`() {
        // Within the dimension bound on one side, far over the pixel bound in total.
        val sample = VaultImageBounded.sampleSize(width = 30_000, height = 1_000)

        val scaled = (30_000 / sample).toLong() * (1_000 / sample).toLong()
        assertTrue("the pixel bound is respected", scaled <= VaultImageBounded.MAXIMUM_PIXELS)
    }

    @Test
    fun `an absurd picture is refused rather than sampled forever`() {
        assertFalse(VaultImageBounded.isDecodable(width = 0, height = 100))
        assertFalse(VaultImageBounded.isDecodable(width = 100, height = 0))
        assertFalse(VaultImageBounded.isDecodable(width = -5, height = 100))
        assertFalse(VaultImageBounded.isDecodable(width = 100_000, height = 100))
        assertTrue(VaultImageBounded.isDecodable(width = 100, height = 100))
    }

    @Test
    fun `the worst picture a decoder will consider is still sampled within the bound`() {
        // The largest dimensions a decoder is allowed to declare at all: past this the file is
        // refused rather than sampled, so this is the heaviest decode that can happen.
        val sample = VaultImageBounded.sampleSize(
            width = VaultImageBounded.MAXIMUM_DECLARED_DIMENSION,
            height = VaultImageBounded.MAXIMUM_DECLARED_DIMENSION,
        )

        assertTrue("the sampling stays inside what the loop will do", sample <= VaultImageBounded.MAXIMUM_SAMPLE)
        assertTrue(sample > 1)
        val scaled = (VaultImageBounded.MAXIMUM_DECLARED_DIMENSION / sample).toLong()
        assertTrue("the decoded image fits the pixel bound", scaled * scaled <= VaultImageBounded.MAXIMUM_PIXELS)
    }

    // ------------------------------------------------------------------ the bounded text preview

    @Test
    fun `a text preview keeps what fits and counts the rest`() {
        val collector = BoundedCollector(maximumBytes = 10)

        collector.write("abcdefghijklmnopqrstuvwxyz".toByteArray(), 0, 26)

        assertEquals("abcdefghij", String(collector.bytes, Charsets.UTF_8))
        assertEquals(26L, collector.totalBytes)
        assertTrue("the file was longer than the preview", collector.truncated)
    }

    @Test
    fun `a text file that fits is not marked as truncated`() {
        val collector = BoundedCollector(maximumBytes = 100)

        collector.write("short".toByteArray(), 0, 5)

        assertEquals("short", String(collector.bytes, Charsets.UTF_8))
        assertEquals(5L, collector.totalBytes)
        assertFalse(collector.truncated)
    }

    @Test
    fun `the preview's memory does not grow with the file`() {
        val collector = BoundedCollector(maximumBytes = 1_024)

        repeat(1_000) { collector.write(ByteArray(4_096) { 9 }, 0, 4_096) }

        assertEquals(1_024, collector.bytes.size)
        assertEquals(4_096_000L, collector.totalBytes)
        assertTrue(collector.truncated)
    }

    // ------------------------------------------------------------------ the failure vocabulary

    @Test
    fun `a cryptographic failure is named for what it is`() {
        assertEquals(
            VaultContentFailure.Corrupt,
            CryptographicFailure.AuthenticationFailed.asContentFailure(),
        )
        assertEquals(
            VaultContentFailure.Corrupt,
            CryptographicFailure.MalformedEnvelope.asContentFailure(),
        )
        assertEquals(
            VaultContentFailure.KeyUnavailable,
            CryptographicFailure.InvalidKey.asContentFailure(),
        )
    }

    @Test
    fun `a typed content failure travels as itself and anything unknown is unreadable`() {
        assertEquals(
            VaultContentFailure.ContentMissing,
            VaultContentException(VaultContentFailure.ContentMissing).asContentFailure(),
        )
        assertEquals(VaultContentFailure.Unreadable, IllegalStateException("?").asContentFailure())
        assertEquals(VaultContentFailure.Unreadable, (null as Exception?).asContentFailure())
    }

    @Test
    fun `a vault that cannot be opened and a key that is gone are named differently`() {
        assertEquals(
            VaultContentFailure.KeyUnavailable,
            com.nivara.app.domain.vault.VaultFailure.KeyUnavailable.asContentFailure(),
        )
        assertEquals(
            VaultContentFailure.Unreadable,
            com.nivara.app.domain.vault.VaultFailure.AccessDenied.asContentFailure(),
        )
        assertEquals(
            VaultContentFailure.KeyUnavailable,
            com.nivara.app.domain.vault.VaultFailure.VaultUnreadable(
                com.nivara.app.domain.vault.VaultUnreadableReason.KeyUnavailable,
            ).asContentFailure(),
        )
    }

    @Test
    fun `a typed failure a platform decoder wrapped is still named for what it is`() {
        val wrapped = IOException(
            "the encrypted object could not be read",
            VaultContentException(VaultContentFailure.Corrupt),
        )

        assertEquals(
            "bytes that did not authenticate are corruption, not storage",
            VaultContentFailure.Corrupt,
            wrapped.asViewerContentFailure(),
        )
        assertEquals(
            VaultContentFailure.Unreadable,
            IOException("no typed reason").asViewerContentFailure(),
        )
    }

    @Test
    fun `a viewer failure keeps the distinction the stage is built on`() {
        val content = VaultViewerException(VaultViewerFailure.Content(VaultContentFailure.Corrupt))
        val decode = VaultViewerException(VaultViewerFailure.DecodeFailed)

        assertEquals(VaultViewerFailure.Content(VaultContentFailure.Corrupt), content.failure)
        assertEquals(VaultViewerFailure.DecodeFailed, decode.failure)
        assertNotEquals(
            "a decoder refusing a file is not a damaged vault",
            decode.failure,
            VaultViewerFailure.Content(VaultContentFailure.Corrupt),
        )
    }

    @Test
    fun `a failed result reports its failure, and a successful one reports none`() = runTest {
        val failure = NivaraResult.Failure(
            VaultViewerException(VaultViewerFailure.Content(VaultContentFailure.Unreadable)),
        )
        val success = NivaraResult.Success("content")

        assertEquals(
            VaultViewerFailure.Content(VaultContentFailure.Unreadable),
            failure.viewerFailure(),
        )
        assertNull(success.viewerFailure())
    }

    // ------------------------------------------------------------------ the image engine's seam

    @Test
    fun `the image engine reads through the content reader and never opens storage itself`() = runTest {
        val item = importFile("picture bytes".toByteArray(), name = "a.png", mimeType = "image/png")
        val reader = RecordingReader(repository())

        val engine = NivaraVaultImageEngine(reader = reader)
        // The decode itself is the platform's and cannot run here; what is verified is that the
        // engine reaches the content through the reader, with the item's identifier and its size.
        runCatching { engine.decode(item.id, item.sizeBytes, authorize = { true }) }

        assertEquals(1, reader.opens)
        assertEquals(item.id, reader.lastItemId)
        assertEquals(item.sizeBytes, reader.lastSizeBytes)
    }

    @Test
    fun `the image engine releases a decoded image safely when there is none`() {
        val engine = NivaraVaultImageEngine(reader = RecordingReader(null))

        // Releasing twice, and releasing before anything was decoded, must both be no-ops.
        engine.release()
        engine.release()
    }

    /** A reader that records what it was asked for and defers to the real one. */
    private class RecordingReader(
        private val delegate: VaultContentReader?,
    ) : VaultContentReader {

        var opens: Int = 0
            private set

        var lastItemId: VaultItemId? = null
            private set

        var lastSizeBytes: Long = 0L
            private set

        override suspend fun <T> withContent(
            itemId: VaultItemId,
            sizeBytes: Long,
            authorize: () -> Boolean,
            block: suspend (com.nivara.app.domain.vault.VaultContentHandle) -> NivaraResult<T>,
        ): NivaraResult<T> {
            opens += 1
            lastItemId = itemId
            lastSizeBytes = sizeBytes
            if (delegate != null) return delegate.withContent(itemId, sizeBytes, authorize, block)
            return NivaraResult.Failure(VaultViewerException(VaultViewerFailure.DecodeFailed))
        }
    }
}
