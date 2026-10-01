package com.nivara.app.data.vault

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.valueOrNull
import com.nivara.app.domain.security.EncryptionContext
import com.nivara.app.domain.security.EncryptionKey
import com.nivara.app.domain.security.EncryptionService
import com.nivara.app.domain.security.SecureRandomGenerator
import com.nivara.app.domain.vault.VaultAlbum
import com.nivara.app.domain.vault.VaultAlbumId
import com.nivara.app.domain.vault.VaultAlbumNames
import com.nivara.app.domain.vault.VaultFailure
import com.nivara.app.domain.vault.VaultItemId
import com.nivara.app.domain.vault.VaultLocation
import com.nivara.app.domain.vault.VaultOrganizationFailure
import com.nivara.app.domain.vault.VaultOrganizationLimits
import com.nivara.app.domain.vault.VaultOrganizationRepository
import com.nivara.app.domain.vault.VaultOrganizationState
import com.nivara.app.domain.vault.VaultOrganizationUnreadable
import com.nivara.app.domain.vault.VaultRepository
import com.nivara.app.domain.vault.VaultState
import com.nivara.app.domain.vault.VaultUnreadableReason
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The vault's albums, and how one gets changed.
 *
 * ### The order that makes a change true
 *
 * An album exists in the form the record on storage describes, and a change is real only when the
 * record that describes it has been read back and authenticated:
 *
 * ```
 *  authorized → vault ready → read the current record (never rebuild it)
 *      → apply the change in memory → validate the whole result
 *      → seal it as the next generation in the purpose that belongs to albums
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
 * writes to the content area at all. Albums name items; deleting an album therefore forgets a list of
 * names and can do nothing else. That is why there is no deletion of content in this file: there is no
 * operation that could express one.
 *
 * ### Why the vault's own record is read first
 *
 * The key is borrowed through [VaultKeyAccess], which reads the vault record and unwraps the vault key
 * from it. So an album change is impossible in a vault that cannot be opened, and it is impossible
 * without the key that protects everything else in the vault — albums are not a side channel around
 * the vault's own gate.
 */
internal class NivaraVaultOrganizationRepository(
    private val vaultRepository: VaultRepository,
    private val keyAccess: VaultKeyAccess,
    private val metadataStorageFactory: (VaultLocation) -> VaultRootStorage,
    private val encryptionService: EncryptionService,
    private val random: SecureRandomGenerator,
    private val clock: () -> Long = System::currentTimeMillis,
    private val mutationLock: Mutex = Mutex(),
) : VaultOrganizationRepository {

    // ------------------------------------------------------------------ reading

    /** Reads the vault, then the album record that belongs to it. Neither is repaired or created. */
    override suspend fun read(): VaultOrganizationState {
        val vault = vaultRepository.inspect()
        if (vault !is VaultState.Ready) return VaultOrganizationState.VaultNotReady(vault)

        val read = keyAccess.withVaultKey { location, key ->
            NivaraResult.Success(readRecord(location = location, key = key))
        }
        return when (read) {
            is NivaraResult.Success -> read.value.toState()
            // The record could not be looked at. That is not "there are no albums": the reason is
            // reported as its own state, so nothing here can be read as a vault without albums.
            is NivaraResult.Failure -> read.error.asUnreadableState()
        }
    }

    // ------------------------------------------------------------------ changing

    /**
     * Creates an album titled [name].
     *
     * The identifier is fresh randomness, checked against the albums the record already names: an
     * identifier that collided would make two albums one.
     */
    override suspend fun createAlbum(
        name: String,
        authorize: () -> Boolean,
    ): NivaraResult<VaultAlbum> = mutate(authorize = authorize) { current, now ->
        val normalized = VaultAlbumNames.normalize(name)
            ?: return@mutate Plan.Refused(VaultOrganizationFailure.InvalidAlbumName)
        if (current.size >= VaultOrganizationLimits.MAXIMUM_ALBUMS) {
            return@mutate Plan.Refused(VaultOrganizationFailure.OrganizationFull)
        }
        val id = newAlbumId(current)
            ?: return@mutate Plan.Refused(VaultOrganizationFailure.OrganizationFull)
        val album = VaultAlbum(
            id = id,
            name = normalized,
            createdAtEpochMillis = now,
            itemIds = emptyList(),
        )
        Plan.Applied(albums = current + album, value = album)
    }

    /** Renames an album. Membership is untouched, and so is every item the album names. */
    override suspend fun renameAlbum(
        albumId: VaultAlbumId,
        name: String,
        authorize: () -> Boolean,
    ): NivaraResult<VaultAlbum> = mutate(authorize = authorize) { current, _ ->
        val index = current.indexOfFirst { album -> album.id == albumId }
        if (index < 0) return@mutate Plan.Refused(VaultOrganizationFailure.AlbumNotFound)
        val normalized = VaultAlbumNames.normalize(name)
            ?: return@mutate Plan.Refused(VaultOrganizationFailure.InvalidAlbumName)
        val existing = current[index]
        if (existing.name == normalized) {
            // Nothing would change, so nothing is written: a new generation that says the same thing
            // is a write that can only fail.
            return@mutate Plan.Refused(VaultOrganizationFailure.AlbumNameUnchanged)
        }
        val renamed = existing.copy(name = normalized)
        Plan.Applied(albums = current.toMutableList().also { list -> list[index] = renamed }, value = renamed)
    }

    /**
     * Deletes an album, and only the album.
     *
     * The items it named keep their encrypted objects, their place in the index and their membership
     * in any other album: this operation removes one list of references from one record.
     */
    override suspend fun deleteAlbum(
        albumId: VaultAlbumId,
        authorize: () -> Boolean,
    ): NivaraResult<Unit> = mutate(authorize = authorize) { current, _ ->
        if (current.none { album -> album.id == albumId }) {
            return@mutate Plan.Refused(VaultOrganizationFailure.AlbumNotFound)
        }
        Plan.Applied(albums = current.filterNot { album -> album.id == albumId }, value = Unit)
    }

    /**
     * Adds [itemId] to an album.
     *
     * The item does not have to be in the index for the reference to be written — an album names
     * identifiers, and an identifier the index no longer names is exactly the stale case the album
     * shows as missing rather than hides. Adding an item that is already there changes nothing and is
     * reported as a success: the state the caller asked for already holds.
     */
    override suspend fun addItem(
        albumId: VaultAlbumId,
        itemId: VaultItemId,
        authorize: () -> Boolean,
    ): NivaraResult<VaultAlbum> = mutate(authorize = authorize) { current, _ ->
        val index = current.indexOfFirst { album -> album.id == albumId }
        if (index < 0) return@mutate Plan.Refused(VaultOrganizationFailure.AlbumNotFound)
        val existing = current[index]
        if (existing.contains(itemId)) return@mutate Plan.Unchanged(existing)
        if (existing.size >= VaultOrganizationLimits.MAXIMUM_MEMBERS_PER_ALBUM) {
            return@mutate Plan.Refused(VaultOrganizationFailure.AlbumFull)
        }
        val added = existing.adding(itemId)
        Plan.Applied(albums = current.toMutableList().also { list -> list[index] = added }, value = added)
    }

    /**
     * Removes [itemId] from an album.
     *
     * The item itself is untouched: it stays in the vault, in the index and in every other album it
     * is in. Removing something that is not there changes nothing and is reported as a success. An
     * album that loses its last item stays, because it was created on purpose.
     */
    override suspend fun removeItem(
        albumId: VaultAlbumId,
        itemId: VaultItemId,
        authorize: () -> Boolean,
    ): NivaraResult<VaultAlbum> = mutate(authorize = authorize) { current, _ ->
        val index = current.indexOfFirst { album -> album.id == albumId }
        if (index < 0) return@mutate Plan.Refused(VaultOrganizationFailure.AlbumNotFound)
        val existing = current[index]
        if (!existing.contains(itemId)) return@mutate Plan.Unchanged(existing)
        val removed = existing.removing(itemId)
        Plan.Applied(albums = current.toMutableList().also { list -> list[index] = removed }, value = removed)
    }

    // ------------------------------------------------------------------ the shared mutation

    /** What a mutation decided to do to the album list. */
    private sealed interface Plan<out T> {

        /** The change is refused, with the typed reason. Nothing is written. */
        data class Refused(val failure: VaultOrganizationFailure) : Plan<Nothing>

        /** The requested state already holds, so the record is left exactly as it is. */
        data class Unchanged<T>(val value: T) : Plan<T>

        /** The record is to be replaced with [albums], and [value] describes what was done. */
        data class Applied<T>(val albums: List<VaultAlbum>, val value: T) : Plan<T>
    }

    /**
     * Runs one change: authorized, planned against the current record, written, verified, pruned.
     *
     * The whole mutation runs under one lock, so two changes cannot interleave their generations, and
     * a read never lands between the two steps of a commit. Reads do not take it: what they read is
     * one complete record, and a commit in progress is invisible until it finishes.
     */
    private suspend fun <T> mutate(
        authorize: () -> Boolean,
        plan: (current: List<VaultAlbum>, now: Long) -> Plan<T>,
    ): NivaraResult<T> = mutationLock.withLock {
        // Asked before anything is read, so a closed session cannot even look at the vault.
        if (!authorize()) return NivaraResult.Failure(VaultOrganizationFailure.NotAuthorized)

        val vault = vaultRepository.inspect()
        if (vault !is VaultState.Ready) {
            return NivaraResult.Failure(VaultOrganizationFailure.VaultNotReady(vault))
        }

        val outcome = keyAccess.withVaultKey { location, key ->
            commit(location = location, key = key, authorize = authorize, plan = plan)
        }
        return when (outcome) {
            is NivaraResult.Success -> outcome
            is NivaraResult.Failure -> NivaraResult.Failure(outcome.error.asOrganizationFailure())
        }
    }

    /** One commit, inside a single borrow of the vault key. */
    private suspend fun <T> commit(
        location: VaultLocation,
        key: EncryptionKey,
        authorize: () -> Boolean,
        plan: (current: List<VaultAlbum>, now: Long) -> Plan<T>,
    ): NivaraResult<T> {
        val read = readRecord(location = location, key = key)
        val albums = when (read) {
            is OrganizationRead.Present -> read.albums
            OrganizationRead.Missing -> emptyList()
            is OrganizationRead.Unreadable ->
                return NivaraResult.Failure(VaultOrganizationFailure.OrganizationUnreadable(read.reason))

            is OrganizationRead.UnsupportedVersion ->
                return NivaraResult.Failure(VaultOrganizationFailure.UnsupportedVersion(read.fileVersion))

            is OrganizationRead.Problem -> return NivaraResult.Failure(read.failure)
        }
        val previousGeneration = (read as? OrganizationRead.Present)?.generation ?: 0L
        val previousSlot = (read as? OrganizationRead.Present)?.slot

        return when (val decided = plan(albums, clock())) {
            is Plan.Refused -> NivaraResult.Failure(decided.failure)
            // Nothing changed, so nothing is written and nothing can fail. A no-op that rewrote the
            // record would be a chance to lose it for no reason.
            is Plan.Unchanged -> NivaraResult.Success(decided.value)

            is Plan.Applied -> {
                // Asked again at the moment the vault would change: a session that ended while the
                // person was choosing a name does not get to write an album.
                if (!authorize()) return NivaraResult.Failure(VaultOrganizationFailure.NotAuthorized)
                when (
                    val written = commitAlbums(
                        location = location,
                        key = key,
                        previousGeneration = previousGeneration,
                        previousSlot = previousSlot,
                        albums = decided.albums,
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
     * read back, authenticated, decoded and compared with the album list that was intended. Only when
     * all of that agrees is the superseded slot pruned — and pruning stays best effort, because the
     * newer generation already wins on the next read whether or not cleanup succeeded.
     */
    private suspend fun commitAlbums(
        location: VaultLocation,
        key: EncryptionKey,
        previousGeneration: Long,
        previousSlot: String?,
        albums: List<VaultAlbum>,
    ): NivaraResult<Unit> {
        val storage = metadataStorageFactory(location)
        val nextGeneration = previousGeneration + VaultOrganizationCodec.FIRST_GENERATION

        val payload = VaultOrganizationCodec.encodePayload(generation = nextGeneration, albums = albums)
            ?: return NivaraResult.Failure(VaultOrganizationFailure.OrganizationFull)

        val envelope = encryptionService.encrypt(
            plaintext = payload,
            key = key,
            context = EncryptionContext.VaultOrganization,
            clearPlaintextAfterUse = true,
        ).valueOrNull() ?: return NivaraResult.Failure(VaultOrganizationFailure.CryptographyFailed)

        val record = VaultOrganizationCodec.encodeRecord(generation = nextGeneration, envelope = envelope)
        val target = targetSlot(previousSlot = previousSlot, generation = nextGeneration)

        if (storage.writeMetadata(target, record) !is NivaraResult.Success) {
            storage.deleteMetadata(target)
            return NivaraResult.Failure(VaultOrganizationFailure.WriteFailed)
        }

        val readBack = storage.readMetadata(target).valueOrNull()
        if (readBack == null || !recordValidates(readBack, nextGeneration, albums, key)) {
            // A record that cannot be read back is not left behind: the next reader would find it and
            // have to decide what a record that does not authenticate means.
            storage.deleteMetadata(target)
            return NivaraResult.Failure(VaultOrganizationFailure.VerificationFailed)
        }

        VaultStructure.ORGANIZATION_SLOT_NAMES
            .filter { name -> name != target }
            .forEach { name -> storage.deleteMetadata(name) }
        return NivaraResult.Success(Unit)
    }

    /** Whether [bytes] is exactly the generation that was meant to be written, and nothing else. */
    private suspend fun recordValidates(
        bytes: ByteArray,
        generation: Long,
        albums: List<VaultAlbum>,
        key: EncryptionKey,
    ): Boolean {
        val header = VaultOrganizationCodec.readHeader(bytes)
        if (header !is VaultOrganizationCodec.HeaderRead.Present) return false
        if (header.generation != generation) return false
        val payloadBytes = encryptionService.decrypt(
            envelope = VaultOrganizationCodec.envelopeOf(bytes),
            key = key,
            context = EncryptionContext.VaultOrganization,
        ).valueOrNull() ?: return false
        val payload = VaultOrganizationCodec.decodePayload(
            bytes = payloadBytes,
            expectedGeneration = generation,
        ) ?: return false
        return payload.albums == albums
    }

    // ------------------------------------------------------------------ reading one record

    /** One look at the album slots, before anything is decided from it. */
    private sealed interface OrganizationRead {

        data class Present(
            val generation: Long,
            val albums: List<VaultAlbum>,
            val slot: String,
        ) : OrganizationRead

        /** No slot holds an album record: this vault has never been organised. */
        data object Missing : OrganizationRead

        data class Unreadable(val reason: VaultOrganizationUnreadable) : OrganizationRead

        data class UnsupportedVersion(val fileVersion: Int) : OrganizationRead

        data class Problem(val failure: VaultOrganizationFailure) : OrganizationRead
    }

    /** What a look at the album slots means to a caller. Every case keeps its own name. */
    private fun OrganizationRead.toState(): VaultOrganizationState = when (this) {
        is OrganizationRead.Present -> VaultOrganizationState.Ready(albums)
        OrganizationRead.Missing -> VaultOrganizationState.Missing
        is OrganizationRead.Unreadable -> VaultOrganizationState.Unreadable(reason)
        is OrganizationRead.UnsupportedVersion -> VaultOrganizationState.UnsupportedVersion(fileVersion)
        // A problem looking at the slots is not a record that is damaged and not an absence of one:
        // the vault's storage could not be listed, and the state says exactly that.
        is OrganizationRead.Problem -> failure.asUnreadableState()
    }

    /**
     * The state that means "the album record could not be read", for a typed organisation failure.
     *
     * Only the failures that describe an unreadable record can arrive here; anything else is reported
     * as unreachable storage rather than as damage, because claiming damage would suggest the record
     * is at fault when the storage was.
     */
    private fun VaultOrganizationFailure.asUnreadableState(): VaultOrganizationState = when (this) {
        is VaultOrganizationFailure.AccessDenied -> VaultOrganizationState.AccessDenied
        is VaultOrganizationFailure.KeyUnavailable ->
            VaultOrganizationState.Unreadable(VaultOrganizationUnreadable.KeyUnavailable)

        is VaultOrganizationFailure.OrganizationUnreadable ->
            VaultOrganizationState.Unreadable(reason)

        is VaultOrganizationFailure.UnsupportedVersion -> VaultOrganizationState.UnsupportedVersion(fileVersion)
        else -> VaultOrganizationState.Unavailable
    }

    /**
     * Reads the newest album record that opens, or reports why none could be read.
     *
     * Every slot is looked at and the highest generation that authenticates wins. A slot holding
     * something that is not a Nivara album record is ignored — a foreign file in the area, the rule
     * the vault's own record follows — while one carrying Nivara's marker that cannot be used is
     * reported rather than passed over, because a reader that skipped it could not tell "no albums
     * yet" from "the albums cannot be read".
     */
    private suspend fun readRecord(location: VaultLocation, key: EncryptionKey): OrganizationRead {
        val storage = metadataStorageFactory(location)

        val entries = storage.metadataEntries()
        val names = entries.valueOrNull() ?: return OrganizationRead.Problem(entries.failureOf())

        var best: OrganizationRead.Present? = null
        var unreadable: VaultOrganizationUnreadable? = null
        var unreadableVersion: Int? = null

        for (slot in VaultStructure.ORGANIZATION_SLOT_NAMES) {
            if (slot !in names) continue
            val bytes = storage.readMetadata(slot)
            val record = bytes.valueOrNull()
            if (record == null) {
                unreadable = VaultOrganizationUnreadable.MetadataDamaged
                continue
            }
            when (val header = VaultOrganizationCodec.readHeader(record)) {
                VaultOrganizationCodec.HeaderRead.NotAnAlbumRecord -> Unit
                is VaultOrganizationCodec.HeaderRead.Unreadable -> {
                    val version = header.fileVersion
                    if (version != null && version != VaultOrganizationCodec.VERSION) {
                        unreadableVersion = version
                    } else {
                        unreadable = VaultOrganizationUnreadable.MetadataDamaged
                    }
                }

                is VaultOrganizationCodec.HeaderRead.Present -> when (
                    val opened = openRecord(record, header.generation, key)
                ) {
                    is OrganizationOpen.Opened ->
                        if (best == null || opened.generation > best.generation) {
                            best = OrganizationRead.Present(
                                generation = opened.generation,
                                albums = opened.albums,
                                slot = slot,
                            )
                        }

                    OrganizationOpen.KeyUnavailable -> unreadable = VaultOrganizationUnreadable.KeyUnavailable
                    OrganizationOpen.Damaged -> unreadable = VaultOrganizationUnreadable.MetadataDamaged
                }
            }
        }

        best?.let { present -> return present }
        unreadableVersion?.let { version -> return OrganizationRead.UnsupportedVersion(version) }
        unreadable?.let { reason -> return OrganizationRead.Unreadable(reason) }
        return OrganizationRead.Missing
    }

    private sealed interface OrganizationOpen {

        data class Opened(val generation: Long, val albums: List<VaultAlbum>) : OrganizationOpen

        data object KeyUnavailable : OrganizationOpen

        data object Damaged : OrganizationOpen
    }

    /**
     * Authenticates one album record and checks its sealed payload against its clear header.
     *
     * The generation is compared in both places on purpose: the clear header is not covered by the
     * envelope, so a record whose payload disagrees with its own header is one somebody edited, and it
     * is refused rather than trusted.
     */
    private suspend fun openRecord(
        bytes: ByteArray,
        headerGeneration: Long,
        key: EncryptionKey,
    ): OrganizationOpen = when (val payloadBytes = encryptionService.decrypt(
        envelope = VaultOrganizationCodec.envelopeOf(bytes),
        key = key,
        context = EncryptionContext.VaultOrganization,
    ).valueOrNull()) {
        null -> OrganizationOpen.Damaged
        else -> when (val payload = VaultOrganizationCodec.decodePayload(payloadBytes, headerGeneration)) {
            null -> OrganizationOpen.Damaged
            else -> OrganizationOpen.Opened(generation = payload.generation, albums = payload.albums)
        }
    }

    /**
     * The slot a new generation is written into: the one that is not the record just read.
     *
     * With no record yet, generations start at one and the first slot is used — the same rule applied
     * to a generation of zero.
     */
    private fun targetSlot(previousSlot: String?, generation: Long): String {
        val names = VaultStructure.ORGANIZATION_SLOT_NAMES
        val other = previousSlot?.let { slot -> names.firstOrNull { name -> name != slot } }
        if (other != null) return other
        val generationIndex = ((generation - VaultOrganizationCodec.FIRST_GENERATION) % names.size).toInt()
        return names[generationIndex]
    }

    /** A fresh album id, checked against the albums the record already names. */
    private fun newAlbumId(albums: List<VaultAlbum>): VaultAlbumId? {
        val taken = albums.map { album -> album.id }.toSet()
        repeat(MAXIMUM_ID_ATTEMPTS) {
            val candidate = VaultAlbumId.create(random)
            if (candidate !in taken) return candidate
        }
        return null
    }

    private companion object {

        /**
         * How many times a fresh identifier is tried before the attempt is abandoned.
         *
         * A collision needs a broken generator, not luck — 128 random bits, checked against a handful
         * of albums — so this is a guard against looping forever on a generator that stopped being
         * random, not a realistic limit.
         */
        const val MAXIMUM_ID_ATTEMPTS = 8
    }
}

/**
 * What "the album record could not be read" means, for whatever opening the vault reported.
 *
 * A read never invents an empty album list and never claims the record is damaged when the real
 * reason is that the vault could not be opened at all: each reason keeps its own state.
 */
private fun Exception?.asUnreadableState(): VaultOrganizationState = when (this) {
    is VaultFailure.AccessDenied -> VaultOrganizationState.AccessDenied
    is VaultFailure.KeyUnavailable ->
        VaultOrganizationState.Unreadable(VaultOrganizationUnreadable.KeyUnavailable)

    is VaultFailure.VaultUnreadable ->
        VaultOrganizationState.Unreadable(
            if (reason == VaultUnreadableReason.KeyUnavailable) {
                VaultOrganizationUnreadable.KeyUnavailable
            } else {
                VaultOrganizationUnreadable.MetadataDamaged
            },
        )

    is VaultFailure.UnsupportedVersion -> VaultOrganizationState.UnsupportedVersion(fileVersion)
    else -> VaultOrganizationState.Unavailable
}

/** The organisation failure behind whatever the key access reported. */
private fun Exception?.asOrganizationFailure(): VaultOrganizationFailure = when (this) {
    null -> VaultOrganizationFailure.MetadataUnavailable
    is VaultOrganizationFailure -> this
    is VaultFailure -> when (this) {
        is VaultFailure.KeyUnavailable -> VaultOrganizationFailure.KeyUnavailable
        is VaultFailure.AccessDenied -> VaultOrganizationFailure.AccessDenied
        is VaultFailure.StorageUnavailable -> VaultOrganizationFailure.MetadataUnavailable
        is VaultFailure.WriteFailed -> VaultOrganizationFailure.WriteFailed
        is VaultFailure.VerificationFailed -> VaultOrganizationFailure.VerificationFailed
        is VaultFailure.CryptographyFailed -> VaultOrganizationFailure.CryptographyFailed
        is VaultFailure.UnsupportedVersion -> VaultOrganizationFailure.UnsupportedVersion(fileVersion)
        is VaultFailure.VaultUnreadable ->
            if (reason == VaultUnreadableReason.KeyUnavailable) {
                VaultOrganizationFailure.KeyUnavailable
            } else {
                VaultOrganizationFailure.VaultNotReady(VaultState.Unreadable(reason))
            }

        else -> VaultOrganizationFailure.MetadataUnavailable
    }

    else -> VaultOrganizationFailure.MetadataUnavailable
}

/** The organisation failure behind a storage result that refused. */
private fun NivaraResult<*>.failureOf(): VaultOrganizationFailure =
    when (val error = (this as? NivaraResult.Failure)?.error) {
        is VaultFailure.AccessDenied -> VaultOrganizationFailure.AccessDenied
        is VaultFailure.StorageUnavailable -> VaultOrganizationFailure.MetadataUnavailable
        is VaultFailure.KeyUnavailable -> VaultOrganizationFailure.KeyUnavailable
        else -> VaultOrganizationFailure.MetadataUnavailable
    }
