package com.nivara.app.data.vault

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.data.security.JcaEncryptionService
import com.nivara.app.domain.security.SecureRandomGenerator
import com.nivara.app.domain.vault.VaultFailure
import com.nivara.app.domain.vault.VaultImportFailure
import com.nivara.app.domain.vault.VaultIndexState
import com.nivara.app.domain.vault.VaultIndexUnreadable
import com.nivara.app.domain.vault.VaultItem
import com.nivara.app.domain.vault.VaultSourceReference
import com.nivara.app.domain.vault.VaultState
import com.nivara.app.testing.valueOrFail
import java.security.SecureRandom
import com.nivara.app.domain.vault.VaultContentSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for the vault's import pipeline.
 *
 * This is where the stage's most important sentence is checked: *a file is in the vault only when its
 * encrypted object is complete on storage, has been read back and opened, and is named in the index.*
 * Every failure below is one a device cannot be asked to produce on command — a destination that
 * stops accepting bytes, one that reports success and stores nothing, one that stores something else,
 * one that will not rename, an index write that is refused or dropped, a session that closes while a
 * file is being encrypted — and each one has its own promise: the item is not in the list, the
 * previous list is intact, and content is never deleted merely because it is unindexed.
 */
class NivaraVaultIndexRepositoryTest {

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

    private fun repository(idsFrom: SecureRandomGenerator = random): NivaraVaultIndexRepository =
        NivaraVaultIndexRepository(
            vaultRepository = vaultRepository,
            keyAccess = keyAccess,
            metadataStorageFactory = { metadata },
            contentStorageFactory = { content },
            sourceOpener = opener,
            encryptionService = encryptionService,
            random = idsFrom,
            clock = { now },
        )

    // ------------------------------------------------------------------ a committed import

    @Test
    fun `an imported file becomes an item with the facts the index recorded`() = runTest {
        val bytes = "the contents of the imported file".toByteArray()
        opener.source = FakeVaultContentSource(bytes, displayName = "notes.txt", mimeType = "text/plain")

        val item = repository().importFile(source(), authorize = { true }).valueOrFail()

        assertEquals("notes.txt", item.name)
        assertEquals("text/plain", item.mimeType)
        assertEquals(bytes.size.toLong(), item.sizeBytes)
        assertEquals(now, item.importedAtEpochMillis)
        assertEquals(1, item.contentFormatVersion)
        assertTrue(item.id.value.isNotEmpty())

        val state = repository().read()
        assertTrue(state is VaultIndexState.Ready)
        assertEquals(listOf(item), (state as VaultIndexState.Ready).items)
        assertEquals(0, state.unindexedObjects)
        assertEquals(0, state.unfinishedObjects)
        assertTrue(state.missingContent.isEmpty())
    }

    @Test
    fun `the object is stored under the item's own name and nothing else is left behind`() = runTest {
        opener.source = FakeVaultContentSource("content".toByteArray(), displayName = "holiday.jpg", mimeType = "image/jpeg")

        val item = repository().importFile(source(), authorize = { true }).valueOrFail()

        assertEquals(setOf(item.id.value + ".nvo"), content.documents.keys)
        assertTrue("nothing was left under a temporary name", content.documents.keys.none { name -> name.endsWith(".pending") })
    }

    @Test
    fun `the source is closed whether the import succeeded or not`() = runTest {
        val source = FakeVaultContentSource("content".toByteArray(), displayName = "a.txt")
        opener.source = source

        repository().importFile(source(), authorize = { true })

        assertTrue(source.closed)
    }

    @Test
    fun `a file with no declared type is imported without one`() = runTest {
        opener.source = FakeVaultContentSource("content".toByteArray(), displayName = "blob", mimeType = "not a type")

        val item = repository().importFile(source(), authorize = { true }).valueOrFail()

        assertNull(item.mimeType)
    }

    @Test
    fun `an empty file is imported as an empty file`() = runTest {
        opener.source = FakeVaultContentSource(ByteArray(0), displayName = "empty.txt", declaredSizeBytes = 0L)

        val item = repository().importFile(source(), authorize = { true }).valueOrFail()

        assertEquals(0L, item.sizeBytes)
    }

    @Test
    fun `two imports with the same name are two items and one is never overwritten`() = runTest {
        opener.source = FakeVaultContentSource("first".toByteArray(), displayName = "report.pdf", mimeType = "application/pdf")
        val first = repository().importFile(source(), authorize = { true }).valueOrFail()
        now += 1_000
        opener.source = FakeVaultContentSource("second".toByteArray(), displayName = "report.pdf", mimeType = "application/pdf")
        val second = repository().importFile(source(), authorize = { true }).valueOrFail()

        assertNotEquals(first.id, second.id)
        assertEquals(setOf("report.pdf"), setOf(first.name, second.name))
        val state = repository().read() as VaultIndexState.Ready
        assertEquals(2, state.items.size)
        assertEquals(setOf(first.id, second.id), state.items.map { item -> item.id }.toSet())
        assertEquals(setOf(first.id.value + ".nvo", second.id.value + ".nvo"), content.documents.keys)
    }

    @Test
    fun `a file whose name is a path is stored by its own segment`() = runTest {
        opener.source = FakeVaultContentSource(
            "content".toByteArray(),
            displayName = "Documents/photos/holiday.jpg",
            mimeType = "image/jpeg",
        )

        val item = repository().importFile(source(), authorize = { true }).valueOrFail()

        assertEquals("holiday.jpg", item.name)
        assertEquals("the object is named from the identifier, not the file", 1, content.documents.size)
        assertTrue(content.documents.keys.single().startsWith(item.id.value))
    }

    @Test
    fun `no index exists at the moment an object is complete`() = runTest {
        opener.source = FakeVaultContentSource("content".toByteArray(), displayName = "a.txt")
        var checked = false
        content.onObjectFinalized = {
            checked = true
            assertTrue(
                "the index must not be written before the object it describes is complete",
                metadata.documents.keys.none { name -> name.startsWith("index.") },
            )
        }

        repository().importFile(source(), authorize = { true }).valueOrFail()

        assertTrue("the object was finalized, so the check ran", checked)
        assertTrue(metadata.documents.keys.any { name -> name.startsWith("index.") })
    }

    // ------------------------------------------------------------------ authorization

    @Test
    fun `nothing is written when the session is not authorized`() = runTest {
        opener.source = FakeVaultContentSource("content".toByteArray(), displayName = "a.txt")

        val result = repository().importFile(source(), authorize = { false })

        assertTrue(result is NivaraResult.Failure)
        assertEquals(VaultImportFailure.NotAuthorized, (result as NivaraResult.Failure).error)
        assertEquals(0, content.writeCalls)
        assertEquals(0, metadata.writeCalls)
    }

    @Test
    fun `a session that closes during encryption stops the import and removes what it wrote`() = runTest {
        opener.source = FakeVaultContentSource("content".toByteArray(), displayName = "a.txt")
        var answers = 0
        val authorize = {
            answers += 1
            answers == 1
        }

        val result = repository().importFile(source(), authorize = authorize)

        assertTrue(result is NivaraResult.Failure)
        assertEquals(VaultImportFailure.NotAuthorized, (result as NivaraResult.Failure).error)
        assertTrue("the abandoned object was removed", content.documents.isEmpty())
        assertTrue("no index was written", metadata.documents.keys.none { name -> name.startsWith("index.") })
        assertEquals(2, answers)
    }

    @Test
    fun `a session that closes before a second import leaves the first one untouched`() = runTest {
        opener.source = FakeVaultContentSource("first".toByteArray(), displayName = "a.txt")
        val first = repository().importFile(source(), authorize = { true }).valueOrFail()
        val before = metadata.snapshot()
        opener.source = FakeVaultContentSource("second".toByteArray(), displayName = "b.txt")

        val refused = repository().importFile(source(), authorize = { false })

        assertTrue(refused is NivaraResult.Failure)
        assertEquals(before, metadata.snapshot())
        val state = repository().read() as VaultIndexState.Ready
        assertEquals(listOf(first), state.items)
    }

    // ------------------------------------------------------------------ the source

    @Test
    fun `a source that cannot be opened fails without touching the vault`() = runTest {
        opener.failure = VaultImportFailure.SourceAccessDenied

        val result = repository().importFile(source(), authorize = { true })

        assertEquals(VaultImportFailure.SourceAccessDenied, (result as NivaraResult.Failure).error)
        assertEquals(0, content.writeCalls)
        assertEquals(0, metadata.writeCalls)
    }

    @Test
    fun `a source whose name cannot be stored is refused`() = runTest {
        opener.source = FakeVaultContentSource("content".toByteArray(), displayName = "..")

        val result = repository().importFile(source(), authorize = { true })

        assertEquals(VaultImportFailure.InvalidSourceName, (result as NivaraResult.Failure).error)
        assertEquals(0, content.writeCalls)
    }

    @Test
    fun `a source larger than the bound is refused before it is read`() = runTest {
        opener.source = FakeVaultContentSource(
            "content".toByteArray(),
            displayName = "huge.bin",
            declaredSizeBytes = VaultImportFailure.MAXIMUM_SOURCE_BYTES + 1,
        )

        val result = repository().importFile(source(), authorize = { true })

        assertEquals(VaultImportFailure.SourceTooLarge, (result as NivaraResult.Failure).error)
        assertEquals(0, content.writeCalls)
    }

    @Test
    fun `a source that stops being readable half way fails the whole import`() = runTest {
        opener.source = FakeVaultContentSource(
            ByteArray(250_000) { 1 },
            displayName = "vanishing.bin",
            failAfterBytes = 100_000,
        )

        val result = repository().importFile(source(), authorize = { true })

        assertTrue(result is NivaraResult.Failure)
        assertEquals(VaultImportFailure.SourceUnavailable, (result as NivaraResult.Failure).error)
        assertTrue("a failed import leaves no object", content.documents.isEmpty())
        assertTrue(metadata.documents.keys.none { name -> name.startsWith("index.") })
    }

    @Test
    fun `a source that is not as large as it declared is not recorded at the wrong size`() = runTest {
        opener.source = FakeVaultContentSource(
            "short".toByteArray(),
            displayName = "a.txt",
            declaredSizeBytes = 5_000L,
        )

        val result = repository().importFile(source(), authorize = { true })

        assertEquals(VaultImportFailure.VerificationFailed, (result as NivaraResult.Failure).error)
        assertTrue(content.documents.isEmpty())
    }

    @Test
    fun `an import that is cancelled leaves no item and nothing half-written`() = runTest {
        val waiting = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        opener.source = object : VaultContentSource {
            override val displayName: String = "interrupted.bin"
            override val mimeType: String? = "application/octet-stream"
            override val declaredSizeBytes: Long? = null

            override suspend fun read(buffer: ByteArray): Int {
                // The import is interrupted while it waits for the source, which is the worst
                // moment: the pending document exists and the index does not.
                started.complete(Unit)
                waiting.await()
                return -1
            }

            override suspend fun close() = Unit
        }

        val job = launch { repository().importFile(source(), authorize = { true }) }
        started.await()
        assertTrue(
            "the pending object exists while the write is in progress",
            content.documents.keys.any { name -> name.endsWith(".pending") },
        )
        job.cancelAndJoin()

        assertTrue("nothing is left under any name", content.documents.isEmpty())
        assertTrue(metadata.documents.keys.none { name -> name.startsWith("index.") })
        assertEquals(VaultIndexState.Missing, repository().read())
    }

    // ------------------------------------------------------------------ the destination

    @Test
    fun `a destination that refuses bytes reports a refused write`() = runTest {
        opener.source = FakeVaultContentSource(ByteArray(300_000) { 1 }, displayName = "big.bin")
        content.writeFailureAfterBytes = 128 * 1024

        val result = repository().importFile(source(), authorize = { true })

        assertTrue(result is NivaraResult.Failure)
        assertEquals(VaultImportFailure.WriteFailed, (result as NivaraResult.Failure).error)
        assertTrue("a refused write leaves no object", content.documents.isEmpty())
        assertTrue(metadata.documents.keys.none { name -> name.startsWith("index.") })
    }

    @Test
    fun `a destination that stores nothing is caught by the read-back`() = runTest {
        opener.source = FakeVaultContentSource("content".toByteArray(), displayName = "a.txt")
        content.swallowWrites = true

        val result = repository().importFile(source(), authorize = { true })

        assertEquals(VaultImportFailure.VerificationFailed, (result as NivaraResult.Failure).error)
        assertTrue(content.documents.isEmpty())
        assertTrue(metadata.documents.keys.none { name -> name.startsWith("index.") })
    }

    @Test
    fun `a destination that stores something else is caught by the read-back`() = runTest {
        opener.source = FakeVaultContentSource("content".toByteArray(), displayName = "a.txt")
        content.corruptWrites = true

        val result = repository().importFile(source(), authorize = { true })

        assertEquals(VaultImportFailure.VerificationFailed, (result as NivaraResult.Failure).error)
        assertTrue(metadata.documents.keys.none { name -> name.startsWith("index.") })
    }

    @Test
    fun `a destination that cannot rename leaves no half-written object`() = runTest {
        opener.source = FakeVaultContentSource("content".toByteArray(), displayName = "a.txt")
        content.renameRefused = true

        val result = repository().importFile(source(), authorize = { true })

        assertEquals(VaultImportFailure.WriteFailed, (result as NivaraResult.Failure).error)
        assertTrue(content.documents.isEmpty())
    }

    // ------------------------------------------------------------------ the index

    @Test
    fun `an index that cannot be read stops the import rather than being replaced`() = runTest {
        val first = importOne()
        val damaged = metadata.documents.filterKeys { name -> name.startsWith("index.") }
            .mapValues { (_, bytes) -> bytes.copyOf().also { stored -> stored[stored.size - 1] = 0 } }
        damaged.forEach { (name, bytes) -> metadata.documents[name] = bytes }
        opener.source = FakeVaultContentSource("second".toByteArray(), displayName = "b.txt")

        val result = repository().importFile(source(), authorize = { true })

        assertTrue(result is NivaraResult.Failure)
        assertTrue((result as NivaraResult.Failure).error is VaultImportFailure.IndexUnreadable)
        val state = repository().read()
        assertTrue(state is VaultIndexState.Unreadable)
        assertEquals(
            VaultIndexUnreadable.MetadataDamaged,
            (state as VaultIndexState.Unreadable).reason,
        )
        // The item from before is still described by the object it was written to.
        assertEquals(1, content.documents.size)
        assertTrue(content.documents.containsKey(first.id.value + ".nvo"))
    }

    @Test
    fun `an index from a newer Nivara is never written over`() = runTest {
        importOne()
        metadata.documents.filterKeys { name -> name.startsWith("index.") }
            .forEach { (name, bytes) ->
                val copy = bytes.copyOf()
                copy[VaultIndexCodec.MAGIC.size] = 2
                metadata.documents[name] = copy
            }
        val before = metadata.snapshot()
        opener.source = FakeVaultContentSource("second".toByteArray(), displayName = "b.txt")

        val result = repository().importFile(source(), authorize = { true })

        assertTrue(result is NivaraResult.Failure)
        assertTrue((result as NivaraResult.Failure).error is VaultImportFailure.UnsupportedIndexVersion)
        assertEquals(before, metadata.snapshot())
    }

    @Test
    fun `an index write that is refused leaves the previous index exactly as it was`() = runTest {
        val first = importOne()
        val before = metadata.snapshot()
        metadata.writeFailure = VaultFailure.WriteFailed
        opener.source = FakeVaultContentSource("second".toByteArray(), displayName = "b.txt")

        val result = repository().importFile(source(), authorize = { true })

        assertTrue(result is NivaraResult.Failure)
        assertEquals(VaultImportFailure.WriteFailed, (result as NivaraResult.Failure).error)
        metadata.writeFailure = null
        val state = repository().read() as VaultIndexState.Ready
        assertEquals(listOf(first), state.items)
        // The object that could not be committed stays: it is complete, and deleting content Nivara
        // cannot match to a list is how a vault loses files.
        assertEquals(2, content.documents.size)
        assertEquals(1, state.unindexedObjects)
    }

    @Test
    fun `an index write that stores nothing is caught by the read-back`() = runTest {
        val first = importOne()
        metadata.swallowWrites = true
        opener.source = FakeVaultContentSource("second".toByteArray(), displayName = "b.txt")

        val result = repository().importFile(source(), authorize = { true })

        assertEquals(VaultImportFailure.VerificationFailed, (result as NivaraResult.Failure).error)
        metadata.swallowWrites = false
        val state = repository().read() as VaultIndexState.Ready
        assertEquals(listOf(first), state.items)
        assertEquals(1, state.unindexedObjects)
    }

    @Test
    fun `a vault that cannot be opened reports its own state instead of an empty list`() = runTest {
        vaultRepository.report(VaultState.Missing)

        assertEquals(VaultIndexState.VaultNotReady(VaultState.Missing), repository().read())
    }

    @Test
    fun `a vault whose key cannot be borrowed reports an unreadable list, not an empty one`() = runTest {
        keyAccess.failure = VaultFailure.KeyUnavailable

        val state = repository().read()

        assertEquals(
            VaultIndexState.Unreadable(VaultIndexUnreadable.KeyUnavailable),
            state,
        )
    }

    @Test
    fun `a listed item whose object is missing is reported, not silently dropped`() = runTest {
        val first = importOne()
        content.documents.remove(first.id.value + ".nvo")

        val state = repository().read() as VaultIndexState.Ready

        assertEquals(listOf(first), state.items)
        assertEquals(setOf(first.id), state.missingContent)
    }

    @Test
    fun `an object nobody listed is counted and never presented as a file`() = runTest {
        importOne()
        content.documents["0123456789abcdef0123456789abcdef.nvo"] = ByteArray(32) { 3 }
        content.documents["fedcba9876543210fedcba9876543210.nvo.pending"] = ByteArray(32) { 4 }

        val state = repository().read() as VaultIndexState.Ready

        assertEquals(1, state.items.size)
        assertEquals(1, state.unindexedObjects)
        assertEquals(1, state.unfinishedObjects)
    }

    @Test
    fun `an index that was never written is a vault that holds nothing yet`() = runTest {
        assertEquals(VaultIndexState.Missing, repository().read())
    }

    @Test
    fun `the index generation moves forward with every import`() = runTest {
        importOne()
        val firstGeneration = generation()
        importOne(name = "b.txt")
        val secondGeneration = generation()

        assertTrue(secondGeneration > firstGeneration)
    }

    // ------------------------------------------------------------------ duplicates and concurrency

    @Test
    fun `an identifier that is already in use fails the import instead of overwriting`() = runTest {
        val fixed = SecureRandomGenerator(FixedRandom(ByteArray(32) { 7 }))
        val repository = repository(idsFrom = fixed)
        opener.source = FakeVaultContentSource("first".toByteArray(), displayName = "a.txt")
        repository.importFile(source(), authorize = { true }).valueOrFail()

        opener.source = FakeVaultContentSource("second".toByteArray(), displayName = "b.txt")
        val result = repository.importFile(source(), authorize = { true })

        assertEquals(VaultImportFailure.DuplicateItemId, (result as NivaraResult.Failure).error)
        assertEquals(1, content.documents.size)
        assertEquals(1, (repository.read() as VaultIndexState.Ready).items.size)
    }

    @Test
    fun `two imports running at once both end up in the index`() = runTest {
        val repository = repository()
        opener.sources[source(1).value] =
            FakeVaultContentSource("first".toByteArray(), displayName = "a.txt")
        opener.sources[source(2).value] =
            FakeVaultContentSource("second".toByteArray(), displayName = "b.txt")
        val first = async { repository.importFile(source(1), authorize = { true }) }
        val second = async { repository.importFile(source(2), authorize = { true }) }

        val results = awaitAll(first, second)
        assertTrue(results.all { result -> result is NivaraResult.Success })
        val state = repository.read() as VaultIndexState.Ready
        assertEquals(2, state.items.size)
        assertEquals(setOf("a.txt", "b.txt"), state.items.map { item -> item.name }.toSet())
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun importOne(name: String = "a.txt"): VaultItem {
        opener.source = FakeVaultContentSource("content of $name".toByteArray(), displayName = name)
        return repository().importFile(source(), authorize = { true }).valueOrFail()
    }

    private fun source(index: Int = 1): VaultSourceReference =
        VaultSourceReference.create("content://com.nivara.test/document/$index")!!

    private fun generation(): Long {
        val name = metadata.documents.keys.first { entry -> entry.startsWith("index.") }
        val bytes = metadata.documents.getValue(name)
        return VaultIndexCodec.readHeader(bytes).let { header ->
            assertTrue(header is VaultIndexCodec.HeaderRead.Present)
            (header as VaultIndexCodec.HeaderRead.Present).generation
        }
    }

    /** A generator whose output a test chooses, so an identifier collision can be produced. */
    private class FixedRandom(private val fixed: ByteArray) : SecureRandom() {

        override fun nextBytes(bytes: ByteArray) {
            fixed.copyInto(bytes, 0, 0, minOf(bytes.size, fixed.size))
        }
    }
}
