package com.nivara.app.data.vault

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.valueOrNull
import com.nivara.app.domain.security.EncryptionContext
import com.nivara.app.domain.security.EncryptionKey
import com.nivara.app.domain.security.EncryptionService
import com.nivara.app.domain.vault.VaultFailure
import com.nivara.app.domain.vault.VaultIndexRepository
import com.nivara.app.domain.vault.VaultIndexState
import com.nivara.app.domain.vault.VaultIndexUnreadable
import com.nivara.app.domain.vault.VaultItemId
import com.nivara.app.domain.vault.VaultLocation
import com.nivara.app.domain.vault.VaultRepository
import com.nivara.app.domain.vault.VaultState
import com.nivara.app.domain.vault.VaultTrashEntry
import com.nivara.app.domain.vault.VaultTrashFailure
import com.nivara.app.domain.vault.VaultTrashLimits
import com.nivara.app.domain.vault.VaultTrashRepository
import com.nivara.app.domain.vault.VaultTrashState
import com.nivara.app.domain.vault.VaultTrashUnreadable
import com.nivara.app.domain.vault.VaultUnreadableReason
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The vault's trash, and how it is changed.
 *
 * ### The order that makes a change true
 *
 * The trash exists in the form the record on storage describes, and a change is real only when the
 * record that describes it has been read back and authenticated:
 *
 * ```
 *  authorized → vault ready → read the current record (never rebuild it)
 *      → verify the item is where the change says it is
 *      → apply the change in memory → validate the whole result
 *      → seal it as the next generation in the purpose that belongs to trash
 *      → write it into the slot that is not authoritative
 *      → read it back: authenticate, decode, compare with what was intended
 *      → only then prune the superseded slot
 * ```
 *
 * An interruption anywhere leaves the previous generation untouched: the new record goes into the
 * other slot and the older one is removed only after its replacement has been read back. A write that
 * fails is deleted, and a read-back that disagrees is deleted too, because a record Nivara cannot
 * verify is not a record it may leave behind for the next reader to trust.
 *
 * ### What a change never touches
 *
 * Nothing here opens, decrypts, moves, renames or deletes an item's encrypted object, and nothing here
 * writes to the content area at all. Trashing an item adds one line to a list of references; the
 * file, its metadata and its album memberships are untouched. That is why there is no deletion of
 * content in this file: there is no operation that could express one.
 *
 * ### Why the vault's own record is read first
 *
 * The key is borrowed through [VaultKeyAccess], which reads the vault record and unwraps the vault key
 * from it. So a trash change is impossible in a vault that cannot be opened, and it is impossible
 * without the key that protects everything else in the vault — the trash is not a side channel around
 * the vault's own gate.
 *
 * ### Why moving an item to trash reads the vault's list
 *
 * Trashing must prove the file exists in the vault, and the vault's list is the only thing that can
 * prove it. So the list is read once, inside the same mutation, before the record is written: an item
 * the index does not name is refused rather than written into the trash as a reference to nothing.
 * Restore does not need that proof — it removes a line from the trash record, and the item it named
 * becomes visible again exactly as the index describes it.
 */
internal class NivaraVaultTrashRepository(
    private val vaultRepository: VaultRepository,
    private val indexRepository: VaultIndexRepository,
    private val keyAccess: VaultKeyAccess,
    private val metadataStorageFactory: (VaultLocation) -> VaultRootStorage,
    private val encryptionService: EncryptionService,
    private val clock: () -> Long = System::currentTimeMillis,
    private val mutationLock: Mutex = Mutex(),
) : VaultTrashRepository {

    // ------------------------------------------------------------------ reading

    /** Reads the vault, then the trash record that belongs to it. Neither is repaired or created. */
    override suspend fun read(): VaultTrashState {
        val vault = vaultRepository.inspect()
        if (vault !is VaultState.Ready) return VaultTrashState.VaultNotReady(vault)

        val read = keyAccess.withVaultKey { location, key ->
            NivaraResult.Success(readRecord(location = location, key = key))
        }
        return when (read) {
            is NivaraResult.Success -> read.value.toState()
            // The record could not be looked at. That is not "nothing is trashed": the reason is
            // reported as its own state, so nothing here can be read as an empty trash.
            is NivaraResult.Failure -> read.error.asTrashUnreadableState()
        }
    }

    // ------------------------------------------------------------------ changing

    /**
     * Moves an item out of the active collection.
     *
     * The item must be one the vault's readable list names. An item already in the trash changes
     * nothing and is reported as a success: the state the person asked for already holds, and a new
     * generation that says the same thing is a write that can only fail.
     */
    override suspend fun trash(
        itemId: VaultItemId,
        authorize: () -> Boolean,
    ): NivaraResult<VaultTrashEntry> = mutate(
        authorize = authorize,
        requiresIndex = true,
    ) { entries, index, now ->
        val existing = entries.firstOrNull { entry -> entry.itemId == itemId }
        if (existing != null) return@mutate Plan.Unchanged(existing)
        if (entries.size >= VaultTrashLimits.MAXIMUM_TRASHED_ITEMS) {
            return@mutate Plan.Refused(VaultTrashFailure.TrashFull)
        }
        if (index == null || index.items.none { item -> item.id == itemId }) {
            return@mutate Plan.Refused(VaultTrashFailure.ItemNotInVault)
        }
        val entry = VaultTrashEntry(itemId = itemId, trashedAtEpochMillis = now)
        Plan.Applied(entries = entries + entry, value = entry)
    }

    /**
     * Moves an item back into the active collection.
     *
     * The same identifier, the same metadata, the same encrypted object and the same album memberships
     * it had before — this removes one line from the trash record. An item that is not in the trash
     * changes nothing and is reported as a success.
     */
    override suspend fun restore(
        itemId: VaultItemId,
        authorize: () -> Boolean,
    ): NivaraResult<Unit> = mutate(
        authorize = authorize,
        requiresIndex = false,
    ) { entries, _, _ ->
        if (entries.none { entry -> entry.itemId == itemId }) return@mutate Plan.Unchanged(Unit)
        Plan.Applied(
            entries = entries.filterNot { entry -> entry.itemId == itemId },
            value = Unit,
        )
    }

    // ------------------------------------------------------------------ the shared mutation

    /** What a mutation decided to do to the trash list. */
    private sealed interface Plan<out T> {

        /** The change is refused, with the typed reason. Nothing is written. */
        data class Refused(val failure: VaultTrashFailure) : Plan<Nothing>

        /** The requested state already holds, so the record is left exactly as it is. */
        data class Unchanged<T>(val value: T) : Plan<T>

        /** The record is to be replaced with [entries], and [value] describes what was done. */
        data class Applied<T>(val entries: List<VaultTrashEntry>, val value: T) : Plan<T>
    }

    /**
     * Runs one change: authorized, planned against the current record, written, verified, pruned.
     *
     * The whole mutation runs under one lock, so two changes cannot interleave their generations, and
     * a read never lands between the two steps of a commit. Reads do not take it: what they read is one
     * complete record, and a commit in progress is invisible until it finishes.
     *
     * @param requiresIndex whether this change has to prove something about the vault's contents. Only
     *   moving an item to trash does; restore removes a reference and needs the trash record alone. A
     *   plan that asked for the index is the only one that may treat `index == null` as an answer —
     *   `null` there means the vault's list does not name anything, including the item in question.
     */
    private suspend fun <T> mutate(
        authorize: () -> Boolean,
        requiresIndex: Boolean,
        plan: (entries: List<VaultTrashEntry>, index: VaultIndexState.Ready?, now: Long) -> Plan<T>,
    ): NivaraResult<T> = mutationLock.withLock {
        // Asked before anything is read, so a closed session cannot even look at the vault.
        if (!authorize()) return NivaraResult.Failure(VaultTrashFailure.NotAuthorized)

        val vault = vaultRepository.inspect()
        if (vault !is VaultState.Ready) {
            return NivaraResult.Failure(VaultTrashFailure.VaultNotReady(vault))
        }

        // Read inside the lock and before the write: the proof that the item is in the vault is made
        // against the same state the change is planned from.
        val indexState = if (requiresIndex) indexRepository.read() else null

        val outcome = keyAccess.withVaultKey { location, key ->
            commit(
                location = location,
                key = key,
                authorize = authorize,
                indexState = indexState,
                plan = plan,
            )
        }
        return when (outcome) {
            is NivaraResult.Success -> outcome
            is NivaraResult.Failure -> NivaraResult.Failure(outcome.error.toTrashFailure())
        }
    }

    /** One commit, inside a single borrow of the vault key. */
    private suspend fun <T> commit(
        location: VaultLocation,
        key: EncryptionKey,
        authorize: () -> Boolean,
        indexState: VaultIndexState?,
        plan: (entries: List<VaultTrashEntry>, index: VaultIndexState.Ready?, now: Long) -> Plan<T>,
    ): NivaraResult<T> {
        val read = readRecord(location = location, key = key)
        val entries = when (read) {
            is TrashRead.Present -> read.entries
            TrashRead.Missing -> emptyList()
            is TrashRead.Unreadable ->
                return NivaraResult.Failure(VaultTrashFailure.TrashUnreadable(read.reason))

            is TrashRead.UnsupportedVersion ->
                return NivaraResult.Failure(VaultTrashFailure.UnsupportedVersion(read.fileVersion))

            is TrashRead.Problem -> return NivaraResult.Failure(read.failure)
        }
        val previousGeneration = (read as? TrashRead.Present)?.generation ?: 0L
        val previousSlot = (read as? TrashRead.Present)?.slot

        // A list that cannot be read is not an empty vault: each reason keeps its own failure rather
        // than being reported as "the item is not in the vault".
        val index: VaultIndexState.Ready? = if (indexState == null) {
            null
        } else {
            when (indexState) {
                is VaultIndexState.Ready -> indexState
                VaultIndexState.Missing -> null
                is VaultIndexState.Unreadable ->
                    return NivaraResult.Failure(VaultTrashFailure.IndexUnreadable(indexState.reason))

                is VaultIndexState.UnsupportedVersion ->
                    return NivaraResult.Failure(VaultTrashFailure.IndexUnsupportedVersion(indexState.fileVersion))

                VaultIndexState.Unavailable -> return NivaraResult.Failure(VaultTrashFailure.IndexUnavailable)
                VaultIndexState.AccessDenied -> return NivaraResult.Failure(VaultTrashFailure.AccessDenied)
                is VaultIndexState.VaultNotReady ->
                    return NivaraResult.Failure(VaultTrashFailure.VaultNotReady(indexState.vault))
            }
        }

        return when (val decided = plan(entries, index, clock())) {
            is Plan.Refused -> NivaraResult.Failure(decided.failure)
            // Nothing changed, so nothing is written and nothing can fail. A no-op that rewrote the
            // record would be a chance to lose it for no reason.
            is Plan.Unchanged -> NivaraResult.Success(decided.value)

            is Plan.Applied -> {
                // Asked again at the moment the vault would change: a session that ended while the
                // person was looking at the trash does not get to move a file.
                if (!authorize()) return NivaraResult.Failure(VaultTrashFailure.NotAuthorized)
                when (
                    val written = commitEntries(
                        location = location,
                        key = key,
                        previousGeneration = previousGeneration,
                        previousSlot = previousSlot,
                        entries = decided.entries,
                    )
                ) {
                    is NivaraResult.Success -> NivaraResult.Success(decided.value)
                    is NivaraResult.Failure -> NivaraResult.Failure(written.error)
                }
            }
        }
    }

    /**
     * Writes the next generation and proves it landed.
     *
     * The record is encoded, sealed and written into the slot that is not authoritative; it is then
     * read back, authenticated, decoded and compared with the entries that were intended. Only when
     * all of that agrees is the superseded slot pruned — and pruning stays best effort, because the
     * newer generation already wins on the next read whether or not cleanup succeeded.
     *
     * The entries are put in the record's canonical order before anything is encoded, so one set of
     * entries has exactly one encoding and the read-back can be compared with what was intended
     * rather than with a reordering of it.
     */
    private suspend fun commitEntries(
        location: VaultLocation,
        key: EncryptionKey,
        previousGeneration: Long,
        previousSlot: String?,
        entries: List<VaultTrashEntry>,
    ): NivaraResult<Unit> {
        val storage = metadataStorageFactory(location)
        val nextGeneration = previousGeneration + VaultTrashCodec.FIRST_GENERATION
        val canonical = entries.sortedBy { entry -> entry.itemId.value }

        val payload = VaultTrashCodec.encodePayload(generation = nextGeneration, entries = canonical)
            ?: return NivaraResult.Failure(VaultTrashFailure.TrashFull)

        val envelope = encryptionService.encrypt(
            plaintext = payload,
            key = key,
            context = EncryptionContext.VaultTrash,
            clearPlaintextAfterUse = true,
        ).valueOrNull() ?: return NivaraResult.Failure(VaultTrashFailure.CryptographyFailed)

        val record = VaultTrashCodec.encodeRecord(generation = nextGeneration, envelope = envelope)
        val target = targetSlot(previousSlot = previousSlot, generation = nextGeneration)

        if (storage.writeMetadata(target, record) !is NivaraResult.Success) {
            storage.deleteMetadata(target)
            return NivaraResult.Failure(VaultTrashFailure.WriteFailed)
        }

        val readBack = storage.readMetadata(target).valueOrNull()
        if (readBack == null || !recordValidates(readBack, nextGeneration, canonical, key)) {
            // A record that cannot be read back is not left behind: the next reader would find it and
            // have to decide what a record that does not authenticate means.
            storage.deleteMetadata(target)
            return NivaraResult.Failure(VaultTrashFailure.VerificationFailed)
        }

        VaultStructure.TRASH_SLOT_NAMES
            .filter { name -> name != target }
            .forEach { name -> storage.deleteMetadata(name) }
        return NivaraResult.Success(Unit)
    }

    /** Whether [bytes] is exactly the generation that was meant to be written, and nothing else. */
    private suspend fun recordValidates(
        bytes: ByteArray,
        generation: Long,
        entries: List<VaultTrashEntry>,
        key: EncryptionKey,
    ): Boolean {
        val header = VaultTrashCodec.readHeader(bytes)
        if (header !is VaultTrashCodec.HeaderRead.Present) return false
        if (header.generation != generation) return false
        val payloadBytes = encryptionService.decrypt(
            envelope = VaultTrashCodec.envelopeOf(bytes),
            key = key,
            context = EncryptionContext.VaultTrash,
        ).valueOrNull() ?: return false
        val payload = VaultTrashCodec.decodePayload(
            bytes = payloadBytes,
            expectedGeneration = generation,
        ) ?: return false
        return payload.entries == entries
    }

    // ------------------------------------------------------------------ reading one record

    /** One look at the trash slots, before anything is decided from it. */
    private sealed interface TrashRead {

        data class Present(
            val generation: Long,
            val entries: List<VaultTrashEntry>,
            val slot: String,
        ) : TrashRead

        /** No slot holds a trash record: nothing has ever been moved to trash in this vault. */
        data object Missing : TrashRead

        data class Unreadable(val reason: VaultTrashUnreadable) : TrashRead

        data class UnsupportedVersion(val fileVersion: Int) : TrashRead

        data class Problem(val failure: VaultTrashFailure) : TrashRead
    }

    /** What a look at the trash slots means to a caller. Every case keeps its own name. */
    private fun TrashRead.toState(): VaultTrashState = when (this) {
        is TrashRead.Present -> VaultTrashState.Ready(entries)
        TrashRead.Missing -> VaultTrashState.Missing
        is TrashRead.Unreadable -> VaultTrashState.Unreadable(reason)
        is TrashRead.UnsupportedVersion -> VaultTrashState.UnsupportedVersion(fileVersion)
        // A problem looking at the slots is not a record that is damaged and not an absence of one:
        // the vault's storage could not be listed, and the state says exactly that.
        is TrashRead.Problem -> failure.asTrashUnreadableState()
    }

    /**
     * The state that means "the trash record could not be read", for a typed trash failure.
     *
     * Only the failures that describe an unreadable record can arrive here; anything else is reported
     * as unreachable storage rather than as damage, because claiming damage would suggest the record is
     * at fault when the storage was.
     */
    private fun VaultTrashFailure.asTrashUnreadableState(): VaultTrashState = when (this) {
        is VaultTrashFailure.AccessDenied -> VaultTrashState.AccessDenied
        is VaultTrashFailure.KeyUnavailable ->
            VaultTrashState.Unreadable(VaultTrashUnreadable.KeyUnavailable)

        is VaultTrashFailure.TrashUnreadable -> VaultTrashState.Unreadable(reason)
        is VaultTrashFailure.UnsupportedVersion -> VaultTrashState.UnsupportedVersion(fileVersion)
        else -> VaultTrashState.Unavailable
    }

    /**
     * Reads the newest trash record that opens, or reports why none could be read.
     *
     * Every slot is looked at and the highest generation that authenticates wins. A slot holding
     * something that is not a Nivara trash record is ignored — a foreign file in the area, the rule the
     * vault's own record follows — while one carrying Nivara's marker that cannot be used is reported
     * rather than passed over, because a reader that skipped it could not tell "nothing has been
     * trashed" from "the trash cannot be read".
     */
    private suspend fun readRecord(location: VaultLocation, key: EncryptionKey): TrashRead {
        val storage = metadataStorageFactory(location)

        val entries = storage.metadataEntries()
        val names = entries.valueOrNull() ?: return TrashRead.Problem(entries.trashFailureOf())

        var best: TrashRead.Present? = null
        var unreadable: VaultTrashUnreadable? = null
        var unreadableVersion: Int? = null

        for (slot in VaultStructure.TRASH_SLOT_NAMES) {
            if (slot !in names) continue
            val bytes = storage.readMetadata(slot)
            val record = bytes.valueOrNull()
            if (record == null) {
                unreadable = VaultTrashUnreadable.MetadataDamaged
                continue
            }
            when (val header = VaultTrashCodec.readHeader(record)) {
                VaultTrashCodec.HeaderRead.NotATrashRecord -> Unit
                is VaultTrashCodec.HeaderRead.Unreadable -> {
                    val version = header.fileVersion
                    if (version != null && version != VaultTrashCodec.VERSION) {
                        unreadableVersion = version
                    } else {
                        unreadable = VaultTrashUnreadable.MetadataDamaged
                    }
                }

                is VaultTrashCodec.HeaderRead.Present -> when (
                    val opened = openRecord(record, header.generation, key)
                ) {
                    is TrashOpen.Opened ->
                        if (best == null || opened.generation > best.generation) {
                            best = TrashRead.Present(
                                generation = opened.generation,
                                entries = opened.entries,
                                slot = slot,
                            )
                        }

                    TrashOpen.KeyUnavailable -> unreadable = VaultTrashUnreadable.KeyUnavailable
                    TrashOpen.Damaged -> unreadable = VaultTrashUnreadable.MetadataDamaged
                }
            }
        }

        best?.let { present -> return present }
        unreadableVersion?.let { version -> return TrashRead.UnsupportedVersion(version) }
        unreadable?.let { reason -> return TrashRead.Unreadable(reason) }
        return TrashRead.Missing
    }

    private sealed interface TrashOpen {

        data class Opened(val generation: Long, val entries: List<VaultTrashEntry>) : TrashOpen

        data object KeyUnavailable : TrashOpen

        data object Damaged : TrashOpen
    }

    /**
     * Authenticates one trash record and checks its sealed payload against its clear header.
     *
     * The generation is compared in both places on purpose: the clear header is not covered by the
     * envelope, so a record whose payload disagrees with its own header is one somebody edited, and it
     * is refused rather than trusted.
     */
    private suspend fun openRecord(
        bytes: ByteArray,
        headerGeneration: Long,
        key: EncryptionKey,
    ): TrashOpen = when (val payloadBytes = encryptionService.decrypt(
        envelope = VaultTrashCodec.envelopeOf(bytes),
        key = key,
        context = EncryptionContext.VaultTrash,
    ).valueOrNull()) {
        null -> TrashOpen.Damaged
        else -> when (val payload = VaultTrashCodec.decodePayload(payloadBytes, headerGeneration)) {
            null -> TrashOpen.Damaged
            else -> TrashOpen.Opened(generation = payload.generation, entries = payload.entries)
        }
    }

    /**
     * The slot a new generation is written into: the one that is not the record just read.
     *
     * With no record yet, generations start at one and the first slot is used — the same rule applied
     * to a generation of zero.
     */
    private fun targetSlot(previousSlot: String?, generation: Long): String {
        val names = VaultStructure.TRASH_SLOT_NAMES
        val other = previousSlot?.let { slot -> names.firstOrNull { name -> name != slot } }
        if (other != null) return other
        val generationIndex = ((generation - VaultTrashCodec.FIRST_GENERATION) % names.size).toInt()
        return names[generationIndex]
    }
}

/**
 * What "the trash record could not be read" means, for whatever opening the vault reported.
 *
 * A read never invents an empty trash and never claims the record is damaged when the real reason is
 * that the vault could not be opened at all: each reason keeps its own state.
 */
private fun Exception?.asTrashUnreadableState(): VaultTrashState = when (this) {
    is VaultFailure.AccessDenied -> VaultTrashState.AccessDenied
    is VaultFailure.KeyUnavailable ->
        VaultTrashState.Unreadable(VaultTrashUnreadable.KeyUnavailable)

    is VaultFailure.VaultUnreadable ->
        VaultTrashState.Unreadable(
            if (reason == VaultUnreadableReason.KeyUnavailable) {
                VaultTrashUnreadable.KeyUnavailable
            } else {
                VaultTrashUnreadable.MetadataDamaged
            },
        )

    is VaultFailure.UnsupportedVersion -> VaultTrashState.UnsupportedVersion(fileVersion)
    else -> VaultTrashState.Unavailable
}

/** The trash failure behind whatever the key access reported. */
private fun Exception?.toTrashFailure(): VaultTrashFailure = when (this) {
    null -> VaultTrashFailure.MetadataUnavailable
    is VaultTrashFailure -> this
    is VaultFailure -> when (this) {
        is VaultFailure.KeyUnavailable -> VaultTrashFailure.KeyUnavailable
        is VaultFailure.AccessDenied -> VaultTrashFailure.AccessDenied
        is VaultFailure.StorageUnavailable -> VaultTrashFailure.MetadataUnavailable
        is VaultFailure.WriteFailed -> VaultTrashFailure.WriteFailed
        is VaultFailure.VerificationFailed -> VaultTrashFailure.VerificationFailed
        is VaultFailure.CryptographyFailed -> VaultTrashFailure.CryptographyFailed
        is VaultFailure.UnsupportedVersion -> VaultTrashFailure.UnsupportedVersion(fileVersion)
        is VaultFailure.VaultUnreadable ->
            if (reason == VaultUnreadableReason.KeyUnavailable) {
                VaultTrashFailure.KeyUnavailable
            } else {
                VaultTrashFailure.VaultNotReady(VaultState.Unreadable(reason))
            }

        else -> VaultTrashFailure.MetadataUnavailable
    }

    else -> VaultTrashFailure.MetadataUnavailable
}

/** The trash failure behind a storage result that refused. */
private fun NivaraResult<*>.trashFailureOf(): VaultTrashFailure =
    when (val error = (this as? NivaraResult.Failure)?.error) {
        is VaultFailure.AccessDenied -> VaultTrashFailure.AccessDenied
        is VaultFailure.StorageUnavailable -> VaultTrashFailure.MetadataUnavailable
        is VaultFailure.KeyUnavailable -> VaultTrashFailure.KeyUnavailable
        else -> VaultTrashFailure.MetadataUnavailable
    }
