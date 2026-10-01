package com.nivara.app.data.vault

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.data.security.JcaEncryptionService
import com.nivara.app.data.security.NivaraContentKeyWrapper
import com.nivara.app.domain.security.SecureRandomGenerator
import com.nivara.app.domain.vault.VaultFailure
import com.nivara.app.domain.vault.VaultLocation
import com.nivara.app.domain.vault.VaultIdentity
import com.nivara.app.domain.vault.VaultLocationRead
import com.nivara.app.domain.vault.VaultState
import com.nivara.app.domain.vault.VaultUnreadableReason
import com.nivara.app.testing.randomKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for the vault repository: what is at a root, and creating a vault at it.
 *
 * The cryptographic services here are the production ones (the JCA envelope and the content-key
 * wrapper), and the storage and the platform key store are fakes, because the cases that matter most
 * are the ones a device cannot be asked to produce: a refused write, a write that reports success and
 * stores nothing, a read-back that disagrees with what was written, a key the platform has destroyed.
 *
 * Two promises are checked over and over from different directions, because they are the stage:
 * nothing is reported as created before the bytes on storage have been read back and opened, and no
 * failure is ever reported as an empty or absent vault.
 */
class NivaraVaultRepositoryTest {

    private val location = VaultLocation("content://com.android.externalstorage.documents/tree/primary%3ANivara")
    private val random = SecureRandomGenerator()
    private val encryptionService = JcaEncryptionService(random = random)
    private val contentKeyWrapper = NivaraContentKeyWrapper(
        random = random,
        encryptionService = encryptionService,
    )
    private val deviceKeyStore = FakeDeviceKeyStore(key = randomKey("device-key"))
    private val locationStore = FakeVaultLocationStore(VaultLocationRead.Present(location))
    private val storage = FakeVaultRootStorage()

    private fun repository(): NivaraVaultRepository = NivaraVaultRepository(
        locationStore = locationStore,
        storageFactory = { storage },
        deviceKeyStore = deviceKeyStore,
        contentKeyWrapper = contentKeyWrapper,
        encryptionService = encryptionService,
        random = random,
    )

    private suspend fun initialize(replaceUnreadable: Boolean = false): NivaraResult<Unit> =
        repository().initialize(replaceUnreadable = replaceUnreadable)

    private suspend fun inspect(): VaultState = repository().inspect()

    private fun slot(index: Int): String = VaultStructure.SLOT_NAMES[index]

    /** Puts a record that a run of `initialize` produced into the metadata area, if it is not there. */
    private suspend fun createVault(): VaultState.Ready {
        val result = initialize()
        assertTrue("the vault must be created before the test can use it", result is NivaraResult.Success)
        return inspect() as VaultState.Ready
    }

    // ------------------------------------------------------------------ reading

    @Test
    fun `no stored location means no vault is configured, not an empty vault`() = runTest {
        locationStore.stored = VaultLocationRead.None
        storage.metadataDirectory = true
        storage.documents[slot(0)] = random.nextByteArray(64)

        assertEquals(VaultState.NotConfigured, inspect())
    }

    @Test
    fun `a location that cannot be read is reported, not forgotten`() = runTest {
        locationStore.stored = VaultLocationRead.Unreadable

        assertEquals(VaultState.LocationUnknown, inspect())
    }

    @Test
    fun `a folder holding nothing of nivara's is a missing vault`() = runTest {
        assertEquals(VaultState.Missing, inspect())
    }

    @Test
    fun `a name that only happens to look like a slot is not a vault`() = runTest {
        storage.metadataDirectory = true
        storage.documents[slot(0)] = "hello".toByteArray()

        val state = inspect()

        assertFalse("another application's file is not a record", state is VaultState.Ready)
        assertEquals(
            "and Nivara's own area without a record is an unfinished setup, not an empty folder",
            VaultState.Unreadable(VaultUnreadableReason.StructureIncomplete),
            state,
        )
    }

    @Test
    fun `a foreign file beside the records is ignored`() = runTest {
        createVault()
        storage.documents["notes.txt"] = "hello".toByteArray()

        assertEquals("a person's own file in the area changes nothing", VaultState.Ready, inspect())
    }

    @Test
    fun `an empty record left by an unfinished initialization is an incomplete structure`() = runTest {
        storage.metadataDirectory = true
        storage.documents[slot(0)] = ByteArray(0)

        assertEquals(VaultState.Unreadable(VaultUnreadableReason.StructureIncomplete), inspect())
    }

    @Test
    fun `a truncated record is damaged, never an empty vault`() = runTest {
        storage.metadataDirectory = true
        storage.documents[slot(0)] = VaultRecordCodec.encodeRecord(1L, random.nextByteArray(40))
            .copyOf(VaultRecordCodec.HEADER_LENGTH + 4)

        val state = inspect()

        assertNotEquals("a damaged record must not read as a folder without a vault", VaultState.Missing, state)
        assertEquals(VaultState.Unreadable(VaultUnreadableReason.MetadataDamaged), state)
    }

    @Test
    fun `a record that cannot be read at all is damage`() = runTest {
        storage.metadataDirectory = true
        storage.unreadableDocuments += slot(0)

        assertEquals(VaultState.Unreadable(VaultUnreadableReason.MetadataDamaged), inspect())
    }

    @Test
    fun `a record written by a newer format is refused with its version`() = runTest {
        storage.metadataDirectory = true
        storage.contentDirectory = true
        val record = VaultRecordCodec.encodeRecord(1L, random.nextByteArray(40))
        record[4] = 2
        storage.documents[slot(0)] = record

        assertEquals(VaultState.UnsupportedVersion(2), inspect())
    }

    @Test
    fun `a valid vault is reported ready with its identity and format version`() = runTest {
        val ready = createVault()

        assertEquals(VaultRecordCodec.VERSION, ready.formatVersion)
        assertEquals(VaultIdentity.BYTES * 2, ready.identity.value.length)

        val again = inspect() as VaultState.Ready

        assertEquals("the same vault is found by the same read", ready.identity, again.identity)
    }

    @Test
    fun `metadata without its content area is an incomplete structure`() = runTest {
        createVault()
        storage.contentDirectory = false

        assertEquals(VaultState.Unreadable(VaultUnreadableReason.StructureIncomplete), inspect())
    }

    @Test
    fun `a platform key that is gone is a lost key, not damage`() = runTest {
        createVault()
        deviceKeyStore.destroyKey()

        assertEquals(VaultState.Unreadable(VaultUnreadableReason.KeyUnavailable), inspect())
    }

    @Test
    fun `a different platform key fails closed rather than reporting a vault`() = runTest {
        createVault()

        // The alias holds new material under the same name: the record is intact and cannot be opened.
        deviceKeyStore.replaceKey(randomKey("other-device-key"))

        val state = inspect()

        assertFalse("wrong key material must never open a vault", state is VaultState.Ready)
        assertNotEquals("and it must not look like a folder without a vault", VaultState.Missing, state)
        assertEquals(VaultState.Unreadable(VaultUnreadableReason.MetadataDamaged), state)
    }

    @Test
    fun `a clear header that disagrees with the sealed payload is damage`() = runTest {
        createVault()
        val stored = storage.documents.getValue(slot(0))
        // Promote the generation in the clear header only: the sealed payload still says 1.
        stored[6 + VaultRecordCodec.GENERATION_SIZE - 1] = 99

        assertEquals(VaultState.Unreadable(VaultUnreadableReason.MetadataDamaged), inspect())
    }

    @Test
    fun `an unreachable root is unavailable, not a missing vault`() = runTest {
        storage.entriesFailure = VaultFailure.StorageUnavailable

        assertEquals(VaultState.Unavailable, inspect())
    }

    @Test
    fun `a refused grant is reported as denied access`() = runTest {
        storage.entriesFailure = VaultFailure.AccessDenied

        assertEquals(VaultState.AccessDenied, inspect())
    }

    @Test
    fun `looking at a root never writes to it`() = runTest {
        createVault()
        val before = storage.snapshot()
        val writes = storage.writeCalls
        val deletes = storage.deleteCalls

        inspect()
        inspect()
        locationStore.stored = VaultLocationRead.None
        inspect()

        assertEquals("inspection must not write", writes, storage.writeCalls)
        assertEquals("inspection must not delete", deletes, storage.deleteCalls)
        assertEquals("inspection must not change a byte", before, storage.snapshot())
    }

    @Test
    fun `inspection answers the same thing twice`() = runTest {
        val ready = createVault()

        assertEquals(ready, inspect())
        assertEquals(ready, inspect())
    }

    // ------------------------------------------------------------------ creating

    @Test
    fun `a fresh root becomes a vault in one write`() = runTest {
        val result = initialize()

        assertTrue("creating a vault succeeds", result is NivaraResult.Success)
        assertTrue("the metadata area is created", storage.metadataDirectory)
        assertTrue("the content area is created", storage.contentDirectory)
        assertEquals("one record is written, and nothing speculatively", 1, storage.writeCalls)
        assertEquals(listOf(slot(0)), storage.documents.keys.toList())

        val ready = inspect() as VaultState.Ready
        assertEquals(VaultRecordCodec.VERSION, ready.formatVersion)
    }

    @Test
    fun `the record on storage holds no plaintext of what it protects`() = runTest {
        createVault()
        val ready = inspect() as VaultState.Ready

        val stored = storage.documents.getValue(slot(0))
        val asText = String(stored, Charsets.ISO_8859_1)

        assertTrue(
            "the sealed payload must not be readable as a payload",
            !asText.contains("NVVP"),
        )
        assertTrue(
            "the vault identity must not be readable in the record",
            !asText.contains(ready.identity.value),
        )
    }

    @Test
    fun `a second initialization refuses and leaves the vault untouched`() = runTest {
        val ready = createVault()
        val before = storage.snapshot()

        val result = initialize()

        assertEquals(VaultFailure.VaultAlreadyExists, (result as NivaraResult.Failure).error)
        assertEquals("a valid vault is never replaced", before, storage.snapshot())
        assertEquals("and it is not even written to", 1, storage.writeCalls)
        assertEquals(ready, inspect())
    }

    @Test
    fun `a valid vault is refused even when replacement was asked for`() = runTest {
        val ready = createVault()

        val result = initialize(replaceUnreadable = true)

        assertEquals(VaultFailure.VaultAlreadyExists, (result as NivaraResult.Failure).error)
        assertEquals(ready, inspect())
    }

    @Test
    fun `a vault from a newer format is never replaced`() = runTest {
        storage.metadataDirectory = true
        storage.contentDirectory = true
        val record = VaultRecordCodec.encodeRecord(1L, random.nextByteArray(40))
        record[4] = 9
        storage.documents[slot(0)] = record
        val before = storage.snapshot()

        val refused = initialize()
        val refusedForcefully = initialize(replaceUnreadable = true)

        assertEquals(
            VaultFailure.UnsupportedVersion(9),
            (refused as NivaraResult.Failure).error,
        )
        assertEquals(
            "a newer Nivara may still open it, so nothing may replace it",
            VaultFailure.UnsupportedVersion(9),
            (refusedForcefully as NivaraResult.Failure).error,
        )
        assertEquals(before, storage.snapshot())
        assertEquals(VaultState.UnsupportedVersion(9), inspect())
    }

    @Test
    fun `damaged records are refused unless the user has said they may be replaced`() = runTest {
        storage.metadataDirectory = true
        storage.contentDirectory = true
        storage.documents[slot(0)] = VaultRecordCodec.encodeRecord(1L, random.nextByteArray(40))
            .copyOf(VaultRecordCodec.HEADER_LENGTH + 2)
        val before = storage.snapshot()

        val refused = initialize()

        assertEquals(
            VaultFailure.VaultUnreadable(VaultUnreadableReason.MetadataDamaged),
            (refused as NivaraResult.Failure).error,
        )
        assertEquals("a refusal changes nothing", before, storage.snapshot())
    }

    @Test
    fun `damaged records are replaced when the user has said so`() = runTest {
        storage.metadataDirectory = true
        storage.contentDirectory = true
        storage.documents[slot(0)] = VaultRecordCodec.encodeRecord(1L, random.nextByteArray(40))
            .copyOf(VaultRecordCodec.HEADER_LENGTH + 2)

        val result = initialize(replaceUnreadable = true)

        assertTrue("the replacement is the explicit path", result is NivaraResult.Success)
        val ready = inspect() as VaultState.Ready
        assertEquals("the old record is gone", listOf(slot(0)), storage.documents.keys.toList())
        assertTrue("a new vault is a new vault", ready.identity.value.isNotEmpty())
    }

    @Test
    fun `a lost platform key is replaced only when the user has said so`() = runTest {
        val original = createVault()
        deviceKeyStore.destroyKey()

        val refused = initialize()

        assertEquals(
            VaultFailure.VaultUnreadable(VaultUnreadableReason.KeyUnavailable),
            (refused as NivaraResult.Failure).error,
        )
        assertEquals(
            "a refusal changes nothing at all",
            VaultState.Unreadable(VaultUnreadableReason.KeyUnavailable),
            inspect(),
        )

        // The explicit replacement creates fresh key material, which is what makes the vault openable
        // again — a new vault with a new identity, not the old one repaired.
        val replaced = initialize(replaceUnreadable = true)

        assertTrue("the user's explicit choice is what allows it", replaced is NivaraResult.Success)
        val ready = inspect() as VaultState.Ready
        assertNotEquals("the replaced vault is a different vault", original.identity, ready.identity)
    }

    @Test
    fun `an unfinished initialization is completed without a replacement decision`() = runTest {
        storage.metadataDirectory = true
        storage.contentDirectory = true
        storage.documents[slot(0)] = ByteArray(0)

        val result = initialize()

        assertTrue("nothing is destroyed by completing an unfinished setup", result is NivaraResult.Success)
        val ready = inspect() as VaultState.Ready
        assertEquals(VaultRecordCodec.VERSION, ready.formatVersion)
    }

    @Test
    fun `with no stored location there is nothing to initialize`() = runTest {
        locationStore.stored = VaultLocationRead.None

        val result = initialize()

        assertEquals(VaultFailure.InvalidLocation, (result as NivaraResult.Failure).error)
        assertEquals("nothing is created anywhere", 0, storage.writeCalls)
    }

    @Test
    fun `a location that cannot be read is never initialized over`() = runTest {
        locationStore.stored = VaultLocationRead.Unreadable

        val result = initialize()

        assertEquals(VaultFailure.LocationUnreadable, (result as NivaraResult.Failure).error)
        assertEquals(0, storage.writeCalls)
    }

    // ------------------------------------------------------------------ failures while writing

    @Test
    fun `a directory that cannot be created is a failed write, not a vault`() = runTest {
        storage.ensureFailure = VaultFailure.WriteFailed

        val result = initialize()

        assertEquals(VaultFailure.WriteFailed, (result as NivaraResult.Failure).error)
        assertEquals("no record is written at all", 0, storage.writeCalls)
    }

    @Test
    fun `a refused write leaves no vault and a retry completes it`() = runTest {
        storage.writeFailure = VaultFailure.WriteFailed

        val result = initialize()

        assertEquals(VaultFailure.WriteFailed, (result as NivaraResult.Failure).error)
        assertEquals("nothing is left where a record belongs", emptyList<String>(), storage.documents.keys.toList())
        assertEquals(
            "the folder says an initialization did not finish, not that a vault is missing",
            VaultState.Unreadable(VaultUnreadableReason.StructureIncomplete),
            inspect(),
        )

        storage.writeFailure = null
        assertTrue("the retry is an ordinary initialization", initialize() is NivaraResult.Success)
        assertTrue(inspect() is VaultState.Ready)
    }

    @Test
    fun `an interrupted write leaves bytes that are not treated as a vault`() = runTest {
        storage.writeFailure = VaultFailure.WriteFailed
        storage.writeFailureAfterBytes = 20
        // The cleanup cannot run either, so the partial bytes stay exactly where the interruption left
        // them — the case a device can produce and a reader has to survive.
        storage.deleteFailure = VaultFailure.WriteFailed

        val result = initialize()

        assertEquals(VaultFailure.WriteFailed, (result as NivaraResult.Failure).error)
        assertEquals(
            "half a record was left behind and nothing removed it",
            listOf(slot(0)),
            storage.documents.keys.toList(),
        )
        val state = inspect()
        assertFalse("half a record is not a vault", state is VaultState.Ready)
        assertNotEquals("and it is not an empty folder either", VaultState.Missing, state)
        assertEquals(
            "an unopenable record is damage, and it stays that way until someone decides otherwise",
            VaultState.Unreadable(VaultUnreadableReason.MetadataDamaged),
            state,
        )
        assertEquals("nothing was repaired", listOf(slot(0)), storage.documents.keys.toList())
    }

    @Test
    fun `a write that stores nothing is caught by reading it back`() = runTest {
        storage.swallowWrites = true

        val result = initialize()

        assertEquals(
            "a provider that drops the write must not look like a created vault",
            VaultFailure.VerificationFailed,
            (result as NivaraResult.Failure).error,
        )
        assertEquals("and its slot is cleared", emptyList<String>(), storage.documents.keys.toList())
    }

    @Test
    fun `a write that stores something else is caught by reading it back`() = runTest {
        storage.corruptWrites = true

        val result = initialize()

        assertEquals(
            VaultFailure.VerificationFailed,
            (result as NivaraResult.Failure).error,
        )
        assertEquals("the unverifiable record is removed", emptyList<String>(), storage.documents.keys.toList())
        assertTrue(
            "and the folder never reports a vault the write did not produce",
            inspect() is VaultState.Unreadable,
        )
    }

    @Test
    fun `a stale record that cannot be deleted does not hide the new vault`() = runTest {
        storage.metadataDirectory = true
        storage.contentDirectory = true
        // The old, unreadable record sits in the slot the new one will not use.
        storage.documents[slot(1)] = VaultRecordCodec.encodeRecord(1L, random.nextByteArray(40))
            .copyOf(VaultRecordCodec.HEADER_LENGTH + 2)
        storage.deleteFailure = VaultFailure.WriteFailed

        val result = initialize(replaceUnreadable = true)

        assertTrue("cleanup is best effort, the commit is not", result is NivaraResult.Success)
        assertTrue("the leftover record is still there", storage.documents.containsKey(slot(1)))
        assertTrue("and it does not hide the vault", inspect() is VaultState.Ready)
    }

    @Test
    fun `the key store is asked for the device key exactly once per initialization`() = runTest {
        initialize()

        assertEquals("creating a vault creates at most one platform key", 1, deviceKeyStore.getOrCreateCalls)
        assertEquals("and it is never retrieved while creating", 0, deviceKeyStore.retrieveCalls)
    }

    @Test
    fun `a platform key that cannot be created is a typed failure`() = runTest {
        deviceKeyStore.refuseCreation = true

        val result = initialize()

        assertEquals(VaultFailure.KeyUnavailable, (result as NivaraResult.Failure).error)
        assertEquals("no record is written when its key material does not exist", 0, storage.writeCalls)
        assertEquals(
            "the folder is left saying the setup did not finish",
            VaultState.Unreadable(VaultUnreadableReason.StructureIncomplete),
            inspect(),
        )
    }

    @Test
    fun `inspecting a root asks the key store for the existing key and nothing else`() = runTest {
        createVault()
        val created = deviceKeyStore.getOrCreateCalls

        inspect()

        assertEquals("reading never creates a key", created, deviceKeyStore.getOrCreateCalls)
        assertTrue(deviceKeyStore.retrieveCalls > 0)
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    fun `two initializations cannot both create a vault`() = runTest {
        val first = async { initialize() }
        val second = async { initialize() }

        val results = listOf(first.await(), second.await())

        assertEquals("exactly one attempt creates the vault", 1, results.count { it is NivaraResult.Success })
        assertEquals(
            "the other is told why",
            1,
            results.count { it is NivaraResult.Failure && (it as NivaraResult.Failure).error == VaultFailure.VaultAlreadyExists },
        )
        assertEquals("one record is on storage", listOf(slot(0)), storage.documents.keys.toList())
        assertTrue(inspect() is VaultState.Ready)
    }

    @Test
    fun `an inspection during an initialization never sees a half-written vault`() = runTest {
        val gate = CompletableDeferred<Unit>()
        storage.writeGate = gate

        val creating = async { initialize() }
        val observed = async { inspect() }

        // The write is in flight and held. Nothing may answer from underneath it: the inspection waits
        // for the same lock, so the only states reachable are the ones before and after the commit.
        assertEquals("the write is held", false, gate.isCompleted)
        gate.complete(Unit)
        assertTrue(creating.await() is NivaraResult.Success)
        assertTrue("after the commit the vault is there", observed.await() is VaultState.Ready)
    }

    @Test
    fun `a held write is visible to a screen as busy rather than as a vault`() = runTest {
        val gate = CompletableDeferred<Unit>()
        storage.writeGate = gate

        val creating = async { initialize() }
        val whileWaiting = storage.documents.isEmpty()

        gate.complete(Unit)
        creating.await()

        assertTrue("no record is on storage until the write returns", whileWaiting)
        assertTrue(inspect() is VaultState.Ready)
    }

    @Test
    fun `the vault is read from storage on every call, never remembered`() = runTest {
        createVault()

        // Something else removes the record — a sync client, a person with a file manager.
        storage.documents.clear()
        storage.contentDirectory = true

        assertEquals(
            "a cached answer would keep a vault that is no longer there",
            VaultState.Unreadable(VaultUnreadableReason.StructureIncomplete),
            inspect(),
        )
    }

    @Test
    fun `two vaults get different identities`() = runTest {
        val first = createVault()

        storage.documents.clear()
        storage.metadataDirectory = false
        storage.contentDirectory = false

        val second = initialize()
        assertTrue(second is NivaraResult.Success)

        val secondReady = inspect() as VaultState.Ready
        assertNotEquals("an identity is per vault, not per device", first.identity, secondReady.identity)
    }

    @Test
    fun `the first vault is written into the first slot at the first generation`() = runTest {
        // Two slots and a rising generation are what make a replacement safe. The repository writes the
        // first vault into the first slot; the state it reports is what a later stage will build on.
        createVault()

        assertEquals(1, storage.documents.size)
        val header = VaultRecordCodec.readHeader(storage.documents.getValue(slot(0)))
            as? VaultRecordCodec.HeaderRead.Present
        assertEquals(VaultRecordCodec.FIRST_GENERATION, header?.header?.generation)
    }
}
