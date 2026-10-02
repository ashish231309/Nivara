package com.nivara.app.data.vault

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.valueOrNull
import com.nivara.app.data.security.Hkdf
import com.nivara.app.domain.credential.TimeProvider
import com.nivara.app.domain.security.ContentKeyWrapper
import com.nivara.app.domain.security.CryptographicFailure
import com.nivara.app.domain.security.DeviceKeyStore
import com.nivara.app.domain.security.EncryptionContext
import com.nivara.app.domain.security.EncryptionKey
import com.nivara.app.domain.security.EncryptionService
import com.nivara.app.domain.security.RecoveryKeyEnvelopeService
import com.nivara.app.domain.vault.RecoveryCodeCodec
import com.nivara.app.domain.vault.RecoveryStatus
import com.nivara.app.domain.vault.VaultIdentity
import com.nivara.app.domain.vault.VaultLocation
import com.nivara.app.domain.vault.VaultLocationRead
import com.nivara.app.domain.vault.VaultLocationStore
import com.nivara.app.domain.vault.VaultRecoveryFailure
import com.nivara.app.domain.vault.VaultRecoveryRepository
import com.nivara.app.domain.vault.VaultRecoverySurvey
import com.nivara.app.domain.vault.VaultState
import com.nivara.app.domain.vault.displayFingerprint
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Recovery and reconnection for a vault that outlived this installation.
 *
 * ### What this class does, and only this
 *
 * Three explicit operations, all fail-closed:
 *
 * * [surveyRecovery] looks at a folder the user selected and says which kind of place it is — not a
 *   vault, a vault without recovery material, a vault recovery can try, or a damaged one — reading
 *   only clear headers, because after a reinstall this installation holds no key that could open
 *   anything sealed.
 * * [recover] takes the user's recovery code, unwraps the vault's *existing* key with it, proves the
 *   key belongs to exactly this vault, and reconnects the installation to the vault — the same
 *   identity, the same key, the same records, now openable again.
 * * [setUpRecovery] writes the recovery record into a connected vault and hands the one-time code to
 *   the caller.
 *
 * ### Where the cryptography comes from
 *
 * Nothing here is a new mechanism. The envelope that carries the vault key is the Stage 2 recovery
 * envelope, produced and opened by [RecoveryKeyEnvelopeService] exactly as it always was. The proof
 * binding the key to the vault is an HMAC-SHA-256 under the vault key itself — the same primitive
 * the envelope already authenticates with. Reconnection re-wraps the vault key with
 * [ContentKeyWrapper] under the [DeviceKeyStore] alias the vault has always used. No new primitive,
 * no second key, no KDF, nothing derived from a location, a device identifier or a credential.
 *
 * ### Why reconnection rewrites the vault record
 *
 * The record's payload is sealed under the wrapping key of the installation that wrote it; after a
 * reinstall that key is gone, so the record can never open again — that is what makes the vault
 * survey as unreadable instead of opening under somebody else's device key. Recovery proves
 * ownership by other means, then re-commits the *same* record — same identity, same key, next
 * generation — wrapped for this installation. Nothing else in the vault is touched: content, index,
 * albums and trash are exactly as they were, and recovery has not read more of them than the byte
 * count needed to prove they authenticate.
 *
 * ### Throttling
 *
 * Wrong-material attempts are delayed by an exponential lockout on a deterministic, injected clock.
 * The lockout is a rail, not the wall: the recovery key is 256 bits of secure randomness, so there
 * is no guessable secret to throttle against in the first place, and the envelope's authentication
 * is what actually refuses a wrong key. The counters live in memory only — an app restart clears
 * them, which is acceptable for a rail over an unguessable secret — and they reset on success.
 */
internal class NivaraVaultRecoveryRepository(
    private val vaultRepository: NivaraVaultRepository,
    private val locationStore: VaultLocationStore,
    private val storageFactory: (VaultLocation) -> VaultRootStorage,
    private val deviceKeyStore: DeviceKeyStore,
    private val contentKeyWrapper: ContentKeyWrapper,
    private val encryptionService: EncryptionService,
    private val recoveryKeyEnvelopeService: RecoveryKeyEnvelopeService,
    private val timeProvider: TimeProvider,
    private val lock: Mutex = Mutex(),
) : VaultRecoveryRepository {

    override suspend fun surveyRecovery(
        location: VaultLocation,
    ): NivaraResult<VaultRecoverySurvey> = lock.withLock {
        val seen = when (val found = surveyRecoverySlots(location)) {
            is RecoverySlots.Problem -> return found.failure
            is RecoverySlots.Seen -> found
        }

        when {
            seen.recoveryRecords.isNotEmpty() -> NivaraResult.Success(
                VaultRecoverySurvey.RecoveryAvailable(
                    identityFingerprint = seen.recoveryRecords
                        .maxByOrNull { record -> record.payload.generation }!!
                        .payload.identity.displayFingerprint(),
                ),
            )
            seen.recoveryUnsupported -> NivaraResult.Success(VaultRecoverySurvey.VaultUnsupported)
            seen.recoveryDamaged -> NivaraResult.Success(VaultRecoverySurvey.VaultDamaged)
            seen.vaultRecordEvidence -> NivaraResult.Success(VaultRecoverySurvey.RecoveryNotSetUp)
            else -> NivaraResult.Success(VaultRecoverySurvey.NotAVault)
        }
    }

    override suspend fun recover(
        location: VaultLocation,
        code: String,
    ): NivaraResult<VaultIdentity> = lock.withLock {
        val now = timeProvider.nowMillis()
        val remaining = blockedUntilMillis - now
        if (remaining > 0) {
            return NivaraResult.Failure(VaultRecoveryFailure.Locked(remainingMillis = remaining))
        }

        val recoveryKey = when (val decoded = RecoveryCodeCodec.decode(code)) {
            is NivaraResult.Success -> decoded.value
            is NivaraResult.Failure -> return when (decoded.error) {
                RecoveryCodeCodec.DecodeFailure.ChecksumMismatch ->
                    NivaraResult.Failure(VaultRecoveryFailure.CodeChecksumMismatch)
                else -> NivaraResult.Failure(VaultRecoveryFailure.CodeMalformed)
            }
        }

        val seen = when (val found = surveyRecoverySlots(location)) {
            is RecoverySlots.Problem -> return found.failure
            is RecoverySlots.Seen -> found
        }
        val newest = seen.recoveryRecords.maxByOrNull { record -> record.payload.generation }
        if (newest == null) {
            return when {
                seen.recoveryUnsupported ->
                    NivaraResult.Failure(VaultRecoveryFailure.VaultUnsupported)
                seen.recoveryDamaged ->
                    NivaraResult.Failure(VaultRecoveryFailure.VaultDamaged)
                seen.vaultRecordEvidence ->
                    NivaraResult.Failure(VaultRecoveryFailure.RecoveryNotSetUp)
                else -> NivaraResult.Failure(VaultRecoveryFailure.NotAVault)
            }
        }
        val payload = newest.payload

        // The envelope is the Stage 2 one: it authenticates or refuses, and it never yields
        // "bytes that might be a key". A refusal here is counted against the lockout.
        val vaultKey = when (val opened = recoveryKeyEnvelopeService.unsealContentKey(
            envelope = payload.envelope,
            recoveryKey = recoveryKey,
        )) {
            is NivaraResult.Success -> {
                recoveryKey.clear()
                opened.value
            }
            is NivaraResult.Failure -> {
                recoveryKey.clear()
                return when (opened.error) {
                    CryptographicFailure.UnsupportedEnvelope ->
                        NivaraResult.Failure(VaultRecoveryFailure.VaultDamaged)
                    else -> failureAfterWrongMaterial(now, VaultRecoveryFailure.WrongMaterial)
                }
            }
        }

        return try {
            if (!proofMatches(vaultKey, payload.identity, payload.proof)) {
                // A key that does not recompute this vault's proof is not this vault's key,
                // whatever it opened. Counted like a wrong secret, because that is what it is
                // from where the user stands.
                return failureAfterWrongMaterial(now, VaultRecoveryFailure.KeyMismatch)
            }

            when (val records = validateSealedRecords(location, vaultKey)) {
                ValidateOutcome.Intact -> Unit
                is ValidateOutcome.Refused -> return NivaraResult.Failure(records.failure)
            }

            val rewrapped = rewrapForThisInstallation(vaultKey)
                ?: return NivaraResult.Failure(VaultRecoveryFailure.KeyUnavailable)

            if (vaultRepository.reconnectRecord(
                    location = location,
                    identity = payload.identity,
                    wrappedKey = rewrapped.wrappedKey,
                    wrappingKey = rewrapped.wrappingKey,
                ) !is NivaraResult.Success
            ) {
                return NivaraResult.Failure(VaultRecoveryFailure.WriteFailed)
            }

            if (locationStore.storeLocation(location) !is NivaraResult.Success) {
                // The record is committed but the reference is not adopted: nothing half-done
                // survives, because the next recovery attempt simply recommits and adopts again.
                return NivaraResult.Failure(VaultRecoveryFailure.LocationUnavailable)
            }

            consecutiveFailures = 0
            blockedUntilMillis = 0L
            NivaraResult.Success(payload.identity)
        } finally {
            (vaultKey as? EncryptionKey.InProcess)?.clear()
        }
    }

    override suspend fun <T> setUpRecovery(
        presentCode: suspend (code: String) -> NivaraResult<T>,
    ): NivaraResult<T> = lock.withLock {
        val identity = when (val state = vaultRepository.inspect()) {
            is VaultState.Ready -> state.identity
            else -> return NivaraResult.Failure(VaultRecoveryFailure.VaultNotReady)
        }

        vaultRepository.withVaultKey { location, vaultKey ->
            writeRecoveryRecord(location, vaultKey, identity, presentCode)
        }
    }

    override suspend fun recoveryStatus(): NivaraResult<RecoveryStatus> = lock.withLock {
        val location = when (val stored = locationStore.storedLocation()) {
            VaultLocationRead.None -> return NivaraResult.Success(RecoveryStatus.NoVault)
            VaultLocationRead.Unreadable -> return NivaraResult.Success(RecoveryStatus.NoVault)
            is VaultLocationRead.Present -> stored.location
        }
        when (val found = surveyRecoverySlots(location)) {
            is RecoverySlots.Problem -> found.failure
            is RecoverySlots.Seen -> NivaraResult.Success(
                when {
                    found.recoveryRecords.isNotEmpty() -> RecoveryStatus.SetUp
                    found.recoveryDamaged || found.recoveryUnsupported -> RecoveryStatus.Damaged
                    else -> RecoveryStatus.NotSetUp
                },
            )
        }
    }

    // ------------------------------------------------------------------ surveying

    /** Everything one look at a candidate's recovery slots found. */
    private sealed interface RecoverySlots {

        data class Seen(
            val recoveryRecords: List<SlotRecord>,
            val recoveryDamaged: Boolean,
            val recoveryUnsupported: Boolean,
            val vaultRecordEvidence: Boolean,
        ) : RecoverySlots

        data class Problem(val failure: NivaraResult.Failure) : RecoverySlots
    }

    /** One readable recovery record: the slot it sits in and the payload it carries. */
    private data class SlotRecord(
        val slot: String,
        val payload: VaultRecoveryCodec.Payload,
    )

    /**
     * Reads the clear structure of a candidate location: recovery slots first, and the vault record
     * slots only far enough to know a Nivara vault is — or is not — behind them.
     *
     * No envelope is opened here; this works with nothing but the folder itself.
     */
    private suspend fun surveyRecoverySlots(location: VaultLocation): RecoverySlots {
        val storage = storageFactory(location)

        val metadataArea = storage.metadataAreaExists()
        val areaPresent = metadataArea.valueOrNull()
            ?: return RecoverySlots.Problem(problemOf(metadataArea))
        if (!areaPresent) {
            return RecoverySlots.Problem(
                // No structure at all is the one confident "nothing to recover here": it is what a
                // plain folder, an empty folder and another app's folder all look like.
                NivaraResult.Failure(VaultRecoveryFailure.NotAVault),
            )
        }

        val entries = storage.metadataEntries()
        val areaEntries = entries.valueOrNull()
            ?: return RecoverySlots.Problem(problemOf(entries))

        val records = mutableListOf<SlotRecord>()
        var damaged = false
        var unsupported = false

        for (slot in VaultStructure.RECOVERY_SLOT_NAMES.filter { name -> name in areaEntries }) {
            val bytes = storage.readMetadata(slot).valueOrNull()
            if (bytes == null) {
                // A slot that cannot be read at all is damage, not an absence.
                damaged = true
                continue
            }
            when (val header = VaultRecoveryCodec.readHeader(bytes)) {
                VaultRecoveryCodec.HeaderRead.NotARecoveryRecord -> damaged = true
                is VaultRecoveryCodec.HeaderRead.Unreadable -> {
                    val version = header.fileVersion
                    if (version != null && version != VaultRecoveryCodec.VERSION) {
                        unsupported = true
                    } else {
                        damaged = true
                    }
                }
                is VaultRecoveryCodec.HeaderRead.Present -> {
                    val payload = VaultRecoveryCodec.decodePayload(
                        bytes = VaultRecoveryCodec.payloadOf(bytes),
                        expectedGeneration = header.header.generation,
                    )
                    if (payload == null) {
                        damaged = true
                    } else {
                        records += SlotRecord(slot = slot, payload = payload)
                    }
                }
            }
        }

        var vaultEvidence = false
        for (slot in VaultStructure.SLOT_NAMES.filter { name -> name in areaEntries }) {
            val bytes = storage.readMetadata(slot).valueOrNull() ?: continue
            if (VaultRecordCodec.readHeader(bytes) !is VaultRecordCodec.HeaderRead.NotAVaultRecord) {
                // A readable-or-damaged vault record both mean the same thing here: whatever its
                // state, this is where a Nivara vault was written.
                vaultEvidence = true
            }
        }

        return RecoverySlots.Seen(
            recoveryRecords = records,
            recoveryDamaged = damaged,
            recoveryUnsupported = unsupported,
            vaultRecordEvidence = vaultEvidence,
        )
    }

    private fun problemOf(@Suppress("UNUSED_PARAMETER") result: NivaraResult<*>): NivaraResult.Failure {
        // The underlying failure is deliberately not surfaced: platform exceptions can carry paths,
        // grants and messages the UI must never show. A read that refused is "the folder could not
        // be read", never "there is nothing there".
        return NivaraResult.Failure(VaultRecoveryFailure.LocationUnavailable)
    }

    // ------------------------------------------------------------------ verification

    /**
     * The proof check: the vault key must recompute the commitment written into the recovery record.
     *
     * The commitment is an HMAC over the vault's identity under the vault key, so it can only be
     * produced by whoever holds the key, and it binds the envelope's contents to *this* vault:
     * another vault's envelope — even one that opens with the same code — yields a key that cannot
     * reproduce this vault's proof.
     */
    private fun proofMatches(
        vaultKey: EncryptionKey,
        identity: VaultIdentity,
        expectedProof: ByteArray,
    ): Boolean {
        val keyBytes = (vaultKey as? EncryptionKey.InProcess)?.material?.copyBytes() ?: return false
        val identityBytes = identity.toBytesOrNull() ?: return false
        val computed = try {
            Hkdf.hmac(key = keyBytes, data = VaultRecoveryCodec.PROOF_INFO + identityBytes)
        } finally {
            keyBytes.fill(0)
            identityBytes.fill(0)
        }
        val matches = computed.contentEquals(expectedProof)
        computed.fill(0)
        return matches
    }

    /** What validating the vault's sealed records under the recovered key found. */
    private sealed interface ValidateOutcome {
        data object Intact : ValidateOutcome
        data class Refused(val failure: VaultRecoveryFailure) : ValidateOutcome
    }

    /**
     * Authenticates every sealed record that is present — index, albums, trash — under the
     * recovered key, and nothing else.
     *
     * Absence stays absence: a vault that never had albums or trash has no such records, and that
     * validates cleanly. Presence must authenticate: a record that is there but does not open under
     * the key the recovery record vouched for is damage, reported as such and repaired by nothing.
     * Content objects are never opened, listed, counted or touched.
     */
    private suspend fun validateSealedRecords(
        location: VaultLocation,
        vaultKey: EncryptionKey,
    ): ValidateOutcome {
        val storage = storageFactory(location)
        val entries = storage.metadataEntries().valueOrNull()
            ?: return ValidateOutcome.Refused(VaultRecoveryFailure.LocationUnavailable)

        for (slot in VaultStructure.INDEX_SLOT_NAMES.filter { name -> name in entries }) {
            validateSlot(storage, slot, vaultKey, EncryptionContext.VaultIndex)?.let {
                return it
            } ?: continue
        }
        for (slot in VaultStructure.ORGANIZATION_SLOT_NAMES.filter { name -> name in entries }) {
            validateSlot(storage, slot, vaultKey, EncryptionContext.VaultOrganization)?.let {
                return it
            } ?: continue
        }
        for (slot in VaultStructure.TRASH_SLOT_NAMES.filter { name -> name in entries }) {
            validateSlot(storage, slot, vaultKey, EncryptionContext.VaultTrash)?.let {
                return it
            } ?: continue
        }
        return ValidateOutcome.Intact
    }

    /**
     * Validates one sealed record slot, or returns `null` when the slot holds bytes that were never
     * a record of that kind — a foreign file in the metadata area, which the record readers have
     * always stepped over.
     */
    private suspend fun validateSlot(
        storage: VaultRootStorage,
        slot: String,
        vaultKey: EncryptionKey,
        context: EncryptionContext,
    ): ValidateOutcome? {
        val bytes = storage.readMetadata(slot).valueOrNull()
            ?: return ValidateOutcome.Refused(VaultRecoveryFailure.VaultDamaged)

        val generation: Long
        val envelope: ByteArray
        val decode: (ByteArray, Long) -> Boolean
        when (context) {
            EncryptionContext.VaultIndex -> when (
                val header = VaultIndexCodec.readHeader(bytes)
            ) {
                is VaultIndexCodec.HeaderRead.Present -> {
                    generation = header.generation
                    envelope = VaultIndexCodec.envelopeOf(bytes)
                    decode = { payload, expected ->
                        VaultIndexCodec.decodePayload(payload, expected) != null
                    }
                }
                VaultIndexCodec.HeaderRead.NotAVaultIndex -> return null
                is VaultIndexCodec.HeaderRead.Unreadable ->
                    return refusalForUnreadable(header.fileVersion)
            }
            EncryptionContext.VaultOrganization -> when (
                val header = VaultOrganizationCodec.readHeader(bytes)
            ) {
                is VaultOrganizationCodec.HeaderRead.Present -> {
                    generation = header.generation
                    envelope = VaultOrganizationCodec.envelopeOf(bytes)
                    decode = { payload, expected ->
                        VaultOrganizationCodec.decodePayload(payload, expected) != null
                    }
                }
                VaultOrganizationCodec.HeaderRead.NotAnAlbumRecord -> return null
                is VaultOrganizationCodec.HeaderRead.Unreadable ->
                    return refusalForUnreadable(header.fileVersion)
            }
            EncryptionContext.VaultTrash -> when (
                val header = VaultTrashCodec.readHeader(bytes)
            ) {
                is VaultTrashCodec.HeaderRead.Present -> {
                    generation = header.generation
                    envelope = VaultTrashCodec.envelopeOf(bytes)
                    decode = { payload, expected ->
                        VaultTrashCodec.decodePayload(payload, expected) != null
                    }
                }
                VaultTrashCodec.HeaderRead.NotATrashRecord -> return null
                is VaultTrashCodec.HeaderRead.Unreadable ->
                    return refusalForUnreadable(header.fileVersion)
            }
            else -> return ValidateOutcome.Refused(VaultRecoveryFailure.VaultDamaged)
        }

        val payloadBytes = encryptionService.decrypt(
            envelope = envelope,
            key = vaultKey,
            context = context,
        ).valueOrNull()
            ?: return ValidateOutcome.Refused(VaultRecoveryFailure.VaultDamaged)
        if (!decode(payloadBytes, generation)) {
            return ValidateOutcome.Refused(VaultRecoveryFailure.VaultDamaged)
        }
        return null
    }

    private fun refusalForUnreadable(fileVersion: Int?): ValidateOutcome =
        if (fileVersion != null && fileVersion != VaultRecordCodec.VERSION) {
            ValidateOutcome.Refused(VaultRecoveryFailure.VaultUnsupported)
        } else {
            ValidateOutcome.Refused(VaultRecoveryFailure.VaultDamaged)
        }

    // ------------------------------------------------------------------ reconnecting

    /** The vault key re-wrapped for this installation, or why it could not be. */
    private data class Rewrapped(
        val wrappingKey: EncryptionKey,
        val wrappedKey: ByteArray,
    )

    /**
     * Wraps the recovered vault key under this installation's device key and proves the wrapping
     * opens before anything is committed — the same discipline the vault's creation uses.
     */
    private suspend fun rewrapForThisInstallation(vaultKey: EncryptionKey): Rewrapped? {
        val wrappingKey = deviceKeyStore.getOrCreateKey(NivaraVaultRepository.WRAPPING_KEY_ALIAS)
            .valueOrNull() ?: return null
        val wrappedKey = contentKeyWrapper.wrap(
            contentKey = vaultKey,
            wrappingKey = wrappingKey,
            context = EncryptionContext.KeyWrapping,
        ).valueOrNull() ?: return null

        val unwrapped = contentKeyWrapper.unwrap(
            wrappedKey = wrappedKey,
            wrappingKey = wrappingKey,
            context = EncryptionContext.KeyWrapping,
        ).valueOrNull() ?: return null
        val expected = (vaultKey as? EncryptionKey.InProcess)?.material
        val actual = (unwrapped as? EncryptionKey.InProcess)?.material
        val matches = expected != null && actual != null && expected.contentEquals(actual)
        (unwrapped as? EncryptionKey.InProcess)?.clear()
        if (!matches) return null
        return Rewrapped(wrappingKey = wrappingKey, wrappedKey = wrappedKey)
    }

    // ------------------------------------------------------------------ setup

    /**
     * Writes the recovery record into the connected vault, verified exactly like the vault's own
     * record: into the slot a reader would not pick, read back, parsed end to end, and only then is
     * the previous slot pruned. The code is derived from the recovery key, handed to the caller
     * once, and the key is cleared on the way out whatever happened.
     */
    private suspend fun <T> writeRecoveryRecord(
        location: VaultLocation,
        vaultKey: EncryptionKey,
        identity: VaultIdentity,
        presentCode: suspend (code: String) -> NivaraResult<T>,
    ): NivaraResult<T> {
        val recoveryKey = recoveryKeyEnvelopeService.generateRecoveryKey()
        try {
            val envelope = recoveryKeyEnvelopeService.sealContentKey(
                contentKey = vaultKey,
                recoveryKey = recoveryKey,
            ).valueOrNull()
                ?: return NivaraResult.Failure(VaultRecoveryFailure.CryptographyFailed)

            val keyBytes = (vaultKey as? EncryptionKey.InProcess)?.material?.copyBytes()
                ?: return NivaraResult.Failure(VaultRecoveryFailure.CryptographyFailed)
            val identityBytes = identity.toBytesOrNull()
                ?: return NivaraResult.Failure(VaultRecoveryFailure.CryptographyFailed)
            val proof = try {
                Hkdf.hmac(key = keyBytes, data = VaultRecoveryCodec.PROOF_INFO + identityBytes)
            } finally {
                keyBytes.fill(0)
                identityBytes.fill(0)
            }

            val storage = storageFactory(location)
            val entries = storage.metadataEntries().valueOrNull()
                ?: return NivaraResult.Failure(VaultRecoveryFailure.WriteFailed)

            val existing = mutableListOf<SlotRecord>()
            for (slot in VaultStructure.RECOVERY_SLOT_NAMES.filter { name -> name in entries }) {
                val bytes = storage.readMetadata(slot).valueOrNull() ?: continue
                val header = VaultRecoveryCodec.readHeader(bytes)
                if (header !is VaultRecoveryCodec.HeaderRead.Present) continue
                val payload = VaultRecoveryCodec.decodePayload(
                    bytes = VaultRecoveryCodec.payloadOf(bytes),
                    expectedGeneration = header.header.generation,
                ) ?: continue
                existing += SlotRecord(slot = slot, payload = payload)
            }

            val generation = (existing.maxOfOrNull { record -> record.payload.generation } ?: 0L) +
                VaultRecoveryCodec.FIRST_GENERATION
            val currentSlot = existing.maxByOrNull { record -> record.payload.generation }?.slot
            val targetSlot = if (currentSlot == null) {
                VaultStructure.RECOVERY_SLOT_NAMES.first()
            } else {
                VaultStructure.RECOVERY_SLOT_NAMES.first { name -> name != currentSlot }
            }

            val payloadBytes = VaultRecoveryCodec.encodePayload(
                generation = generation,
                identity = identity,
                proof = proof,
                envelope = envelope,
            )
            val record = VaultRecoveryCodec.encodeRecord(generation = generation, payload = payloadBytes)
            if (storage.writeMetadata(targetSlot, record) !is NivaraResult.Success) {
                storage.deleteMetadata(targetSlot)
                return NivaraResult.Failure(VaultRecoveryFailure.WriteFailed)
            }

            val readBack = storage.readMetadata(targetSlot).valueOrNull()
            val committed = readBack?.let { bytes ->
                val header = VaultRecoveryCodec.readHeader(bytes)
                if (header !is VaultRecoveryCodec.HeaderRead.Present) return@let null
                if (header.header.generation != generation) return@let null
                VaultRecoveryCodec.decodePayload(
                    bytes = VaultRecoveryCodec.payloadOf(bytes),
                    expectedGeneration = generation,
                )
            }
            val expected = VaultRecoveryCodec.Payload(
                generation = generation,
                identity = identity,
                proof = proof,
                envelope = envelope,
            )
            if (committed != expected) {
                // The bytes did not come back as they were written; the attempt's slot is cleared
                // and the previous record is untouched.
                storage.deleteMetadata(targetSlot)
                return NivaraResult.Failure(VaultRecoveryFailure.WriteFailed)
            }

            VaultStructure.RECOVERY_SLOT_NAMES
                .filter { name -> name != targetSlot }
                .forEach { name -> storage.deleteMetadata(name) }

            val code = RecoveryCodeCodec.encode(recoveryKey).valueOrNull()
                ?: return NivaraResult.Failure(VaultRecoveryFailure.CryptographyFailed)
            return presentCode(code)
        } finally {
            recoveryKey.clear()
        }
    }

    // ------------------------------------------------------------------ throttling

    /** Consecutive wrong-material attempts since the last success. In-memory by design. */
    private var consecutiveFailures: Int = 0

    /** The clock reading before which [recover] refuses to run. */
    private var blockedUntilMillis: Long = 0L

    private fun failureAfterWrongMaterial(
        now: Long,
        failure: VaultRecoveryFailure,
    ): NivaraResult<VaultIdentity> {
        consecutiveFailures += 1
        val shift = (consecutiveFailures - 1).coerceAtMost(MAXIMUM_LOCKOUT_SHIFT)
        val delay = (BASE_LOCKOUT_MILLIS shl shift).coerceAtMost(MAXIMUM_LOCKOUT_MILLIS)
        blockedUntilMillis = now + delay
        return NivaraResult.Failure(failure)
    }

    // ------------------------------------------------------------------ helpers

    private fun VaultIdentity.toBytesOrNull(): ByteArray? {
        if (!VaultIdentity.isWellFormed(value)) return null
        val bytes = ByteArray(value.length / 2)
        for (index in bytes.indices) {
            val high = value[index * 2].digitToIntOrNull(16) ?: return null
            val low = value[index * 2 + 1].digitToIntOrNull(16) ?: return null
            bytes[index] = ((high shl 4) or low).toByte()
        }
        return bytes
    }

    private companion object {

        /** First lockout after a wrong-material attempt: two seconds. */
        const val BASE_LOCKOUT_MILLIS: Int = 2_000

        /** Lockouts double per consecutive failure up to this shift… */
        const val MAXIMUM_LOCKOUT_SHIFT: Int = 7

        /** …and never exceed five minutes. */
        const val MAXIMUM_LOCKOUT_MILLIS: Int = 300_000
    }
}
