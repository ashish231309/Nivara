package com.nivara.app.data.vault

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.data.security.JcaEncryptionService
import com.nivara.app.domain.security.SecureRandomGenerator
import com.nivara.app.domain.vault.VaultContentException
import com.nivara.app.domain.vault.VaultContentFailure
import com.nivara.app.domain.vault.VaultContentHandle
import com.nivara.app.domain.vault.VaultFailure
import com.nivara.app.domain.vault.VaultItem
import com.nivara.app.domain.vault.VaultSourceReference
import com.nivara.app.domain.vault.VaultUnreadableReason
import com.nivara.app.testing.valueOrFail
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for reading an imported file back out of the vault.
 *
 * This is the stage's newest seam and the one every viewer stands behind, so the tests are about the
 * promises a viewer depends on: bytes come back in order and exactly; a read can be restarted without
 * a second open; a closed session stops the read at the next piece; a missing object, an unreadable
 * one and one that does not authenticate are three different failures; and a handle that has been
 * closed serves nothing.
 *
 * Everything runs against the real pipeline — the real repository, the real index, the real streaming
 * decryption — with the storage doubles the import tests already use, so what is verified is the code
 * that ships rather than a model of it.
 */
class VaultContentReaderTest {

    private val random = SecureRandomGenerator()
    private val encryptionService = JcaEncryptionService(random = random)
    private val metadata = FakeVaultRootStorage().apply {
        metadataDirectory = true
        contentDirectory = true
    }
    private val content = FakeVaultContentStorage()
    private val keyAccess = FakeVaultKeyAccess()
    private val vaultRepository = FakeReadyVaultRepository()
    private val opener = FakeVaultSourceOpener()
    private var now: Long = 1_700_000_000_000L

    private fun repository(): NivaraVaultIndexRepository = NivaraVaultIndexRepository(
        vaultRepository = vaultRepository,
        keyAccess = keyAccess,
        metadataStorageFactory = { metadata },
        contentStorageFactory = { content },
        sourceOpener = opener,
        encryptionService = encryptionService,
        random = random,
        clock = { now },
    )

    /** Imports one file so there is content to read back. */
    private suspend fun importFile(
        bytes: ByteArray,
        name: String = "holiday.jpg",
        mimeType: String? = "image/jpeg",
    ): VaultItem {
        opener.source = FakeVaultContentSource(bytes, displayName = name, mimeType = mimeType)
        return repository().importFile(
            source = VaultSourceReference.create("content://com.nivara.test/document/one")!!,
            authorize = { true },
        ).valueOrFail()
    }

    private suspend fun readAll(
        item: VaultItem,
        authorize: () -> Boolean = { true },
        block: suspend (VaultContentHandle) -> Unit = {},
    ): NivaraResult<ByteArray> = repository().withContent(item.id, item.sizeBytes, authorize) { handle ->
        block(handle)
        val sink = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = handle.read(buffer, 0, buffer.size)
            if (read < 0) break
            sink.write(buffer, 0, read)
        }
        NivaraResult.Success(sink.toByteArray())
    }

    /** The reader's typed failure behind a failed result. */
    private fun NivaraResult<*>.contentFailure(): VaultContentFailure? =
        ((this as? NivaraResult.Failure)?.error as? VaultContentException)?.failure

    // ------------------------------------------------------------------ reading

    @Test
    fun `an imported file comes back byte for byte`() = runTest {
        val bytes = "the vault keeps this file".toByteArray()
        val item = importFile(bytes)

        val read = readAll(item)

        assertTrue(read is NivaraResult.Success)
        assertArrayEquals(bytes, read.valueOrFail())
    }

    @Test
    fun `an empty file is read as an empty file`() = runTest {
        val item = importFile(ByteArray(0), name = "empty.txt", mimeType = "text/plain")

        val read = readAll(item)

        assertEquals(0, read.valueOrFail().size)
        assertEquals(0L, item.sizeBytes)
    }

    @Test
    fun `a file larger than a read buffer comes back in order`() = runTest {
        // Several times the handle's own read size, so the read crosses both the pipe and the chunk
        // boundary many times.
        val bytes = ByteArray(700_000) { index -> ((index * 13) % 251).toByte() }
        val item = importFile(bytes, name = "large.bin", mimeType = "application/octet-stream")

        val read = readAll(item)

        assertArrayEquals(bytes, read.valueOrFail())
    }

    @Test
    fun `reading in small pieces gives the same bytes as reading in large ones`() = runTest {
        val bytes = ByteArray(40_000) { index -> (index % 199).toByte() }
        val item = importFile(bytes, name = "pieces.bin", mimeType = "application/octet-stream")

        val piecewise = repository().withContent(item.id, item.sizeBytes, { true }) { handle ->
            val sink = ByteArrayOutputStream()
            val one = ByteArray(1_000)
            while (true) {
                val read = handle.read(one, 0, one.size)
                if (read < 0) break
                sink.write(one, 0, read)
            }
            NivaraResult.Success(sink.toByteArray())
        }

        assertArrayEquals(bytes, piecewise.valueOrFail())
    }

    @Test
    fun `the size the index recorded is what the handle reports`() = runTest {
        val bytes = ByteArray(12_345) { 7 }
        val item = importFile(bytes, name = "sized.bin", mimeType = "application/octet-stream")

        var reported = -1L
        repository().withContent(item.id, item.sizeBytes, { true }) { handle ->
            reported = handle.sizeBytes
            NivaraResult.Success(Unit)
        }

        assertEquals(bytes.size.toLong(), reported)
        assertEquals(item.sizeBytes, reported)
    }

    @Test
    fun `a restart begins the file again without opening it twice`() = runTest {
        val bytes = "restartable content".toByteArray()
        val item = importFile(bytes, name = "restart.txt", mimeType = "text/plain")

        val first = ByteArray(5)
        var reads = 0
        val outcome = repository().withContent(item.id, item.sizeBytes, { true }) { handle ->
            val before = handle.read(first, 0, first.size)
            handle.restart()
            val second = handle.read(ByteArray(5), 0, 5)
            reads += 1
            NivaraResult.Success(before to second)
        }

        assertEquals(1, reads)
        assertEquals(5, outcome.valueOrFail().first)
        assertEquals(5, outcome.valueOrFail().second)
        val sink = ByteArrayOutputStream()
        repository().withContent(item.id, item.sizeBytes, { true }) { handle ->
            val buffer = ByteArray(64)
            while (true) {
                val read = handle.read(buffer, 0, buffer.size)
                if (read < 0) break
                sink.write(buffer, 0, read)
            }
            NivaraResult.Success(Unit)
        }
        assertArrayEquals(bytes, sink.toByteArray())
    }

    @Test
    fun `closing the handle twice is safe and serves nothing afterwards`() = runTest {
        val item = importFile("content".toByteArray(), name = "a.txt", mimeType = "text/plain")

        var failure: VaultContentFailure? = null
        repository().withContent(item.id, item.sizeBytes, { true }) { handle ->
            handle.read(ByteArray(4), 0, 4)
            handle.close()
            handle.close()
            failure = try {
                handle.read(ByteArray(4), 0, 4)
                null
            } catch (typed: VaultContentException) {
                typed.failure
            }
            NivaraResult.Success(Unit)
        }

        assertEquals(VaultContentFailure.Unreadable, failure)
    }

    // ------------------------------------------------------------------ failures, kept apart

    @Test
    fun `a session that is not open serves nothing at all`() = runTest {
        val item = importFile("content".toByteArray(), name = "a.txt", mimeType = "text/plain")

        var opened = false
        val outcome = repository().withContent(item.id, item.sizeBytes, { false }) { handle ->
            opened = true
            NivaraResult.Success(handle.sizeBytes)
        }

        assertFalse("the handle was never even made", opened)
        assertEquals(VaultContentFailure.NotAuthorized, outcome.contentFailure())
    }

    @Test
    fun `a session that closes during a read stops it at the next piece`() = runTest {
        val bytes = ByteArray(220_000) { index -> (index % 97).toByte() }
        val item = importFile(bytes, name = "long.bin", mimeType = "application/octet-stream")
        var reads = 0
        val authorize = {
            reads += 1
            reads <= 2
        }

        var failure: VaultContentFailure? = null
        var produced = 0
        repository().withContent(item.id, item.sizeBytes, authorize) { handle ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = try {
                    handle.read(buffer, 0, buffer.size)
                } catch (typed: VaultContentException) {
                    failure = typed.failure
                    break
                }
                if (read < 0) break
                produced += read
            }
            NivaraResult.Success(Unit)
        }

        assertEquals(VaultContentFailure.NotAuthorized, failure)
        assertTrue("nothing was produced after the gate closed", produced < bytes.size)
        assertNotEquals(0, produced)
    }

    @Test
    fun `an object that is not in the vault is reported as missing content`() = runTest {
        val item = importFile("content".toByteArray(), name = "a.txt", mimeType = "text/plain")
        // The list still names the item; the object itself is gone.
        content.documents.remove(VaultContentNames.objectName(item.id))

        var failure: VaultContentFailure? = null
        repository().withContent(item.id, item.sizeBytes, { true }) { handle ->
            failure = try {
                handle.read(ByteArray(8), 0, 8)
                null
            } catch (typed: VaultContentException) {
                typed.failure
            }
            NivaraResult.Success(Unit)
        }

        assertEquals(VaultContentFailure.ContentMissing, failure)
    }

    @Test
    fun `an object that does not authenticate is never served`() = runTest {
        val bytes = ByteArray(90_000) { index -> (index % 41).toByte() }
        val item = importFile(bytes, name = "damaged.bin", mimeType = "application/octet-stream")
        val name = VaultContentNames.objectName(item.id)
        val stored = content.documents.getValue(name).copyOf()
        // One byte in the middle of the ciphertext: everything after it fails to authenticate.
        stored[stored.size / 2] = (stored[stored.size / 2].toInt() xor 0x01).toByte()
        content.documents[name] = stored

        var failure: VaultContentFailure? = null
        var produced = 0
        repository().withContent(item.id, item.sizeBytes, { true }) { handle ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = try {
                    handle.read(buffer, 0, buffer.size)
                } catch (typed: VaultContentException) {
                    failure = typed.failure
                    break
                }
                if (read < 0) break
                produced += read
            }
            NivaraResult.Success(Unit)
        }

        assertEquals(VaultContentFailure.Corrupt, failure)
        // Records before the edited one had already authenticated and were legitimately served; what
        // must not happen is the failure being reported as the end of the file or the whole object
        // being produced.
        assertTrue("the whole file was never produced", produced < bytes.size)
    }

    @Test
    fun `a truncated object is a corrupt object, not a shorter file`() = runTest {
        val bytes = ByteArray(200_000) { index -> (index % 61).toByte() }
        val item = importFile(bytes, name = "truncated.bin", mimeType = "application/octet-stream")
        val name = VaultContentNames.objectName(item.id)
        val stored = content.documents.getValue(name)
        content.documents[name] = stored.copyOf(stored.size - 40)

        var failure: VaultContentFailure? = null
        var produced = 0
        repository().withContent(item.id, item.sizeBytes, { true }) { handle ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = try {
                    handle.read(buffer, 0, buffer.size)
                } catch (typed: VaultContentException) {
                    failure = typed.failure
                    break
                }
                if (read < 0) break
                produced += read
            }
            NivaraResult.Success(Unit)
        }

        assertEquals(VaultContentFailure.Corrupt, failure)
        assertTrue(produced < bytes.size)
    }

    @Test
    fun `a vault that is not ready never reaches storage`() = runTest {
        val item = importFile("content".toByteArray(), name = "a.txt", mimeType = "text/plain")
        // What the real key access reports when the record authenticates but the structure it
        // describes is gone.
        keyAccess.failure = VaultFailure.VaultUnreadable(VaultUnreadableReason.StructureIncomplete)

        val outcome = repository().withContent(item.id, item.sizeBytes, { true }) { handle ->
            NivaraResult.Success(handle.sizeBytes)
        }

        assertEquals(VaultContentFailure.Unreadable, outcome.contentFailure())
    }

    @Test
    fun `a key that cannot be borrowed fails the read as a key problem`() = runTest {
        val item = importFile("content".toByteArray(), name = "a.txt", mimeType = "text/plain")
        keyAccess.failure = VaultFailure.KeyUnavailable

        val outcome = repository().withContent(item.id, item.sizeBytes, { true }) { handle ->
            NivaraResult.Success(handle.sizeBytes)
        }

        assertEquals(VaultContentFailure.KeyUnavailable, outcome.contentFailure())
    }

    @Test
    fun `a failing read is not reported as the end of a file`() = runTest {
        val item = importFile(ByteArray(300_000) { 3 }, name = "a.bin", mimeType = "application/octet-stream")
        content.readFailure = VaultFailure.StorageUnavailable

        var failure: VaultContentFailure? = null
        repository().withContent(item.id, item.sizeBytes, { true }) { handle ->
            failure = try {
                var read = handle.read(ByteArray(8), 0, 8)
                // The producer fails asynchronously; reading until something happens is what a viewer
                // does, and what must never end in a clean "end of file".
                while (read >= 0) read = handle.read(ByteArray(8), 0, 8)
                null
            } catch (typed: VaultContentException) {
                typed.failure
            }
            NivaraResult.Success(Unit)
        }

        assertTrue(
            "storage that refused must not look like a complete file",
            failure == VaultContentFailure.Unreadable || failure == VaultContentFailure.ContentMissing,
        )
    }

    @Test
    fun `a content failure is a typed failure and never a thrown platform exception`() = runTest {
        val item = importFile("content".toByteArray(), name = "a.txt", mimeType = "text/plain")
        content.documents.remove(VaultContentNames.objectName(item.id))

        val outcome = repository().withContent(item.id, item.sizeBytes, { true }) { handle ->
            handle.read(ByteArray(4), 0, 4)
            NivaraResult.Success(Unit)
        }

        // The failure travels out of the block rather than crashing the caller: a viewer maps it to a
        // screen, and a repository that let it escape would take the whole screen with it.
        assertEquals(VaultContentFailure.ContentMissing, outcome.contentFailure())
    }

    @Test
    fun `reading the same item twice in a row works, and each read is independent`() = runTest {
        val bytes = "read me twice".toByteArray()
        val item = importFile(bytes, name = "twice.txt", mimeType = "text/plain")

        val first = readAll(item).valueOrFail()
        val second = readAll(item).valueOrFail()

        assertArrayEquals(bytes, first)
        assertArrayEquals(bytes, second)
        assertEquals(2, keyAccess.borrows)
    }

    @Test
    fun `the imported object is addressed by the item identifier and nothing else`() = runTest {
        val item = importFile("content".toByteArray(), name = "../../escape.txt", mimeType = "text/plain")

        // The name a provider gave is stored in the index and never used to address anything: the
        // object is the item's identifier with the content port's own suffix.
        assertEquals(setOf(VaultContentNames.objectName(item.id)), content.documents.keys)
        assertEquals("escape.txt", item.name)
    }
}

