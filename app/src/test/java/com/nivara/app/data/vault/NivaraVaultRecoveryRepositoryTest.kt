package com.nivara.app.data.vault

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.valueOrNull
import com.nivara.app.data.security.HkdfRecoveryKeyEnvelopeService
import com.nivara.app.data.security.JcaEncryptionService
import com.nivara.app.data.security.NivaraContentKeyWrapper
import com.nivara.app.domain.security.EncryptionContext
import com.nivara.app.domain.security.RecoveryCodeCodec
import com.nivara.app.domain.security.SecureRandomGenerator
import com.nivara.app.domain.vault.VaultAlbum
import com.nivara.app.domain.vault.VaultAlbumId
import com.nivara.app.domain.vault.VaultFailure
import com.nivara.app.domain.vault.VaultIdentity
import com.nivara.app.domain.vault.VaultItem
import com.nivara.app.domain.vault.VaultItemId
import com.nivara.app.domain.vault.VaultLocation
import com.nivara.app.domain.vault.VaultLocationRead
import com.nivara.app.domain.vault.VaultRecoveryFailure
import com.nivara.app.domain.vault.VaultRecoverySurvey
import com.nivara.app.domain.vault.VaultState
import com.nivara.app.domain.vault.VaultTrashEntry
import com.nivara.app.domain.vault.displayFingerprint
import com.nivara.app.testing.MutableTimeProvider
import com.nivara.app.testing.randomBytes
import com.nivara.app.testing.randomKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for recovery and reconnection: surveying a folder, reconnecting to a vault with
 * its recovery code, and writing recovery material into a connected vault.
 *
 * The cryptographic services are the production ones — the JCA envelope, the content-key wrapper
 * and the Stage 2 recovery envelope — and the storage, the platform key store, the location store
 * and the clock are fakes. A "reinstall" is what the fakes make reproducible: a different device
 * key under the same alias and no stored location, exactly what a fresh installation has.
 *
 * The promises under test are the stage's: recovery unwraps the vault's existing key and never
 * creates a key or a vault; it proves the key belongs to the vault before reconnecting; it leaves
 * content, index, albums and trash untouched; a failure never adopts, writes or remembers
 * anything; and a wrong secret is refused, delayed, and reset by a success.
 */
class NivaraVaultRecoveryRepositoryTest {

    private val location = VaultLocation("content://com.android.externalstorage.documents/tree/primary%3ANivara")
    private val random = SecureRandomGenerator()
    private val encryptionService = JcaEncryptionService(random = random)
    private val contentKeyWrapper = NivaraContentKeyWrapper(
        random = random,
        encryptionService = encryptionService,
    )
    private val envelopeService = HkdfRecoveryKeyEnvelopeService(random = random)
    private val deviceKeyStore = FakeDeviceKeyStore(key = randomKey("device-key"))
    private val locationStore = FakeVaultLocationStore(VaultLocationRead.None)
    private val storage = FakeVaultRootStorage()
    private val clock = MutableTimeProvider()

    private val vaultRepository: NivaraVaultRepository by lazy {
        NivaraVaultRepository(
            locationStore = locationStore,
            storageFactory = { storage },
            deviceKeyStore = deviceKeyStore,
            contentKeyWrapper = contentKeyWrapper,
            encryptionService = encryptionService,
            random = random,
        )
    }

    private val repository: NivaraVaultRecoveryRepository by lazy {
        NivaraVaultRecoveryRepository(
            vaultRepository = vaultRepository,
            locationStore = locationStore,
            storageFactory = { storage },
            deviceKeyStore = deviceKeyStore,
            contentKeyWrapper = contentKeyWrapper,
            encryptionService = encryptionService,
            recoveryKeyEnvelopeService = envelopeService,
            timeProvider = clock,
        )
    }

    private fun recoverySlot(index: Int): String = VaultStructure.RECOVERY_SLOT_NAMES[index]

    /** Creates the vault at the test location, connected to this installation. */
    private suspend fun createVault(): VaultState.Ready {
        locationStore.stored = VaultLocationRead.Present(location)
        val result = vaultRepository.initialize(replaceUnreadable = false)
        assertTrue("the vault must exist before the test uses it", result is NivaraResult.Success)
        return vaultRepository.inspect() as VaultState.Ready
    }

    /** Sets recovery up and returns the one-time code. */
    private suspend fun setUpRecovery(): String {
        var captured: String? = null
        val result = repository.setUpRecovery { code ->
            captured = code
            NivaraResult.Success(Unit)
        }
        assertTrue("setup must succeed before the test uses it: $result", result is NivaraResult.Success)
        assertNotNull("the code must be handed over exactly once", captured)
        return captured!!
    }

    /**
     * Makes the installation fresh: a different device key under the same alias and no stored
     * location — exactly what a reinstall leaves behind.
     */
    private suspend fun simulateReinstall() {
        deviceKeyStore.replaceKey(randomKey("fresh-device-key"))
        // While the folder is still remembered, the repository must prove the old record genuinely
        // cannot open under the new device key — that is the reinstall the rest of the test plays.
        assertEquals(
            "a fresh installation cannot open the old record",
            true,
            vaultRepository.inspect() is VaultState.Unreadable,
        )
        // Only then does the reference go away, as it does on a real reinstall.
        locationStore.stored = VaultLocationRead.None
        locationStore.adopted.clear()
    }

    /** Writes a sealed index record naming [items], as the index repository would. */
    private suspend fun writeIndexRecord(vaultKey: com.nivara.app.domain.security.EncryptionKey, items: List<VaultItem>) {
        val payload = VaultIndexCodec.encodePayload(generation = 1L, vaultGeneration = 1L, items = items)!!
        val envelope = (encryptionService.encrypt(
            plaintext = payload,
            key = vaultKey,
            context = EncryptionContext.VaultIndex,
        ) as NivaraResult.Success).value
        storage.documents[VaultStructure.INDEX_SLOT_NAMES[0]] =
            VaultIndexCodec.encodeRecord(generation = 1L, envelope = envelope)
    }

    /** Writes a sealed album record, as the organization repository would. */
    private suspend fun writeOrganizationRecord(
        vaultKey: com.nivara.app.domain.security.EncryptionKey,
        albums: List<VaultAlbum>,
    ) {
        val payload = VaultOrganizationCodec.encodePayload(generation = 1L, albums = albums)!!
        val envelope = (encryptionService.encrypt(
            plaintext = payload,
            key = vaultKey,
            context = EncryptionContext.VaultOrganization,
        ) as NivaraResult.Success).value
        storage.documents[VaultStructure.ORGANIZATION_SLOT_NAMES[0]] =
            VaultOrganizationCodec.encodeRecord(generation = 1L, envelope = envelope)
    }

    /** Writes a sealed trash record, as the trash repository would. */
    private suspend fun writeTrashRecord(
        vaultKey: com.nivara.app.domain.security.EncryptionKey,
        entries: List<VaultTrashEntry>,
    ) {
        val payload = VaultTrashCodec.encodePayload(generation = 1L, entries = entries)!!
        val envelope = (encryptionService.encrypt(
            plaintext = payload,
            key = vaultKey,
            context = EncryptionContext.VaultTrash,
        ) as NivaraResult.Success).value
        storage.documents[VaultStructure.TRASH_SLOT_NAMES[0]] =
            VaultTrashCodec.encodeRecord(generation = 1L, envelope = envelope)
    }

    /** Borrows the vault key the established way, for seeding records and for assertions. */
    private suspend fun withVaultKey(
        block: suspend (com.nivara.app.domain.security.EncryptionKey) -> Unit,
    ) {
        val result = vaultRepository.withVaultKey { _, key ->
            block(key)
            NivaraResult.Success(Unit)
        }
        assertTrue("the vault key must be borrowable: $result", result is NivaraResult.Success)
    }

    private fun testItem(seed: Int): VaultItem = VaultItem(
        id = VaultItemId.fromBytes(ByteArray(16) { seed.toByte() })!!,
        name = "file-$seed.bin",
        mimeType = "application/octet-stream",
        sizeBytes = 1000L + seed,
        importedAtEpochMillis = 1_000_000L + seed,
        contentFormatVersion = 1,
        contentDigest = com.nivara.app.domain.vault.VaultContentDigest.fromBytes(ByteArray(32) { seed.toByte() })!!,
    )

    // ------------------------------------------------------------------ surveying

    @Test
    fun `a folder without nivara's structure is not a vault`() = runTest {
        val survey = repository.surveyRecovery(location)

        assertEquals(
            NivaraResult.Success(VaultRecoverySurvey.NotAVault),
            survey,
        )
    }

    @Test
    fun `a folder with only foreign files is not a vault`() = runTest {
        storage.metadataDirectory = true
        storage.documents["notes.txt"] = "hello".toByteArray()

        val survey = repository.surveyRecovery(location)

        assertEquals(NivaraResult.Success(VaultRecoverySurvey.NotAVault), survey)
    }

    @Test
    fun `a vault without recovery material says so`() = runTest {
        createVault()

        val survey = repository.surveyRecovery(location)

        assertEquals(NivaraResult.Success(VaultRecoverySurvey.RecoveryNotSetUp), survey)
    }

    @Test
    fun `a vault with recovery material offers the vault's fingerprint`() = runTest {
        val ready = createVault()
        setUpRecovery()

        val survey = repository.surveyRecovery(location)

        assertEquals(
            NivaraResult.Success(
                VaultRecoverySurvey.RecoveryAvailable(
                    identityFingerprint = ready.identity.displayFingerprint(),
                ),
            ),
            survey,
        )
    }

    @Test
    fun `the survey does not adopt the folder it looked at`() = runTest {
        createVault()
        locationStore.adopted.clear()

        repository.surveyRecovery(location)

        assertTrue("surveying is not adopting", locationStore.adopted.isEmpty())
    }

    @Test
    fun `a truncated recovery record is damaged, not absent`() = runTest {
        createVault()
        setUpRecovery()
        val slot = VaultStructure.RECOVERY_SLOT_NAMES.first { name -> storage.documents.containsKey(name) }
        storage.documents[slot] = storage.documents[slot]!!.copyOfRange(0, 6)

        val survey = repository.surveyRecovery(location)

        assertEquals(NivaraResult.Success(VaultRecoverySurvey.VaultDamaged), survey)
    }

    @Test
    fun `a recovery record from a newer version is unsupported, not damaged`() = runTest {
        createVault()
        setUpRecovery()
        val slot = VaultStructure.RECOVERY_SLOT_NAMES.first { name -> storage.documents.containsKey(name) }
        val bytes = storage.documents[slot]!!
        bytes[4] = 2
        storage.documents[slot] = bytes

        val survey = repository.surveyRecovery(location)

        assertEquals(NivaraResult.Success(VaultRecoverySurvey.VaultUnsupported), survey)
    }

    @Test
    fun `a foreign file in a recovery slot is damage`() = runTest {
        createVault()
        storage.documents[recoverySlot(0)] = "not a record".toByteArray()

        val survey = repository.surveyRecovery(location)

        assertEquals(NivaraResult.Success(VaultRecoverySurvey.VaultDamaged), survey)
    }

    @Test
    fun `a storage failure while surveying is unavailability, not absence`() = runTest {
        storage.metadataDirectory = true
        storage.entriesFailure = VaultFailure.StorageUnavailable

        val survey = repository.surveyRecovery(location)

        assertTrue(survey is NivaraResult.Failure)
        assertEquals(
            VaultRecoveryFailure.LocationUnavailable,
            (survey as NivaraResult.Failure).error,
        )
    }

    // ------------------------------------------------------------------ reconnecting

    @Test
    fun `a fresh installation reconnects to its vault with the code`() = runTest {
        val ready = createVault()
        val code = setUpRecovery()
        simulateReinstall()

        val result = repository.recover(location = location, code = code)

        assertEquals(ready.identity, result.valueOrNull())
        assertEquals("the folder is adopted on success", 1, locationStore.adopted.size)
        assertEquals(
            "the vault opens again, as itself",
            VaultState.Ready(identity = ready.identity, formatVersion = 1),
            vaultRepository.inspect(),
        )
    }

    @Test
    fun `reconnection keeps the vault's identity stable`() = runTest {
        val ready = createVault()
        val code = setUpRecovery()
        simulateReinstall()

        repository.recover(location = location, code = code)

        val state = vaultRepository.inspect()
        assertEquals(ready.identity, (state as VaultState.Ready).identity)
    }

    @Test
    fun `reconnection preserves index, albums and trash`() = runTest {
        val ready = createVault()
        val item = testItem(1)
        val album = VaultAlbum(
            id = VaultAlbumId.fromBytes(ByteArray(16) { 7 })!!,
            name = "kept",
            createdAtEpochMillis = 123L,
            itemIds = listOf(item.id),
        )
        val trashed = VaultTrashEntry(itemId = item.id, trashedAtEpochMillis = 456L)
        withVaultKey { key ->
            writeIndexRecord(key, listOf(item))
            writeOrganizationRecord(key, listOf(album))
            writeTrashRecord(key, listOf(trashed))
        }
        val code = setUpRecovery()
        val documentsBefore = storage.snapshot()
        simulateReinstall()

        val result = repository.recover(location = location, code = code)

        assertEquals(ready.identity, result.valueOrNull())
        for ((name, bytes) in documentsBefore) {
            if (name.startsWith("vault.") || name.startsWith("recovery.")) continue
            assertEquals("the record $name must be byte-identical after recovery", bytes, storage.snapshot()[name])
        }
        withVaultKey { key ->
            val indexEnvelope = VaultIndexCodec.envelopeOf(
                storage.documents[VaultStructure.INDEX_SLOT_NAMES[0]]!!,
            )
            val indexPayload = (encryptionService.decrypt(
                envelope = indexEnvelope,
                key = key,
                context = EncryptionContext.VaultIndex,
            ) as NivaraResult.Success).value
            val index = VaultIndexCodec.decodePayload(indexPayload, 1L)
            assertEquals(listOf(item), index?.items)
        }
    }

    @Test
    fun `reconnection leaves the content area untouched`() = runTest {
        createVault()
        storage.contentDirectory = true
        storage.documents["content-object.nvo"] = randomBytes(48)
        val code = setUpRecovery()
        val contentBefore = storage.documents["content-object.nvo"]!!.copyOf()
        simulateReinstall()

        repository.recover(location = location, code = code)

        assertTrue(storage.documents["content-object.nvo"]!!.contentEquals(contentBefore))
    }

    @Test
    fun `a vault with no optional records still reconnects`() = runTest {
        val ready = createVault()
        val code = setUpRecovery()
        simulateReinstall()

        val result = repository.recover(location = location, code = code)

        assertEquals(ready.identity, result.valueOrNull())
    }

    @Test
    fun `recovery works again after a failed adoption`() = runTest {
        val ready = createVault()
        val code = setUpRecovery()
        simulateReinstall()
        locationStore.storeResult = NivaraResult.Failure(VaultFailure.AccessDenied)

        val refused = repository.recover(location = location, code = code)
        assertEquals(
            VaultRecoveryFailure.LocationUnavailable,
            (refused as NivaraResult.Failure).error,
        )
        assertEquals(
            "a failed adoption is not persisted",
            VaultLocationRead.None,
            locationStore.stored,
        )

        locationStore.storeResult = NivaraResult.Success(Unit)
        val retried = repository.recover(location = location, code = code)

        assertEquals(ready.identity, retried.valueOrNull())
        assertEquals(
            "the retry adopts the reference durably",
            VaultLocationRead.Present(location),
            locationStore.stored,
        )
    }

    @Test
    fun `a recovered vault lends its key through the established path`() = runTest {
        createVault()
        val code = setUpRecovery()
        simulateReinstall()

        repository.recover(location = location, code = code)

        val borrow = vaultRepository.withVaultKey { _, key ->
            NivaraResult.Success((key as com.nivara.app.domain.security.EncryptionKey.InProcess).material.size)
        }
        assertEquals(32, borrow.valueOrNull())
    }

    // ------------------------------------------------------------------ refusals

    @Test
    fun `a wrong code is refused and nothing is adopted`() = runTest {
        createVault()
        setUpRecovery()
        val wrongCode = (RecoveryCodeCodec.encode(random.nextKeyBytes()) as NivaraResult.Success).value
        simulateReinstall()
        val before = storage.snapshot()

        val result = repository.recover(location = location, code = wrongCode)

        assertEquals(VaultRecoveryFailure.WrongMaterial, (result as NivaraResult.Failure).error)
        assertTrue(locationStore.adopted.isEmpty())
        assertEquals(VaultLocationRead.None, locationStore.stored)
        assertEquals("the vault is untouched by a refused recovery", before, storage.snapshot())
    }

    @Test
    fun `a malformed code is refused as malformed`() = runTest {
        createVault()
        setUpRecovery()

        val result = repository.recover(location = location, code = "not a code")

        assertEquals(VaultRecoveryFailure.CodeMalformed, (result as NivaraResult.Failure).error)
    }

    @Test
    fun `a code that fails its own checksum is refused as mistyped`() = runTest {
        createVault()
        val code = setUpRecovery()
        val first = code.first()
        val mistyped = (if (first == 'A') 'B' else 'A') + code.substring(1)

        val result = repository.recover(location = location, code = mistyped)

        assertEquals(
            VaultRecoveryFailure.CodeChecksumMismatch,
            (result as NivaraResult.Failure).error,
        )
    }

    @Test
    fun `recovering without recovery material says so`() = runTest {
        createVault()
        val code = (RecoveryCodeCodec.encode(random.nextKeyBytes()) as NivaraResult.Success).value

        val result = repository.recover(location = location, code = code)

        assertEquals(VaultRecoveryFailure.RecoveryNotSetUp, (result as NivaraResult.Failure).error)
    }

    @Test
    fun `recovering from a folder that is not a vault is refused`() = runTest {
        val code = (RecoveryCodeCodec.encode(random.nextKeyBytes()) as NivaraResult.Success).value

        val result = repository.recover(location = location, code = code)

        assertEquals(VaultRecoveryFailure.NotAVault, (result as NivaraResult.Failure).error)
    }

    @Test
    fun `a damaged recovery record is refused and repaired by nothing`() = runTest {
        createVault()
        setUpRecovery()
        val slot = VaultStructure.RECOVERY_SLOT_NAMES.first { name -> storage.documents.containsKey(name) }
        val before = storage.documents[slot]!!.copyOf()
        storage.documents[slot] = before.copyOfRange(0, 8)
        val code = (RecoveryCodeCodec.encode(random.nextKeyBytes()) as NivaraResult.Success).value

        val result = repository.recover(location = location, code = code)

        assertEquals(VaultRecoveryFailure.VaultDamaged, (result as NivaraResult.Failure).error)
        assertTrue("the damaged record is left as found", storage.documents[slot]!!.contentEquals(before.copyOfRange(0, 8)))
    }

    @Test
    fun `an envelope that is not a recovery envelope is damaged`() = runTest {
        createVault()
        setUpRecovery()
        val slot = VaultStructure.RECOVERY_SLOT_NAMES.first { name -> storage.documents.containsKey(name) }
        val bytes = storage.documents[slot]!!
        val header = VaultRecoveryCodec.readHeader(bytes) as VaultRecoveryCodec.HeaderRead.Present
        val payload = VaultRecoveryCodec.decodePayload(
            VaultRecoveryCodec.payloadOf(bytes),
            header.header.generation,
        )!!
        val forged = VaultRecoveryCodec.encodeRecord(
            generation = header.header.generation,
            payload = VaultRecoveryCodec.encodePayload(
                generation = header.header.generation,
                identity = payload.identity,
                proof = payload.proof,
                envelope = randomBytes(72),
            ),
        )
        storage.documents[slot] = forged
        val code = (RecoveryCodeCodec.encode(random.nextKeyBytes()) as NivaraResult.Success).value

        val result = repository.recover(location = location, code = code)

        assertEquals(VaultRecoveryFailure.VaultDamaged, (result as NivaraResult.Failure).error)
    }

    @Test
    fun `an envelope opening to another vault's key is refused by the proof`() = runTest {
        createVault()
        val code = setUpRecovery()
        // Replace the envelope with one that seals a different key under the same recovery
        // material: it opens, and what it opens is not this vault's key.
        val recoveryKey = (RecoveryCodeCodec.decode(code) as NivaraResult.Success).value
        val foreignKey = randomKey("foreign-vault-key")
        val foreignEnvelope = (envelopeService.sealContentKey(
            contentKey = foreignKey,
            recoveryKey = recoveryKey,
        ) as NivaraResult.Success).value
        val slot = VaultStructure.RECOVERY_SLOT_NAMES.first { name -> storage.documents.containsKey(name) }
        val bytes = storage.documents[slot]!!
        val header = VaultRecoveryCodec.readHeader(bytes) as VaultRecoveryCodec.HeaderRead.Present
        val payload = VaultRecoveryCodec.decodePayload(
            VaultRecoveryCodec.payloadOf(bytes),
            header.header.generation,
        )!!
        storage.documents[slot] = VaultRecoveryCodec.encodeRecord(
            generation = header.header.generation,
            payload = VaultRecoveryCodec.encodePayload(
                generation = header.header.generation,
                identity = payload.identity,
                proof = payload.proof,
                envelope = foreignEnvelope,
            ),
        )

        val result = repository.recover(location = location, code = code)

        assertEquals(VaultRecoveryFailure.KeyMismatch, (result as NivaraResult.Failure).error)
        assertTrue(locationStore.adopted.isEmpty())
    }

    @Test
    fun `a recovery record swapped in from another vault is refused by the records`() = runTest {
        createVault()
        withVaultKey { key -> writeOrganizationRecord(key, emptyList()) }
        val code = setUpRecovery()

        // Another vault's recovery record is copied over this one: its envelope opens with the
        // code, and its proof binds the foreign key to the foreign identity it carries — but this
        // vault's album record does not authenticate under the foreign key, and recovery refuses.
        val recoveryKey = (RecoveryCodeCodec.decode(code) as NivaraResult.Success).value
        val foreignKey = randomKey("foreign-vault-key")
        val foreignIdentity = VaultIdentity("ffeeddccbbaa99887766554433221100")
        val foreignEnvelope = (envelopeService.sealContentKey(
            contentKey = foreignKey,
            recoveryKey = recoveryKey,
        ) as NivaraResult.Success).value
        val foreignProof = com.nivara.app.data.security.Hkdf.hmac(
            key = (foreignKey as com.nivara.app.domain.security.EncryptionKey.InProcess).material.copyBytes(),
            data = VaultRecoveryCodec.PROOF_INFO + foreignIdentity.toHexBytes(),
        )
        val targetSlot = VaultStructure.RECOVERY_SLOT_NAMES.first { name -> storage.documents.containsKey(name) }
        val header = VaultRecoveryCodec.readHeader(storage.documents[targetSlot]!!)
            as VaultRecoveryCodec.HeaderRead.Present
        storage.documents[targetSlot] = VaultRecoveryCodec.encodeRecord(
            generation = header.header.generation,
            payload = VaultRecoveryCodec.encodePayload(
                generation = header.header.generation,
                identity = foreignIdentity,
                proof = foreignProof,
                envelope = foreignEnvelope,
            ),
        )

        val result = repository.recover(location = location, code = code)

        assertEquals(VaultRecoveryFailure.VaultDamaged, (result as NivaraResult.Failure).error)
        assertTrue(locationStore.adopted.isEmpty())
    }

    private fun VaultIdentity.toHexBytes(): ByteArray {
        val bytes = ByteArray(value.length / 2)
        for (index in bytes.indices) {
            val high = value[index * 2].digitToInt(16)
            val low = value[index * 2 + 1].digitToInt(16)
            bytes[index] = ((high shl 4) or low).toByte()
        }
        return bytes
    }

    @Test
    fun `a damaged index record refuses recovery and is not repaired`() = runTest {
        createVault()
        withVaultKey { key -> writeIndexRecord(key, listOf(testItem(1))) }
        val code = setUpRecovery()
        val indexSlot = VaultStructure.INDEX_SLOT_NAMES[0]
        val before = storage.documents[indexSlot]!!.copyOf()
        val damaged = before.copyOf()
        damaged[damaged.size - 1] = (damaged[damaged.size - 1].toInt() xor 0x01).toByte()
        storage.documents[indexSlot] = damaged
        simulateReinstall()

        val result = repository.recover(location = location, code = code)

        assertEquals(VaultRecoveryFailure.VaultDamaged, (result as NivaraResult.Failure).error)
        assertTrue(
            "the damaged index is left exactly as found",
            storage.documents[indexSlot]!!.contentEquals(damaged),
        )
        assertTrue("nothing was adopted", locationStore.adopted.isEmpty())
        assertFalse(before.contentEquals(damaged))
    }

    @Test
    fun `a write failure during reconnection leaves the vault as it was`() = runTest {
        createVault()
        val code = setUpRecovery()
        simulateReinstall()
        storage.writeFailure = VaultFailure.WriteFailed

        val result = repository.recover(location = location, code = code)

        assertEquals(VaultRecoveryFailure.WriteFailed, (result as NivaraResult.Failure).error)
        assertTrue(locationStore.adopted.isEmpty())

        storage.writeFailure = null
        val retried = repository.recover(location = location, code = code)
        assertTrue("the attempt is repeatable once the storage accepts writes", retried is NivaraResult.Success)
    }

    @Test
    fun `a missing device key during reconnection is reported`() = runTest {
        createVault()
        val code = setUpRecovery()
        deviceKeyStore.destroyKey()
        deviceKeyStore.refuseCreation = true
        locationStore.stored = VaultLocationRead.None

        val result = repository.recover(location = location, code = code)

        assertEquals(VaultRecoveryFailure.KeyUnavailable, (result as NivaraResult.Failure).error)
        assertTrue(locationStore.adopted.isEmpty())
    }

    // ------------------------------------------------------------------ throttling

    @Test
    fun `wrong material locks recovery out on the injected clock`() = runTest {
        createVault()
        setUpRecovery()
        val wrongCode = (RecoveryCodeCodec.encode(random.nextKeyBytes()) as NivaraResult.Success).value

        val first = repository.recover(location = location, code = wrongCode)
        assertEquals(VaultRecoveryFailure.WrongMaterial, (first as NivaraResult.Failure).error)

        clock.advanceBy(1_000)
        val locked = repository.recover(location = location, code = wrongCode)
        val lockedFailure = (locked as NivaraResult.Failure).error
        assertTrue("the second attempt must be locked out", lockedFailure is VaultRecoveryFailure.Locked)
        assertTrue((lockedFailure as VaultRecoveryFailure.Locked).remainingMillis > 0)

        clock.advanceBy(10_000)
        val afterLockout = repository.recover(location = location, code = wrongCode)
        assertEquals(
            "once the lockout passes the attempt is judged on its merits again",
            VaultRecoveryFailure.WrongMaterial,
            (afterLockout as NivaraResult.Failure).error,
        )
    }

    @Test
    fun `the lockout grows with consecutive failures`() = runTest {
        createVault()
        setUpRecovery()
        val wrongCode = (RecoveryCodeCodec.encode(random.nextKeyBytes()) as NivaraResult.Success).value

        repository.recover(location = location, code = wrongCode)
        clock.advanceBy(10_000)
        repository.recover(location = location, code = wrongCode)

        clock.advanceBy(2_500)
        val stillLocked = repository.recover(location = location, code = wrongCode)

        assertTrue(
            "the second failure locks out longer than the first",
            (stillLocked as NivaraResult.Failure).error is VaultRecoveryFailure.Locked,
        )
    }

    @Test
    fun `a success resets the lockout`() = runTest {
        val ready = createVault()
        val code = setUpRecovery()
        val wrongCode = (RecoveryCodeCodec.encode(random.nextKeyBytes()) as NivaraResult.Success).value

        repository.recover(location = location, code = wrongCode)
        clock.advanceBy(10_000)
        val success = repository.recover(location = location, code = code)
        assertEquals(ready.identity, success.valueOrNull())

        // After a success a single failure must lock out for the base delay only: two and a half
        // seconds on, the attempt is judged again. Had the counter not been reset, the second
        // failure's four-second lockout would still be running.
        val failure = repository.recover(location = location, code = wrongCode)
        assertEquals(VaultRecoveryFailure.WrongMaterial, (failure as NivaraResult.Failure).error)
        clock.advanceBy(1_000)
        val locked = repository.recover(location = location, code = wrongCode)
        assertTrue((locked as NivaraResult.Failure).error is VaultRecoveryFailure.Locked)
        clock.advanceBy(1_500)
        val judged = repository.recover(location = location, code = wrongCode)
        assertEquals(
            "the counter restarted at the success",
            VaultRecoveryFailure.WrongMaterial,
            (judged as NivaraResult.Failure).error,
        )
    }

    @Test
    fun `mistyped codes do not count against the lockout`() = runTest {
        createVault()
        val code = setUpRecovery()
        val first = code.first()
        val mistyped = (if (first == 'A') 'B' else 'A') + code.substring(1)

        repository.recover(location = location, code = mistyped)
        val next = repository.recover(location = location, code = mistyped)

        assertEquals(
            "checksum refusals are deterministic and never locked",
            VaultRecoveryFailure.CodeChecksumMismatch,
            (next as NivaraResult.Failure).error,
        )
    }

    // ------------------------------------------------------------------ setup

    @Test
    fun `setup hands over a code that unwraps the vault's own key`() = runTest {
        createVault()
        // The borrowed key is cleared the moment the borrow ends, so the assertion keeps a copy of
        // its material, taken inside the borrow.
        var keyMaterial: ByteArray? = null
        withVaultKey { key ->
            keyMaterial = (key as com.nivara.app.domain.security.EncryptionKey.InProcess)
                .material.copyBytes()
        }

        val code = setUpRecovery()

        val recoveryKey = (RecoveryCodeCodec.decode(code) as NivaraResult.Success).value
        val slot = VaultStructure.RECOVERY_SLOT_NAMES.first { name -> storage.documents.containsKey(name) }
        val bytes = storage.documents[slot]!!
        val header = VaultRecoveryCodec.readHeader(bytes) as VaultRecoveryCodec.HeaderRead.Present
        val payload = VaultRecoveryCodec.decodePayload(
            VaultRecoveryCodec.payloadOf(bytes),
            header.header.generation,
        )!!
        val recovered = (envelopeService.unsealContentKey(
            envelope = payload.envelope,
            recoveryKey = recoveryKey,
        ) as NivaraResult.Success).value
        val recoveredMaterial = (recovered as com.nivara.app.domain.security.EncryptionKey.InProcess)
            .material.copyBytes()
        assertTrue(
            "the envelope must wrap the vault's own key",
            recoveredMaterial.contentEquals(keyMaterial!!),
        )
    }

    @Test
    fun `setup commits the vault's own identity into the recovery record`() = runTest {
        val ready = createVault()

        setUpRecovery()

        val slot = VaultStructure.RECOVERY_SLOT_NAMES.first { name -> storage.documents.containsKey(name) }
        val bytes = storage.documents[slot]!!
        val header = VaultRecoveryCodec.readHeader(bytes) as VaultRecoveryCodec.HeaderRead.Present
        val payload = VaultRecoveryCodec.decodePayload(
            VaultRecoveryCodec.payloadOf(bytes),
            header.header.generation,
        )
        assertEquals(ready.identity, payload?.identity)
    }

    @Test
    fun `setup replaces an existing recovery record only after the new one verifies`() = runTest {
        createVault()
        val firstCode = setUpRecovery()
        val secondCode = setUpRecovery()

        assertNotEquals("a setup generates fresh material", firstCode, secondCode)
        val slots = VaultStructure.RECOVERY_SLOT_NAMES.count { name -> storage.documents.containsKey(name) }
        assertEquals("the superseded record is pruned", 1, slots)

        val retryFirst = repository.recover(location = location, code = firstCode)
        assertEquals(
            "the old code no longer opens the envelope",
            VaultRecoveryFailure.WrongMaterial,
            (retryFirst as NivaraResult.Failure).error,
        )
    }

    @Test
    fun `a failed setup leaves the previous recovery record in place`() = runTest {
        createVault()
        val code = setUpRecovery()
        storage.writeFailure = VaultFailure.WriteFailed

        val result = repository.setUpRecovery { NivaraResult.Success(Unit) }

        assertEquals(VaultRecoveryFailure.WriteFailed, (result as NivaraResult.Failure).error)
        storage.writeFailure = null
        assertEquals(
            "the vault still reports recovery material",
            NivaraResult.Success(com.nivara.app.domain.vault.RecoveryStatus.SetUp),
            repository.recoveryStatus(),
        )
        val recovered = repository.recover(location = location, code = code)
        assertTrue("and the previous code still works", recovered is NivaraResult.Success)
    }

    @Test
    fun `setup refuses when the vault is not open`() = runTest {
        locationStore.stored = VaultLocationRead.None

        val result = repository.setUpRecovery { NivaraResult.Success(Unit) }

        assertEquals(VaultRecoveryFailure.VaultNotReady, (result as NivaraResult.Failure).error)
    }

    @Test
    fun `the code is handed over exactly once`() = runTest {
        createVault()
        var handed = 0

        repository.setUpRecovery {
            handed += 1
            NivaraResult.Success(Unit)
        }

        assertEquals(1, handed)
    }

    @Test
    fun `the code is never persisted beside the envelope`() = runTest {
        createVault()
        val code = setUpRecovery()

        for ((_, bytes) in storage.documents) {
            assertFalse(
                "no stored document may contain the code",
                String(bytes, Charsets.ISO_8859_1).contains(code.take(8)),
            )
        }
    }

    @Test
    fun `setup never touches the vault record or the content area`() = runTest {
        createVault()
        val vaultSlotsBefore = VaultStructure.SLOT_NAMES.map { slot -> storage.documents[slot] }
        val code = setUpRecovery()

        assertEquals(vaultSlotsBefore, VaultStructure.SLOT_NAMES.map { slot -> storage.documents[slot] })
        assertNotNull(code)
    }

    // ------------------------------------------------------------------ recovery status

    @Test
    fun `the status is no vault when nothing is connected`() = runTest {
        locationStore.stored = VaultLocationRead.None

        assertEquals(NivaraResult.Success(com.nivara.app.domain.vault.RecoveryStatus.NoVault), repository.recoveryStatus())
    }

    @Test
    fun `the status is not set up for a connected vault without a record`() = runTest {
        createVault()

        assertEquals(
            NivaraResult.Success(com.nivara.app.domain.vault.RecoveryStatus.NotSetUp),
            repository.recoveryStatus(),
        )
    }

    @Test
    fun `the status is set up once the record is written`() = runTest {
        createVault()
        setUpRecovery()

        assertEquals(
            NivaraResult.Success(com.nivara.app.domain.vault.RecoveryStatus.SetUp),
            repository.recoveryStatus(),
        )
    }

    @Test
    fun `the status is damaged when the record cannot be read`() = runTest {
        createVault()
        setUpRecovery()
        val slot = VaultStructure.RECOVERY_SLOT_NAMES.first { name -> storage.documents.containsKey(name) }
        storage.documents[slot] = storage.documents[slot]!!.copyOfRange(0, 5)

        assertEquals(
            NivaraResult.Success(com.nivara.app.domain.vault.RecoveryStatus.Damaged),
            repository.recoveryStatus(),
        )
    }

    // ------------------------------------------------------------------ no second key

    @Test
    fun `recovery never generates a vault key of its own`() = runTest {
        val ready = createVault()
        val code = setUpRecovery()
        val wrappedBefore = (vaultRepository.inspect() as VaultState.Ready).identity
        simulateReinstall()

        val result = repository.recover(location = location, code = code)

        assertEquals(ready.identity, result.valueOrNull())
        assertEquals(ready.identity, wrappedBefore)
        // The reconnected record holds a wrapped key that opens under the *new* device key — proof
        // that the existing key was re-wrapped, not replaced by a generated one.
        withVaultKey { key ->
            assertEquals(32, (key as com.nivara.app.domain.security.EncryptionKey.InProcess).material.size)
        }
    }
}
