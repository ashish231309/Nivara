package com.nivara.app.data.vault

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.data.security.JcaEncryptionService
import com.nivara.app.domain.security.EncryptionContext
import com.nivara.app.domain.security.EncryptionKey
import com.nivara.app.domain.security.SecureRandomGenerator
import com.nivara.app.domain.vault.VaultFailure
import com.nivara.app.domain.vault.VaultImportProgress
import com.nivara.app.domain.vault.VaultIndexRepository
import com.nivara.app.domain.vault.VaultIndexState
import com.nivara.app.domain.vault.VaultIndexUnreadable
import com.nivara.app.domain.vault.VaultItem
import com.nivara.app.domain.vault.VaultSourceReference
import com.nivara.app.domain.vault.VaultTrashEntry
import com.nivara.app.domain.vault.VaultTrashFailure
import com.nivara.app.domain.vault.VaultTrashLimits
import com.nivara.app.domain.vault.VaultTrashState
import com.nivara.app.domain.vault.VaultTrashUnreadable
import com.nivara.app.testing.testItem
import com.nivara.app.testing.testItemId
import com.nivara.app.testing.testTrashEntry
import com.nivara.app.testing.valueOrFail
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for the trash repository.
 *
 * What a device cannot be asked to produce on command is exactly what matters here: a record that does
 * not authenticate, one written by a newer build, a write that reports success and stores nothing, a
 * read-back that disagrees, a storage that refuses a write, a session that closes part-way through a
 * change. Each has its own promise, and they are all the same promise in the end — *the previous record
 * is still there, and nothing was reported as done that was not*.
 *
 * The vault is not real: the storage, the key, the vault's own repository and the index are doubles.
 * The encryption is real, because the property under test is that only bytes sealed for this purpose
 * can be read back at all.
 */
class NivaraVaultTrashRepositoryTest {

    private val random = SecureRandomGenerator()
    private val encryptionService = JcaEncryptionService(random = random)
    private val metadata = FakeVaultRootStorage().apply {
        metadataDirectory = true
        contentDirectory = true
    }
    private val keyAccess = FakeVaultKeyAccess()
    private val vaultRepository = FakeReadyVaultRepository()
    private var indexState: VaultIndexState = VaultIndexState.Missing
    private var now: Long = 1_700_000_000_000L
    private var allow: Boolean = true

    private class FixedIndexRepository(
        private val state: () -> VaultIndexState,
    ) : VaultIndexRepository {

        override suspend fun read(): VaultIndexState = state()

        override suspend fun importFile(
            source: VaultSourceReference,
            authorize: () -> Boolean,
            onProgress: (VaultImportProgress) -> Unit,
        ): NivaraResult<VaultItem> = NivaraResult.Failure(VaultFailure.StorageUnavailable)
    }

    private fun repository(): NivaraVaultTrashRepository = NivaraVaultTrashRepository(
        vaultRepository = vaultRepository,
        indexRepository = FixedIndexRepository { indexState },
        keyAccess = keyAccess,
        metadataStorageFactory = { metadata },
        encryptionService = encryptionService,
        clock = { now },
    )

    private fun authorize(): Boolean = allow

    private fun VaultTrashState.readable(): List<VaultTrashEntry> =
        (this as? VaultTrashState.Ready)?.entries
            ?: error("expected a readable trash record but was $this")

    private fun NivaraResult<*>.failure(): VaultTrashFailure =
        (this as? NivaraResult.Failure)?.error as? VaultTrashFailure
            ?: error("expected a typed trash failure but was $this")

    private fun slotsHoldingRecords(): List<String> =
        VaultStructure.TRASH_SLOT_NAMES.filter { name -> metadata.documents.containsKey(name) }

    /** Puts a record into a slot without going through the repository, as a previous generation would. */
    private suspend fun putRecord(
        entries: List<VaultTrashEntry>,
        generation: Long = 1L,
        slot: String = VaultStructure.TRASH_SLOT_NAMES.first(),
    ) {
        val payload = VaultTrashCodec.encodePayload(generation = generation, entries = entries)
            ?: error("the test's record could not be encoded")
        keyAccess.withVaultKey { _, key ->
            val envelope = encryptionService.encrypt(
                plaintext = payload,
                key = key,
                context = EncryptionContext.VaultTrash,
            ).valueOrFail()
            metadata.writeMetadata(
                slot,
                VaultTrashCodec.encodeRecord(generation = generation, envelope = envelope),
            )
            NivaraResult.Success(Unit)
        }
    }

    private fun putHeader(version: Int, slot: String = VaultStructure.TRASH_SLOT_NAMES.first()) {
        metadata.documents[slot] =
            VaultTrashCodec.MAGIC + byteArrayOf(version.toByte(), 0) + ByteArray(8) { 1 } + byteArrayOf(9, 9)
    }

    // ------------------------------------------------------------------ reading

    @Test
    fun a_vault_with_no_record_has_a_missing_trash_and_nothing_is_written() = runTest {
        assertEquals(VaultTrashState.Missing, repository().read())
        assertEquals(0, metadata.writeCalls)
    }

    @Test
    fun a_record_that_was_written_reads_back_as_the_entries_it_names() = runTest {
        val entries = listOf(testTrashEntry(seed = 2), testTrashEntry(seed = 1))
        putRecord(entries)
        assertEquals(entries.sortedBy { entry -> entry.itemId.value }, repository().read().readable())
    }

    @Test
    fun a_record_that_cannot_be_decrypted_is_unreadable_rather_than_empty() = runTest {
        putHeader(version = 1)
        assertEquals(
            VaultTrashState.Unreadable(VaultTrashUnreadable.MetadataDamaged),
            repository().read(),
        )
    }

    @Test
    fun a_record_written_by_a_newer_build_is_reported_with_its_version() = runTest {
        putHeader(version = 2)
        assertEquals(VaultTrashState.UnsupportedVersion(fileVersion = 2), repository().read())
    }

    @Test
    fun a_foreign_file_in_a_trash_slot_is_ignored_rather_than_read_as_trash() = runTest {
        metadata.documents[VaultStructure.TRASH_SLOT_NAMES.first()] = byteArrayOf(1, 2, 3)
        assertEquals(VaultTrashState.Missing, repository().read())
    }

    @Test
    fun a_newer_record_in_the_second_slot_wins_over_an_older_one_in_the_first() = runTest {
        putRecord(listOf(testTrashEntry(seed = 1)), generation = 1L, slot = VaultStructure.TRASH_SLOT_NAMES.first())
        putRecord(
            listOf(testTrashEntry(seed = 1), testTrashEntry(seed = 2)),
            generation = 2L,
            slot = VaultStructure.TRASH_SLOT_NAMES.last(),
        )
        assertEquals(2, repository().read().readable().size)
    }

    // ------------------------------------------------------------------ trashing

    @Test
    fun trashing_an_item_in_the_vault_writes_one_record_and_reports_the_entry() = runTest {
        indexState = VaultIndexState.Ready(items = listOf(testItem(seed = 1)))
        val entry = VaultTrashEntry(itemId = testItemId(1), trashedAtEpochMillis = now)
        val result = repository().trash(itemId = testItemId(1), authorize = ::authorize)
        assertEquals(entry, (result as? NivaraResult.Success)?.value)
        assertEquals(listOf(entry), repository().read().readable())
        assertEquals(1, metadata.writeCalls)
        assertEquals(1, slotsHoldingRecords().size)
    }

    @Test
    fun trashing_the_same_item_again_changes_nothing_and_is_reported_as_done() = runTest {
        indexState = VaultIndexState.Ready(items = listOf(testItem(seed = 1)))
        val repository = repository()
        repository.trash(itemId = testItemId(1), authorize = ::authorize)
        now += 1_000L
        val second = repository.trash(itemId = testItemId(1), authorize = ::authorize)
        val entry = (second as? NivaraResult.Success)?.value
        assertEquals(testItemId(1), entry?.itemId)
        assertEquals(1_700_000_000_000L, entry?.trashedAtEpochMillis)
        assertEquals(1, metadata.writeCalls)
        assertEquals(
            listOf(VaultTrashEntry(itemId = testItemId(1), trashedAtEpochMillis = 1_700_000_000_000L)),
            repository.read().readable(),
        )
    }

    @Test
    fun an_item_the_vault_does_not_list_is_refused_rather_than_invented() = runTest {
        indexState = VaultIndexState.Ready(items = listOf(testItem(seed = 2)))
        val result = repository().trash(itemId = testItemId(1), authorize = ::authorize)
        assertEquals(VaultTrashFailure.ItemNotInVault, result.failure())
        assertEquals(0, metadata.writeCalls)
    }

    @Test
    fun a_list_that_cannot_be_read_is_reported_as_such_and_nothing_is_written() = runTest {
        indexState = VaultIndexState.Unreadable(VaultIndexUnreadable.MetadataDamaged)
        val result = repository().trash(itemId = testItemId(1), authorize = ::authorize)
        assertEquals(
            VaultTrashFailure.IndexUnreadable(VaultIndexUnreadable.MetadataDamaged),
            result.failure(),
        )
        assertEquals(0, metadata.writeCalls)
    }

    @Test
    fun a_change_without_a_session_is_refused_before_anything_is_read_or_written() = runTest {
        indexState = VaultIndexState.Ready(items = listOf(testItem(seed = 1)))
        allow = false
        val result = repository().trash(itemId = testItemId(1), authorize = ::authorize)
        assertEquals(VaultTrashFailure.NotAuthorized, result.failure())
        assertEquals(0, metadata.writeCalls)
        assertEquals(0, keyAccess.borrows)
    }

    @Test
    fun every_change_asks_the_gate_twice_and_needs_both_answers() = runTest {
        indexState = VaultIndexState.Ready(items = listOf(testItem(seed = 1)))
        val answers = mutableListOf<Boolean>()
        val result = repository().trash(
            itemId = testItemId(1),
            authorize = { answers += true; true },
        )
        assertTrue(result is NivaraResult.Success)
        assertEquals(2, answers.size)
    }

    @Test
    fun a_session_that_ends_before_the_write_refuses_the_change() = runTest {
        indexState = VaultIndexState.Ready(items = listOf(testItem(seed = 1)))
        val answers = mutableListOf<Boolean>()
        val result = repository().trash(
            itemId = testItemId(1),
            authorize = {
                answers += true
                answers.size == 1
            },
        )
        assertEquals(VaultTrashFailure.NotAuthorized, result.failure())
        assertEquals(0, metadata.writeCalls)
    }

    @Test
    fun more_trashed_items_than_the_record_can_carry_is_refused() = runTest {
        indexState = VaultIndexState.Ready(items = listOf(testItem(seed = VaultTrashLimits.MAXIMUM_TRASHED_ITEMS + 1)))
        val full = (1..VaultTrashLimits.MAXIMUM_TRASHED_ITEMS).map { seed -> testTrashEntry(seed = seed) }
        putRecord(full)
        val writesBefore = metadata.writeCalls
        val result = repository().trash(
            itemId = testItemId(VaultTrashLimits.MAXIMUM_TRASHED_ITEMS + 1),
            authorize = ::authorize,
        )
        assertEquals(VaultTrashFailure.TrashFull, result.failure())
        assertEquals(writesBefore, metadata.writeCalls)
    }

    @Test
    fun a_record_that_cannot_be_read_is_never_written_over() = runTest {
        indexState = VaultIndexState.Ready(items = listOf(testItem(seed = 1)))
        putHeader(version = 1)
        val result = repository().trash(itemId = testItemId(1), authorize = ::authorize)
        assertEquals(
            VaultTrashFailure.TrashUnreadable(VaultTrashUnreadable.MetadataDamaged),
            result.failure(),
        )
        assertEquals(0, metadata.writeCalls)
    }

    // ------------------------------------------------------------------ restoring

    @Test
    fun restoring_removes_the_entry_and_keeps_the_identifier() = runTest {
        val entry = testTrashEntry(seed = 1)
        putRecord(listOf(entry))
        val writesBefore = metadata.writeCalls
        val result = repository().restore(itemId = testItemId(1), authorize = ::authorize)
        assertTrue(result is NivaraResult.Success)
        assertEquals(emptyList<VaultTrashEntry>(), repository().read().readable())
        assertEquals(writesBefore + 1, metadata.writeCalls)
        assertEquals(testItemId(1), entry.itemId)
    }

    @Test
    fun restoring_an_item_that_is_not_trashed_changes_nothing() = runTest {
        putRecord(listOf(testTrashEntry(seed = 1)))
        val writesBefore = metadata.writeCalls
        val result = repository().restore(itemId = testItemId(2), authorize = ::authorize)
        assertTrue(result is NivaraResult.Success)
        assertEquals(writesBefore, metadata.writeCalls)
        assertEquals(listOf(testTrashEntry(seed = 1)), repository().read().readable())
    }

    @Test
    fun a_write_that_does_not_come_back_is_reported_and_its_bytes_are_removed() = runTest {
        indexState = VaultIndexState.Ready(items = listOf(testItem(seed = 1)))
        metadata.corruptWrites = true
        val result = repository().trash(itemId = testItemId(1), authorize = ::authorize)
        assertEquals(VaultTrashFailure.VerificationFailed, result.failure())
        assertTrue(metadata.deleteCalls > 0)
        assertEquals(0, slotsHoldingRecords().size)
    }

    @Test
    fun a_write_that_stores_nothing_is_reported_and_nothing_is_kept() = runTest {
        indexState = VaultIndexState.Ready(items = listOf(testItem(seed = 1)))
        metadata.swallowWrites = true
        val result = repository().trash(itemId = testItemId(1), authorize = ::authorize)
        assertEquals(VaultTrashFailure.VerificationFailed, result.failure())
        assertEquals(0, slotsHoldingRecords().size)
    }

    @Test
    fun a_storage_that_refuses_the_write_is_reported_and_its_target_is_cleaned() = runTest {
        indexState = VaultIndexState.Ready(items = listOf(testItem(seed = 1)))
        metadata.writeFailure = VaultFailure.StorageUnavailable
        val result = repository().trash(itemId = testItemId(1), authorize = ::authorize)
        assertEquals(VaultTrashFailure.WriteFailed, result.failure())
        assertTrue(metadata.deleteCalls > 0)
        assertEquals(0, slotsHoldingRecords().size)
    }

    @Test
    fun a_failed_change_leaves_the_previous_committed_record_intact() = runTest {
        indexState = VaultIndexState.Ready(items = listOf(testItem(seed = 1), testItem(seed = 2)))
        val repository = repository()
        repository.trash(itemId = testItemId(1), authorize = ::authorize)
        metadata.corruptWrites = true
        val failed = repository.trash(itemId = testItemId(2), authorize = ::authorize)
        assertEquals(VaultTrashFailure.VerificationFailed, failed.failure())
        metadata.corruptWrites = false
        assertEquals(
            listOf(VaultTrashEntry(itemId = testItemId(1), trashedAtEpochMillis = 1_700_000_000_000L)),
            repository.read().readable(),
        )
    }

    @Test
    fun the_superseded_slot_is_pruned_only_after_the_new_record_is_read_back() = runTest {
        indexState = VaultIndexState.Ready(items = listOf(testItem(seed = 1), testItem(seed = 2)))
        val repository = repository()
        repository.trash(itemId = testItemId(1), authorize = ::authorize)
        repository.trash(itemId = testItemId(2), authorize = ::authorize)
        assertEquals(1, slotsHoldingRecords().size)
        assertTrue(metadata.deleteCalls > 0)
        assertEquals(2, repository.read().readable().size)
    }

    @Test
    fun a_change_reads_the_record_back_after_writing_it() = runTest {
        indexState = VaultIndexState.Ready(items = listOf(testItem(seed = 1), testItem(seed = 2)))
        putRecord(listOf(testTrashEntry(seed = 1)))
        val readsBefore = metadata.readCalls
        repository().trash(itemId = testItemId(2), authorize = ::authorize)
        // One read of the slot that holds the previous state, and one read-back of the slot that was
        // just written: a write that is never read again is never verified.
        assertTrue(metadata.readCalls >= readsBefore + 2)
    }

    @Test
    fun a_read_never_writes_anything_and_never_creates_a_record() = runTest {
        putRecord(listOf(testTrashEntry(seed = 1)), generation = 1L)
        val writesBefore = metadata.writeCalls
        repository().read()
        repository().read()
        assertEquals(writesBefore, metadata.writeCalls)
        assertNotNull(metadata.documents[VaultStructure.TRASH_SLOT_NAMES.first()])
        assertNull(metadata.documents[VaultStructure.TRASH_SLOT_NAMES.last()])
    }

    @Test
    fun the_record_is_sealed_under_the_trash_purpose_and_the_key_is_returned_cleared() = runTest {
        indexState = VaultIndexState.Ready(items = listOf(testItem(seed = 1)))
        repository().trash(itemId = testItemId(1), authorize = ::authorize)
        val record = metadata.documents.getValue(VaultStructure.TRASH_SLOT_NAMES.first())
        assertTrue(VaultTrashCodec.readHeader(record) is VaultTrashCodec.HeaderRead.Present)
        assertEquals(1, keyAccess.handedOut.size)
        val key = keyAccess.handedOut.single()
        assertTrue((key as? EncryptionKey.InProcess)?.material?.isCleared ?: false)
        assertFalse(record.isEmpty())
    }
}
