package com.nivara.app.data.vault

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.data.security.JcaEncryptionService
import com.nivara.app.domain.security.EncryptionContext
import com.nivara.app.domain.security.EncryptionKey
import com.nivara.app.domain.security.SecureRandomGenerator
import com.nivara.app.domain.vault.VaultAlbum
import com.nivara.app.domain.vault.VaultAlbumNames
import com.nivara.app.domain.vault.VaultFailure
import com.nivara.app.domain.vault.VaultOrganizationFailure
import com.nivara.app.domain.vault.VaultOrganizationLimits
import com.nivara.app.domain.vault.VaultOrganizationState
import com.nivara.app.domain.vault.VaultOrganizationUnreadable
import com.nivara.app.domain.vault.VaultState
import com.nivara.app.testing.testAlbum
import com.nivara.app.testing.testAlbumId
import com.nivara.app.testing.testItemId
import com.nivara.app.testing.valueOrFail
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for the album repository.
 *
 * What a device cannot be asked to produce on command is exactly what matters here: a record that does
 * not authenticate, one written by a newer build, a write that reports success and stores nothing, a
 * read-back that disagrees with what was written, a storage that refuses a write, a session that closes
 * part-way through a change. Each has its own promise, and they are all the same promise in the end —
 * *the previous record is still there, and nothing was reported as done that was not*.
 *
 * The vault is not real: the storage, the key and the vault's own repository are doubles. The
 * encryption is real, because the property under test is that only bytes sealed for this purpose can
 * be read back at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NivaraVaultOrganizationRepositoryTest {

    private val random = SecureRandomGenerator()
    private val encryptionService = JcaEncryptionService(random = random)
    private val metadata = FakeVaultRootStorage().apply {
        metadataDirectory = true
        contentDirectory = true
    }
    private val keyAccess = FakeVaultKeyAccess()
    private val vaultRepository = FakeReadyVaultRepository()
    private var now: Long = 1_700_000_000_000L
    private var allow: Boolean = true

    private fun repository(): NivaraVaultOrganizationRepository = NivaraVaultOrganizationRepository(
        vaultRepository = vaultRepository,
        keyAccess = keyAccess,
        metadataStorageFactory = { metadata },
        encryptionService = encryptionService,
        random = random,
        clock = { now },
    )

    private fun authorize(): Boolean = allow

    private fun NivaraResult<*>.failure(): VaultOrganizationFailure =
        (this as? NivaraResult.Failure)?.error as? VaultOrganizationFailure
            ?: error("expected a typed organisation failure but was $this")

    private fun slotsHoldingRecords(): List<String> =
        VaultStructure.ORGANIZATION_SLOT_NAMES.filter { name -> metadata.documents.containsKey(name) }

    private fun bytesOf(name: String): List<Int> =
        metadata.documents.getValue(name).map { byte -> byte.toInt() and 0xFF }

    /** Puts a record into a slot without going through the repository, as a previous generation would. */
    private suspend fun putRecord(
        albums: List<VaultAlbum>,
        generation: Long = 1L,
        slot: String = VaultStructure.ORGANIZATION_SLOT_NAMES.first(),
    ) {
        val payload = VaultOrganizationCodec.encodePayload(generation = generation, albums = albums)
            ?: error("the test's record could not be encoded")
        keyAccess.withVaultKey { _, key ->
            val envelope = encryptionService.encrypt(
                plaintext = payload,
                key = key,
                context = EncryptionContext.VaultOrganization,
            ).valueOrFail()
            metadata.writeMetadata(
                slot,
                VaultOrganizationCodec.encodeRecord(generation = generation, envelope = envelope),
            )
            NivaraResult.Success(Unit)
        }
    }

    // ------------------------------------------------------------------ reading

    @Test
    fun `a vault nobody has organised has no albums and nothing is written`() = runTest {
        val repository = repository()

        assertEquals(VaultOrganizationState.Missing, repository.read())
        assertEquals("reading never creates a record", 0, metadata.writeCalls)
        assertEquals(0, metadata.deleteCalls)
    }

    @Test
    fun `a vault that cannot be opened is not a vault without albums`() = runTest {
        vaultRepository.report(VaultState.Missing)

        val state = repository().read()

        state as VaultOrganizationState.VaultNotReady
        assertEquals(VaultState.Missing, state.vault)
    }

    @Test
    fun `a record that does not authenticate is unreadable, not absent`() = runTest {
        metadata.documents["albums.0.nva"] = VaultOrganizationCodec.encodeRecord(
            generation = 1L,
            envelope = ByteArray(64) { index -> index.toByte() },
        )

        val state = repository().read()

        state as VaultOrganizationState.Unreadable
        assertEquals(VaultOrganizationUnreadable.MetadataDamaged, state.reason)
    }

    @Test
    fun `a record sealed for another purpose cannot be opened`() = runTest {
        // The same payload, sealed for the index instead of for albums: the purpose is authenticated,
        // so the record is refused before anything in it is believed.
        val payload = VaultOrganizationCodec.encodePayload(generation = 1L, albums = listOf(testAlbum(seed = 1)))!!
        keyAccess.withVaultKey { _, key ->
            val envelope = encryptionService.encrypt(
                plaintext = payload,
                key = key,
                context = EncryptionContext.VaultIndex,
            ).valueOrFail()
            metadata.writeMetadata(
                "albums.0.nva",
                VaultOrganizationCodec.encodeRecord(generation = 1L, envelope = envelope),
            )
            NivaraResult.Success(Unit)
        }

        val state = repository().read()

        state as VaultOrganizationState.Unreadable
        assertEquals(VaultOrganizationUnreadable.MetadataDamaged, state.reason)
    }

    @Test
    fun `a record from a newer build is reported with its version and never written over`() = runTest {
        val newer = ByteArray(VaultOrganizationCodec.HEADER_LENGTH + 8)
        "NVAO".toByteArray().copyInto(newer, 0)
        newer[4] = 2
        metadata.documents["albums.0.nva"] = newer
        val before = bytesOf("albums.0.nva")

        val state = repository().read()

        state as VaultOrganizationState.UnsupportedVersion
        assertEquals(2, state.fileVersion)

        val refused = repository().createAlbum(name = "Trip", authorize = ::authorize)

        assertEquals(VaultOrganizationFailure.UnsupportedVersion(2), refused.failure())
        assertEquals("the record is left exactly as it was", before, bytesOf("albums.0.nva"))
        assertEquals(0, metadata.writeCalls)
    }

    @Test
    fun `a file in an album slot that is not an album record is ignored`() = runTest {
        metadata.documents["albums.0.nva"] = "not a nivara record".toByteArray()

        assertEquals(VaultOrganizationState.Missing, repository().read())
    }

    @Test
    fun `reading a damaged record changes nothing at all`() = runTest {
        metadata.documents["albums.0.nva"] = "NVAO\u0001\u0000".toByteArray()
        val before = bytesOf("albums.0.nva")
        val repository = repository()

        repeat(3) { repository.read() }

        assertEquals("no repair, no rebuild", before, bytesOf("albums.0.nva"))
        assertEquals(0, metadata.writeCalls)
        assertEquals(0, metadata.deleteCalls)
    }

    @Test
    fun `the newest generation wins`() = runTest {
        val older = testAlbum(seed = 1, name = "Older")
        val newer = testAlbum(seed = 2, name = "Newer")
        putRecord(albums = listOf(older), generation = 1L, slot = VaultStructure.ORGANIZATION_SLOT_NAMES[0])
        putRecord(albums = listOf(newer), generation = 3L, slot = VaultStructure.ORGANIZATION_SLOT_NAMES[1])

        val state = repository().read()

        assertEquals(VaultOrganizationState.Ready(listOf(newer)), state)
    }

    // ------------------------------------------------------------------ creating

    @Test
    fun `a created album is committed and read back`() = runTest {
        val repository = repository()

        val created = repository.createAlbum(name = "  Trip  2024 ", authorize = ::authorize).valueOrFail()

        assertEquals("the name is stored trimmed and collapsed", "Trip 2024", created.name)
        assertTrue("a new album holds nothing", created.itemIds.isEmpty())
        assertEquals(now, created.createdAtEpochMillis)
        assertEquals(VaultOrganizationState.Ready(listOf(created)), repository.read())
        assertEquals(listOf("albums.0.nva"), slotsHoldingRecords())
    }

    @Test
    fun `a second change goes into the other slot, and only one record is left`() = runTest {
        val repository = repository()
        repository.createAlbum(name = "First", authorize = ::authorize)
        repository.createAlbum(name = "Second", authorize = ::authorize)

        val state = repository().read()

        assertEquals(listOf("First", "Second"), state.albums.map { album -> album.name })
        assertEquals("one record survives", 1, slotsHoldingRecords().size)
        assertEquals("and the older slot is the one that went", listOf("albums.1.nva"), slotsHoldingRecords())
    }

    @Test
    fun `creating an album with a name that cannot be stored is refused and writes nothing`() = runTest {
        val repository = repository()

        listOf("", "   ", "x".repeat(VaultAlbumNames.MAXIMUM_LENGTH + 1), "Trip\u0000").forEach { bad ->
            val result = repository.createAlbum(name = bad, authorize = ::authorize)

            assertEquals(VaultOrganizationFailure.InvalidAlbumName, result.failure())
        }
        assertEquals(0, metadata.writeCalls)
        assertEquals(VaultOrganizationState.Missing, repository.read())
    }

    @Test
    fun `creating an album when the record is full is refused`() = runTest {
        val full = (1..VaultOrganizationLimits.MAXIMUM_ALBUMS).map { seed -> testAlbum(seed = seed, name = "Album $seed") }
        putRecord(albums = full)
        val writesAfterSeeding = metadata.writeCalls

        val result = repository().createAlbum(name = "One more", authorize = ::authorize)

        assertEquals(VaultOrganizationFailure.OrganizationFull, result.failure())
        assertEquals("a refused change writes nothing", writesAfterSeeding, metadata.writeCalls)
    }

    @Test
    fun `every album gets its own identifier`() = runTest {
        val repository = repository()
        val first = repository.createAlbum(name = "Trip", authorize = ::authorize).valueOrFail()
        val second = repository.createAlbum(name = "Trip", authorize = ::authorize).valueOrFail()

        assertFalse("a title is a label, not an identity", first.id == second.id)
    }

    // ------------------------------------------------------------------ renaming

    @Test
    fun `renaming an album keeps its identity and its membership`() = runTest {
        val repository = repository()
        val created = repository.createAlbum(name = "Trip", authorize = ::authorize).valueOrFail()
        repository.addItem(albumId = created.id, itemId = testItemId(7), authorize = ::authorize)

        val renamed = repository.renameAlbum(albumId = created.id, name = "  Winter   Trip ", authorize = ::authorize)
            .valueOrFail()

        assertEquals(created.id, renamed.id)
        assertEquals("Winter Trip", renamed.name)
        assertEquals(listOf(testItemId(7)), renamed.itemIds)
    }

    @Test
    fun `renaming to the title it already has is refused and writes nothing`() = runTest {
        val repository = repository()
        val created = repository.createAlbum(name = "Trip", authorize = ::authorize).valueOrFail()
        val before = bytesOf("albums.0.nva")
        val writesBefore = metadata.writeCalls

        val result = repository.renameAlbum(albumId = created.id, name = "  Trip ", authorize = ::authorize)

        assertEquals(VaultOrganizationFailure.AlbumNameUnchanged, result.failure())
        assertEquals("a write that can only fail is not made", writesBefore, metadata.writeCalls)
        assertEquals(before, bytesOf("albums.0.nva"))
    }

    @Test
    fun `renaming an album that is not in the record is refused`() = runTest {
        val repository = repository()
        repository.createAlbum(name = "Trip", authorize = ::authorize)

        val result = repository.renameAlbum(albumId = testAlbumId(99), name = "Other", authorize = ::authorize)

        assertEquals(VaultOrganizationFailure.AlbumNotFound, result.failure())
        assertEquals(1, metadata.writeCalls)
    }

    @Test
    fun `a title may change while the files in the album do not`() = runTest {
        val repository = repository()
        val created = repository.createAlbum(name = "Before", authorize = ::authorize).valueOrFail()
        repository.addItem(albumId = created.id, itemId = testItemId(7), authorize = ::authorize)
        repository.addItem(albumId = created.id, itemId = testItemId(8), authorize = ::authorize)

        val renamed = repository.renameAlbum(albumId = created.id, name = "After", authorize = ::authorize)
            .valueOrFail()

        assertEquals(listOf(testItemId(7), testItemId(8)), renamed.itemIds)
    }

    // ------------------------------------------------------------------ membership

    @Test
    fun `adding an item is idempotent, and the second add writes nothing`() = runTest {
        val repository = repository()
        val created = repository.createAlbum(name = "Trip", authorize = ::authorize).valueOrFail()

        val first = repository.addItem(albumId = created.id, itemId = testItemId(7), authorize = ::authorize)
            .valueOrFail()
        val afterFirst = slotsHoldingRecords().let { slots -> bytesOf(slots.single()) }
        val writesAfterFirst = metadata.writeCalls

        val second = repository.addItem(albumId = created.id, itemId = testItemId(7), authorize = ::authorize)
            .valueOrFail()

        assertEquals(listOf(testItemId(7)), first.itemIds)
        assertEquals("the state the caller asked for already holds", first, second)
        assertEquals("so no new generation is written", writesAfterFirst, metadata.writeCalls)
        assertEquals(afterFirst, slotsHoldingRecords().let { slots -> bytesOf(slots.single()) })
    }

    @Test
    fun `membership keeps the order items were added in`() = runTest {
        val repository = repository()
        val created = repository.createAlbum(name = "Trip", authorize = ::authorize).valueOrFail()

        repository.addItem(albumId = created.id, itemId = testItemId(9), authorize = ::authorize)
        repository.addItem(albumId = created.id, itemId = testItemId(7), authorize = ::authorize)
        val added = repository.addItem(albumId = created.id, itemId = testItemId(8), authorize = ::authorize)
            .valueOrFail()

        assertEquals(listOf(testItemId(9), testItemId(7), testItemId(8)), added.itemIds)
    }

    @Test
    fun `an item may be added to more than one album`() = runTest {
        val repository = repository()
        val first = repository.createAlbum(name = "First", authorize = ::authorize).valueOrFail()
        val second = repository.createAlbum(name = "Second", authorize = ::authorize).valueOrFail()

        repository.addItem(albumId = first.id, itemId = testItemId(7), authorize = ::authorize)
        repository.addItem(albumId = second.id, itemId = testItemId(7), authorize = ::authorize)

        val state = repository().read()
        assertEquals(listOf(listOf(testItemId(7)), listOf(testItemId(7))), state.albums.map { album -> album.itemIds })
    }

    @Test
    fun `adding an item that the vault's list no longer names is allowed and kept`() = runTest {
        val repository = repository()
        val created = repository.createAlbum(name = "Trip", authorize = ::authorize).valueOrFail()

        repository.addItem(albumId = created.id, itemId = testItemId(404), authorize = ::authorize)

        val state = repository().read()
        assertEquals(
            "a reference is kept and shown as stale, never repaired away",
            listOf(testItemId(404)),
            state.albums.single().itemIds,
        )
    }

    @Test
    fun `adding to an album that already holds as many items as it can is refused`() = runTest {
        val members = (1..VaultOrganizationLimits.MAXIMUM_MEMBERS_PER_ALBUM).map { seed -> testItemId(seed) }
        putRecord(albums = listOf(testAlbum(seed = 1, name = "Full", itemIds = members)))
        val writesAfterSeeding = metadata.writeCalls

        val result = repository().addItem(albumId = testAlbumId(1), itemId = testItemId(9_999), authorize = ::authorize)

        assertEquals(VaultOrganizationFailure.AlbumFull, result.failure())
        assertEquals("a refused change writes nothing", writesAfterSeeding, metadata.writeCalls)
    }

    @Test
    fun `removing an item takes it out of that album and nowhere else`() = runTest {
        val repository = repository()
        val first = repository.createAlbum(name = "First", authorize = ::authorize).valueOrFail()
        val second = repository.createAlbum(name = "Second", authorize = ::authorize).valueOrFail()
        repository.addItem(albumId = first.id, itemId = testItemId(7), authorize = ::authorize)
        repository.addItem(albumId = second.id, itemId = testItemId(7), authorize = ::authorize)

        val removed = repository.removeItem(albumId = first.id, itemId = testItemId(7), authorize = ::authorize)
            .valueOrFail()

        assertTrue("the album is emptied", removed.itemIds.isEmpty())
        val state = repository().read()
        assertEquals(
            "the other album still names it",
            listOf(testItemId(7)),
            state.albums.single { album -> album.id == second.id }.itemIds,
        )
    }

    @Test
    fun `removing something that is not in the album changes nothing`() = runTest {
        val repository = repository()
        val created = repository.createAlbum(name = "Trip", authorize = ::authorize).valueOrFail()
        val writesBefore = metadata.writeCalls

        val result = repository.removeItem(albumId = created.id, itemId = testItemId(7), authorize = ::authorize)

        assertTrue("what the caller asked for already holds", result is NivaraResult.Success)
        assertEquals(writesBefore, metadata.writeCalls)
    }

    @Test
    fun `removing a stale reference is an explicit act and the only way it goes`() = runTest {
        val repository = repository()
        val created = repository.createAlbum(name = "Trip", authorize = ::authorize).valueOrFail()
        repository.addItem(albumId = created.id, itemId = testItemId(404), authorize = ::authorize)

        val trimmed = repository.removeItem(albumId = created.id, itemId = testItemId(404), authorize = ::authorize)
            .valueOrFail()

        assertTrue(trimmed.itemIds.isEmpty())
    }

    // ------------------------------------------------------------------ deleting an album

    @Test
    fun `deleting an album deletes the album and nothing else`() = runTest {
        val repository = repository()
        val first = repository.createAlbum(name = "First", authorize = ::authorize).valueOrFail()
        val second = repository.createAlbum(name = "Second", authorize = ::authorize).valueOrFail()
        repository.addItem(albumId = first.id, itemId = testItemId(7), authorize = ::authorize)
        repository.addItem(albumId = second.id, itemId = testItemId(7), authorize = ::authorize)
        repository.addItem(albumId = second.id, itemId = testItemId(8), authorize = ::authorize)

        repository.deleteAlbum(albumId = first.id, authorize = ::authorize).valueOrFail()

        val state = repository().read()
        assertEquals(listOf("Second"), state.albums.map { album -> album.name })
        assertEquals(
            "the files the deleted album named are still named by the album that remains",
            listOf(testItemId(7), testItemId(8)),
            state.albums.single().itemIds,
        )
    }

    @Test
    fun `deleting an album leaves every other album's membership exactly as it was`() = runTest {
        val repository = repository()
        val kept = repository.createAlbum(name = "Kept", authorize = ::authorize).valueOrFail()
        val doomed = repository.createAlbum(name = "Doomed", authorize = ::authorize).valueOrFail()
        repository.addItem(albumId = kept.id, itemId = testItemId(7), authorize = ::authorize)
        repository.addItem(albumId = doomed.id, itemId = testItemId(8), authorize = ::authorize)

        repository.deleteAlbum(albumId = doomed.id, authorize = ::authorize)

        val state = repository().read()
        assertEquals(listOf(kept.id), state.albums.map { album -> album.id })
        assertEquals(listOf(testItemId(7)), state.albums.single().itemIds)
    }

    @Test
    fun `deleting the last album leaves a readable record with no albums`() = runTest {
        val repository = repository()
        val only = repository.createAlbum(name = "Only", authorize = ::authorize).valueOrFail()

        repository.deleteAlbum(albumId = only.id, authorize = ::authorize).valueOrFail()

        assertEquals(
            "an emptied album list is a record, not an absence of one",
            VaultOrganizationState.Ready(emptyList()),
            repository().read(),
        )
    }

    @Test
    fun `deleting an album that is not there is refused`() = runTest {
        val repository = repository()
        repository.createAlbum(name = "Trip", authorize = ::authorize)

        val result = repository.deleteAlbum(albumId = testAlbumId(99), authorize = ::authorize)

        assertEquals(VaultOrganizationFailure.AlbumNotFound, result.failure())
    }

    @Test
    fun `nothing outside the album slots is written or removed`() = runTest {
        // A record of the vault's own, standing in for the index and the vault record: album changes
        // must leave everything that is not theirs exactly as they found it.
        metadata.documents["index.0.nvi"] = "the vault's own list".toByteArray()
        val untouched = bytesOf("index.0.nvi")
        val repository = repository()
        val created = repository.createAlbum(name = "Trip", authorize = ::authorize).valueOrFail()
        repository.renameAlbum(albumId = created.id, name = "Winter", authorize = ::authorize)
        repository.addItem(albumId = created.id, itemId = testItemId(7), authorize = ::authorize)
        repository.deleteAlbum(albumId = created.id, authorize = ::authorize)

        assertEquals(untouched, bytesOf("index.0.nvi"))
        assertTrue(
            "nothing was created outside the album slots",
            metadata.documents.keys.all { name -> name == "index.0.nvi" || name in VaultStructure.ORGANIZATION_SLOT_NAMES },
        )
    }

    // ------------------------------------------------------------------ authorization

    @Test
    fun `a change without a session is refused before anything is even read`() = runTest {
        allow = false
        val repository = repository()

        val result = repository.createAlbum(name = "Trip", authorize = ::authorize)

        assertEquals(VaultOrganizationFailure.NotAuthorized, result.failure())
        assertEquals("the vault is not opened for a refused change", 0, keyAccess.borrows)
        assertEquals(0, metadata.writeCalls)
        assertEquals(0, metadata.readCalls)
    }

    @Test
    fun `a session that closes while a change is being prepared stops it`() = runTest {
        val repository = repository()
        repository.createAlbum(name = "First", authorize = ::authorize)
        val before = slotsHoldingRecords().let { slots -> bytesOf(slots.single()) }
        val writesBefore = metadata.writeCalls

        // Open when the change is accepted, closed by the time the record would be replaced.
        var asked = 0
        val closing = {
            asked += 1
            asked == 1
        }
        val result = repository.createAlbum(name = "Second", authorize = closing)

        assertEquals(VaultOrganizationFailure.NotAuthorized, result.failure())
        assertEquals("nothing was written after the gate closed", writesBefore, metadata.writeCalls)
        assertEquals(before, slotsHoldingRecords().let { slots -> bytesOf(slots.single()) })
    }

    // ------------------------------------------------------------------ what a failed write leaves behind

    @Test
    fun `a write that reports success and stores nothing is a failure, and no record is left`() = runTest {
        metadata.swallowWrites = true

        val result = repository().createAlbum(name = "Trip", authorize = ::authorize)

        assertEquals(VaultOrganizationFailure.VerificationFailed, result.failure())
        assertTrue("the unverified record is not left for the next reader", slotsHoldingRecords().isEmpty())
        assertEquals(VaultOrganizationState.Missing, repository().read())
    }

    @Test
    fun `a write that stores something else is a failure, and no record is left`() = runTest {
        metadata.corruptWrites = true

        val result = repository().createAlbum(name = "Trip", authorize = ::authorize)

        assertEquals(VaultOrganizationFailure.VerificationFailed, result.failure())
        assertTrue(slotsHoldingRecords().isEmpty())
    }

    @Test
    fun `a refused write is reported, and the record that was there is untouched`() = runTest {
        val repository = repository()
        repository.createAlbum(name = "First", authorize = ::authorize)
        val before = slotsHoldingRecords().let { slots -> bytesOf(slots.single()) }
        metadata.writeFailure = VaultFailure.StorageUnavailable

        val result = repository.createAlbum(name = "Second", authorize = ::authorize)

        assertEquals(VaultOrganizationFailure.WriteFailed, result.failure())
        assertEquals("the previous generation is still the record", before, slotsHoldingRecords().let { slots -> bytesOf(slots.single()) })
        assertEquals(listOf("First"), repository().read().albums.map { album -> album.name })
    }

    @Test
    fun `a record that cannot be read back is removed rather than trusted`() = runTest {
        val repository = repository()
        repository.createAlbum(name = "First", authorize = ::authorize)
        val before = slotsHoldingRecords().let { slots -> bytesOf(slots.single()) }
        // The slot the next generation will be written into cannot be read afterwards.
        metadata.unreadableDocuments += "albums.1.nva"

        val result = repository.createAlbum(name = "Second", authorize = ::authorize)

        assertEquals(VaultOrganizationFailure.VerificationFailed, result.failure())
        assertEquals(listOf("albums.0.nva"), slotsHoldingRecords())
        assertEquals(before, bytesOf("albums.0.nva"))
        assertEquals(listOf("First"), repository().read().albums.map { album -> album.name })
    }

    @Test
    fun `a change that fails leaves the record it did not write untouched, byte for byte`() = runTest {
        val repository = repository()
        repository.createAlbum(name = "First", authorize = ::authorize)
        val before = slotsHoldingRecords().let { slots -> bytesOf(slots.single()) }
        metadata.writeFailure = VaultFailure.WriteFailed
        metadata.writeFailureAfterBytes = 4

        repository.createAlbum(name = "Second", authorize = ::authorize)
        repository.createAlbum(name = "Third", authorize = ::authorize)
        repository.deleteAlbum(albumId = testAlbumId(1), authorize = ::authorize)

        assertEquals(before, bytesOf("albums.0.nva"))
        assertEquals(listOf("albums.0.nva"), slotsHoldingRecords())
    }

    // ------------------------------------------------------------------ the record that cannot be changed

    @Test
    fun `a record that cannot be read refuses every change and is left alone`() = runTest {
        metadata.documents["albums.0.nva"] = VaultOrganizationCodec.encodeRecord(
            generation = 1L,
            envelope = ByteArray(64) { index -> (index * 7).toByte() },
        )
        val before = bytesOf("albums.0.nva")
        val repository = repository()

        val results = listOf(
            repository.createAlbum(name = "Trip", authorize = ::authorize),
            repository.renameAlbum(albumId = testAlbumId(1), name = "Other", authorize = ::authorize),
            repository.deleteAlbum(albumId = testAlbumId(1), authorize = ::authorize),
            repository.addItem(albumId = testAlbumId(1), itemId = testItemId(1), authorize = ::authorize),
            repository.removeItem(albumId = testAlbumId(1), itemId = testItemId(1), authorize = ::authorize),
        )

        results.forEach { result ->
            assertEquals(
                VaultOrganizationFailure.OrganizationUnreadable(VaultOrganizationUnreadable.MetadataDamaged),
                result.failure(),
            )
        }
        assertEquals("writing over a record Nivara cannot read is how albums are lost", before, bytesOf("albums.0.nva"))
        assertEquals(0, metadata.writeCalls)
        assertEquals(0, metadata.deleteCalls)
    }

    @Test
    fun `a change is refused while the vault itself cannot be opened`() = runTest {
        vaultRepository.report(VaultState.Unavailable)

        val result = repository().createAlbum(name = "Trip", authorize = ::authorize)

        assertTrue(result.failure() is VaultOrganizationFailure.VaultNotReady)
        assertEquals(0, metadata.writeCalls)
    }

    @Test
    fun `opening the vault is what makes albums possible, so the key is borrowed for every change`() = runTest {
        val repository = repository()

        repository.createAlbum(name = "Trip", authorize = ::authorize)

        assertTrue("the vault's key opened the record", keyAccess.borrows > 0)
        assertTrue(
            "and it was cleared again",
            keyAccess.handedOut.all { key -> key !is EncryptionKey.InProcess || key.material.isCleared },
        )
    }

    // ------------------------------------------------------------------ while a change is in flight

    @Test
    fun `a read while a change is being written still sees the record that is committed`() =
        runTest(UnconfinedTestDispatcher()) {
            val repository = repository()
            val first = repository.createAlbum(name = "First", authorize = ::authorize).valueOrFail()
            val gate = CompletableDeferred<Unit>()
            metadata.writeGate = gate

            val writing = async { repository.createAlbum(name = "Second", authorize = ::authorize) }
            // The commit is held inside the write, so nothing has been replaced yet.
            val duringWrite = repository.read()
            gate.complete(Unit)
            val committed = writing.await().valueOrFail()

            assertEquals(VaultOrganizationState.Ready(listOf(first)), duringWrite)
            assertEquals("Second", committed.name)
            metadata.writeGate = null
            assertEquals(
                listOf("First", "Second"),
                repository().read().albums.map { album -> album.name },
            )
        }

    @Test
    fun `two changes in a row leave the record holding both of them`() = runTest {
        val repository = repository()
        val first = repository.createAlbum(name = "First", authorize = ::authorize).valueOrFail()
        repository.addItem(albumId = first.id, itemId = testItemId(7), authorize = ::authorize)
        repository.renameAlbum(albumId = first.id, name = "First, renamed", authorize = ::authorize)

        val state = repository().read()
        assertEquals(listOf("First, renamed"), state.albums.map { album -> album.name })
        assertEquals(listOf(testItemId(7)), state.albums.single().itemIds)
        assertEquals(1, slotsHoldingRecords().size)
    }

    // ------------------------------------------------------------------ helpers

    @Test
    fun `an album record never grows into a second index`() = runTest {
        val repository = repository()
        val created = repository.createAlbum(name = "Trip", authorize = ::authorize).valueOrFail()
        repository.addItem(albumId = created.id, itemId = testItemId(7), authorize = ::authorize)

        val record = metadata.documents.values.single { bytes -> bytes.size >= 4 && String(bytes.copyOf(4)) == "NVAO" }

        assertTrue(
            "the record holds identifiers and titles, and nothing that could open a file",
            record.size < 4_096,
        )
        assertNull(
            "and nothing in it is plaintext",
            Regex("Trip").find(String(record, Charsets.ISO_8859_1)),
        )
    }

    @Test
    fun `a cancelled change is not retried behind the caller's back`() =
        runTest(UnconfinedTestDispatcher()) {
            val repository = repository()
            val gate = CompletableDeferred<Unit>()
            metadata.writeGate = gate
            val writesBefore = metadata.writeCalls

            val job = launch { repository.createAlbum(name = "Trip", authorize = ::authorize) }
            // The change has reached the write and is held there.
            assertEquals(writesBefore + 1, metadata.writeCalls)
            job.cancel()
            metadata.writeGate = null
            gate.complete(Unit)
            job.join()

            assertEquals("the change was attempted once", writesBefore + 1, metadata.writeCalls)
            assertEquals(
                "and nothing was committed",
                VaultOrganizationState.Missing,
                repository().read(),
            )
        }

    @Test
    fun `the albums a change writes are the albums a later change reads`() = runTest {
        val repository = repository()
        val created = repository.createAlbum(name = "Trip", authorize = ::authorize).valueOrFail()
        repository.addItem(albumId = created.id, itemId = testItemId(7), authorize = ::authorize)

        val readBack = repository().read()
        val renamed = repository().renameAlbum(albumId = readBack.albums.single().id, name = "Winter", authorize = ::authorize)
            .valueOrFail()

        assertEquals(created.id, renamed.id)
        assertEquals(listOf(testItemId(7)), renamed.itemIds)
    }
}
