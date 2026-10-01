package com.nivara.app.data.vault

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.valueOrNull
import com.nivara.app.data.security.ContentDigester
import com.nivara.app.domain.security.CryptographicFailure
import com.nivara.app.domain.security.EncryptionContext
import com.nivara.app.domain.security.EncryptionKey
import com.nivara.app.domain.security.EncryptionService
import com.nivara.app.domain.security.SecureRandomGenerator
import com.nivara.app.domain.vault.VaultContentDigest
import com.nivara.app.domain.vault.VaultContentSource
import com.nivara.app.domain.vault.VaultFailure
import com.nivara.app.domain.vault.VaultImportFailure
import com.nivara.app.domain.vault.VaultImportProgress
import com.nivara.app.domain.vault.VaultIndexRepository
import com.nivara.app.domain.vault.VaultIndexState
import com.nivara.app.domain.vault.VaultIndexUnreadable
import com.nivara.app.domain.vault.VaultItem
import com.nivara.app.domain.vault.VaultItemId
import com.nivara.app.domain.vault.VaultItemNames
import com.nivara.app.domain.vault.VaultLocation
import com.nivara.app.domain.vault.VaultRepository
import com.nivara.app.domain.vault.VaultSourceReference
import com.nivara.app.domain.vault.VaultState
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The vault's catalogue, and how a file gets into it.
 *
 * ### The order that makes an import true
 *
 * A file is in the vault when — and only when — its encrypted object is on storage, complete, read
 * back, opened, checked against what the source gave, and named in the index. Every step before that
 * can fail without leaving a trace:
 *
 * ```
 *  authorized → index readable → allocate id → open the source → validate its name
 *      → stream-encrypt into a pending object → flush/sync → rename to the item's name
 *      → read it back: digest the stored bytes and decrypt every record to the expected size
 *      → authorized again → seal a new index generation
 *      → write it into the slot that is not authoritative → read it back and open it → prune
 * ```
 *
 * An interruption anywhere leaves either no object at all — a failed, refused or cancelled write
 * removes its own pending document — or a complete object that no index names. The second is an
 * orphan: it is reported as unindexed and left exactly where it is, because deleting content Nivara
 * cannot match to a list is how a vault loses files. Nothing but the index can make a file appear,
 * and the index is replaced only once its replacement has been read back and opened.
 *
 * ### Why the index keeps two slots
 *
 * For the same reason the vault's own record does: storage that cannot replace a document atomically
 * must never be asked to. A new generation is written into the slot that is *not* the current one, so
 * the authoritative index is untouched while its replacement is written; the generation in the clear
 * header decides which of the two a reader takes; the older one is deleted only after the newer one
 * has been read back.
 *
 * ### What is deliberately absent
 *
 * No deletion of content, no deduplication, no rebuild of an unreadable index and no scanning of the
 * content area to guess what a vault holds. An index that cannot be read is reported as exactly that
 * — never as an empty vault, and never as a reason to write over it.
 */
internal class NivaraVaultIndexRepository(
    private val vaultRepository: VaultRepository,
    private val keyAccess: VaultKeyAccess,
    private val metadataStorageFactory: (VaultLocation) -> VaultRootStorage,
    private val contentStorageFactory: (VaultLocation) -> VaultContentStorage,
    private val sourceOpener: VaultSourceOpener,
    private val encryptionService: EncryptionService,
    private val random: SecureRandomGenerator,
    private val clock: () -> Long = System::currentTimeMillis,
    private val importLock: Mutex = Mutex(),
) : VaultIndexRepository {

    /**
     * The whole import runs under one lock, so two imports cannot interleave their index
     * generations, and a read never lands between the two steps of a commit.
     *
     * Reads do not take it: what they read is one complete record, and a commit in progress is
     * invisible to them until it finishes.
     */
    override suspend fun importFile(
        source: VaultSourceReference,
        authorize: () -> Boolean,
        onProgress: (VaultImportProgress) -> Unit,
    ): NivaraResult<VaultItem> = importLock.withLock {
        // Asked before anything is written, so a closed session cannot even start a change.
        if (!authorize()) return NivaraResult.Failure(VaultImportFailure.NotAuthorized)

        val outcome = keyAccess.withVaultKey { location, key ->
            importInto(
                location = location,
                key = key,
                reference = source,
                authorize = authorize,
                onProgress = onProgress,
            )
        }
        return when (outcome) {
            is NivaraResult.Success -> outcome
            is NivaraResult.Failure -> NivaraResult.Failure(outcome.error.asImportFailure())
        }
    }

    /** Reads the vault, then the index that belongs to it. Neither is repaired or created. */
    override suspend fun read(): VaultIndexState {
        val vault = vaultRepository.inspect()
        if (vault !is VaultState.Ready) return VaultIndexState.VaultNotReady(vault)

        val read = keyAccess.withVaultKey { location, key ->
            NivaraResult.Success(readIndex(location = location, key = key))
        }
        return when (read) {
            is NivaraResult.Success -> read.value
            is NivaraResult.Failure -> when (read.importFailureOf()) {
                VaultImportFailure.AccessDenied -> VaultIndexState.AccessDenied
                VaultImportFailure.KeyUnavailable ->
                    VaultIndexState.Unreadable(VaultIndexUnreadable.KeyUnavailable)
                else -> VaultIndexState.Unavailable
            }
        }
    }

    // ------------------------------------------------------------------ reading

    /** One look at the index slots, before anything is decided from it. */
    private sealed interface IndexRead {

        /** A record was read, authenticated and parsed. */
        data class Present(
            val generation: Long,
            val items: List<VaultItem>,
            val slot: String,
        ) : IndexRead

        /** No slot holds a Nivara index record: the vault has never imported anything. */
        data object Missing : IndexRead

        data class Unreadable(val reason: VaultIndexUnreadable) : IndexRead

        data class UnsupportedVersion(val fileVersion: Int) : IndexRead

        data class Problem(val failure: VaultImportFailure) : IndexRead
    }

    /** Turns an index read into the state the application reasons about. */
    private suspend fun readIndex(location: VaultLocation, key: EncryptionKey): VaultIndexState {
        val objects = contentStorageFactory(location).listObjects().valueOrNull()
        return when (val read = readIndexRecord(location = location, key = key)) {
            IndexRead.Missing -> VaultIndexState.Missing

            is IndexRead.Present -> VaultIndexState.Ready(
                items = read.items,
                missingContent = missingObjects(read.items, objects),
                unindexedObjects = unindexedObjects(read.items, objects),
                unfinishedObjects = objects?.count { entry -> entry is ContentObjectRef.Pending },
            )

            is IndexRead.Unreadable -> VaultIndexState.Unreadable(read.reason)
            is IndexRead.UnsupportedVersion -> VaultIndexState.UnsupportedVersion(read.fileVersion)
            is IndexRead.Problem -> when (read.failure) {
                VaultImportFailure.AccessDenied -> VaultIndexState.AccessDenied
                else -> VaultIndexState.Unavailable
            }
        }
    }

    /** The ids the index names whose encrypted object is not in the content area. */
    private fun missingObjects(
        items: List<VaultItem>,
        objects: List<ContentObjectRef>?,
    ): Set<VaultItemId> {
        if (objects == null) return emptySet()
        val present = objects.filterIsInstance<ContentObjectRef.Object>()
            .map { entry -> entry.itemId }
            .toSet()
        return items.map { item -> item.id }.filterNot { id -> id in present }.toSet()
    }

    /** How many objects are there that the index does not name, or `null` when it could not look. */
    private fun unindexedObjects(
        items: List<VaultItem>,
        objects: List<ContentObjectRef>?,
    ): Int? {
        if (objects == null) return null
        val indexed = items.map { item -> item.id }.toSet()
        return objects.filterIsInstance<ContentObjectRef.Object>()
            .count { entry -> entry.itemId !in indexed }
    }

    /**
     * Reads the newest index record that opens, or reports why none could be read.
     *
     * Every slot is looked at and the highest generation that authenticates wins. A slot holding
     * something which is not a Nivara index record is not an index and is ignored — the rule the
     * vault's own metadata follows — while one carrying Nivara's marker that cannot be used is
     * reported rather than passed over: a reader that skipped it could not tell "the vault holds
     * nothing yet" from "the list of what it holds cannot be read".
     */
    private suspend fun readIndexRecord(location: VaultLocation, key: EncryptionKey): IndexRead {
        val storage = metadataStorageFactory(location)

        val entries = storage.metadataEntries()
        val names = entries.valueOrNull() ?: return IndexRead.Problem(entries.importFailureOf())

        var best: IndexRead.Present? = null
        var unreadable: VaultIndexUnreadable? = null
        var unreadableVersion: Int? = null

        for (slot in VaultStructure.INDEX_SLOT_NAMES) {
            if (slot !in names) continue
            val bytes = storage.readMetadata(slot)
            val record = bytes.valueOrNull()
            if (record == null) {
                unreadable = VaultIndexUnreadable.MetadataDamaged
                continue
            }
            when (val header = VaultIndexCodec.readHeader(record)) {
                VaultIndexCodec.HeaderRead.NotAVaultIndex -> Unit // a foreign file in the area
                is VaultIndexCodec.HeaderRead.Unreadable -> {
                    val version = header.fileVersion
                    if (version != null && version != VaultIndexCodec.VERSION) {
                        unreadableVersion = version
                    } else {
                        unreadable = VaultIndexUnreadable.MetadataDamaged
                    }
                }

                is VaultIndexCodec.HeaderRead.Present -> when (
                    val opened = openIndex(record, header.generation, key)
                ) {
                    is IndexOpen.Opened ->
                        if (best == null || opened.generation > best.generation) {
                            best = IndexRead.Present(
                                generation = opened.generation,
                                items = opened.items,
                                slot = slot,
                            )
                        }

                    IndexOpen.KeyUnavailable -> unreadable = VaultIndexUnreadable.KeyUnavailable
                    IndexOpen.Damaged -> unreadable = VaultIndexUnreadable.MetadataDamaged
                }
            }
        }

        best?.let { present -> return present }
        unreadableVersion?.let { version -> return IndexRead.UnsupportedVersion(version) }
        unreadable?.let { reason -> return IndexRead.Unreadable(reason) }
        return IndexRead.Missing
    }

    private sealed interface IndexOpen {

        data class Opened(val generation: Long, val items: List<VaultItem>) : IndexOpen

        data object KeyUnavailable : IndexOpen

        data object Damaged : IndexOpen
    }

    /**
     * Authenticates one index record and checks its sealed payload against its clear header.
     *
     * The generation is compared in both places on purpose: the clear header is not covered by the
     * envelope, so an index whose payload disagrees with its own header is one somebody edited, and
     * it is refused rather than trusted.
     */
    private suspend fun openIndex(
        bytes: ByteArray,
        headerGeneration: Long,
        key: EncryptionKey,
    ): IndexOpen {
        val payloadBytes = encryptionService.decrypt(
            envelope = VaultIndexCodec.envelopeOf(bytes),
            key = key,
            context = EncryptionContext.VaultIndex,
        ).valueOrNull() ?: return IndexOpen.Damaged
        val payload = VaultIndexCodec.decodePayload(
            bytes = payloadBytes,
            expectedGeneration = headerGeneration,
        ) ?: return IndexOpen.Damaged
        return IndexOpen.Opened(generation = payload.generation, items = payload.items)
    }

    // ------------------------------------------------------------------ importing

    /** Opens the source and closes it whatever happens, then hands over to the pipeline. */
    private suspend fun importInto(
        location: VaultLocation,
        key: EncryptionKey,
        reference: VaultSourceReference,
        authorize: () -> Boolean,
        onProgress: (VaultImportProgress) -> Unit,
    ): NivaraResult<VaultItem> {
        val existing = when (val read = readIndexRecord(location = location, key = key)) {
            IndexRead.Missing -> IndexRead.Present(
                generation = 0L,
                items = emptyList(),
                slot = VaultStructure.INDEX_SLOT_NAMES.first(),
            )

            is IndexRead.Present -> read
            is IndexRead.Unreadable ->
                return NivaraResult.Failure(VaultImportFailure.IndexUnreadable(read.reason))

            is IndexRead.UnsupportedVersion ->
                return NivaraResult.Failure(VaultImportFailure.UnsupportedIndexVersion(read.fileVersion))

            is IndexRead.Problem -> return NivaraResult.Failure(read.failure)
        }

        val opened = sourceOpener.open(reference)
        val source = opened.valueOrNull() ?: return NivaraResult.Failure(opened.importFailureOf())
        // The bound wraps the source, not the loop: every read the encryption service makes goes
        // through it, so a provider that under-reports its size cannot make Nivara read past what it
        // is willing to hold.
        val limited = LimitedSource(source, VaultImportFailure.MAXIMUM_SOURCE_BYTES)
        return try {
            importSource(
                location = location,
                key = key,
                source = source,
                limited = limited,
                existing = existing,
                authorize = authorize,
                onProgress = onProgress,
            )
        } finally {
            // Closed once, whatever happened. A source is read for exactly one import.
            source.close()
        }
    }

    /** Everything from validating the source to the committed index. */
    private suspend fun importSource(
        location: VaultLocation,
        key: EncryptionKey,
        source: VaultContentSource,
        limited: VaultContentSource,
        existing: IndexRead.Present,
        authorize: () -> Boolean,
        onProgress: (VaultImportProgress) -> Unit,
    ): NivaraResult<VaultItem> {
        val name = VaultItemNames.sanitize(source.displayName)
            ?: return NivaraResult.Failure(VaultImportFailure.InvalidSourceName)
        val mimeType = source.mimeType?.takeIf { declared -> VaultItemNames.isWellFormedMimeType(declared) }
        val declared = source.declaredSizeBytes
        if (declared != null && declared > VaultImportFailure.MAXIMUM_SOURCE_BYTES) {
            return NivaraResult.Failure(VaultImportFailure.SourceTooLarge)
        }
        if (existing.items.size >= VaultIndexCodec.MAXIMUM_ITEM_COUNT) {
            return NivaraResult.Failure(VaultImportFailure.IndexFull)
        }

        val itemId = newItemId(existing.items)
            ?: return NivaraResult.Failure(VaultImportFailure.DuplicateItemId)
        val contentStorage = contentStorageFactory(location)

        var plaintextBytes = 0L
        val write = contentStorage.writeObject(itemId) { output ->
            // The sink is wrapped before anything is written to it: a destination that refuses bytes
            // is a refused write, and it must not be reported as a cryptographic failure or as a
            // source that could not be read.
            val digesting = ContentDigester.DigestingOutputStream(WriteFailureReportingSink(output))
            val encrypted = encryptionService.encryptStream(
                plaintext = limited,
                ciphertext = digesting,
                key = key,
                context = EncryptionContext.VaultContent,
                identity = itemId.toBytes(),
                onProgress = { processed ->
                    onProgress(VaultImportProgress(bytesProcessed = processed, totalBytes = declared))
                },
            )
            plaintextBytes = encrypted.valueOrNull() ?: throw encrypted.encryptionException()
        }
        if (write !is NivaraResult.Success) {
            return NivaraResult.Failure(write.importFailureOf())
        }

        val verified = verifyObject(contentStorage = contentStorage, itemId = itemId, key = key)
        if (verified !is ObjectVerification.Verified) {
            deleteOurObject(contentStorage, itemId)
            return NivaraResult.Failure((verified as ObjectVerification.Failed).failure)
        }
        if (verified.plaintextBytes != plaintextBytes || declared?.let { it != verified.plaintextBytes } == true) {
            // What came back out is not what went in — or the size the provider declared was wrong,
            // in which case the index would record a claim Nivara cannot stand behind.
            deleteOurObject(contentStorage, itemId)
            return NivaraResult.Failure(VaultImportFailure.VerificationFailed)
        }

        // Asked again at the moment a durable change is about to be made: a session that ended during
        // a long import stops it, and the object this import wrote is removed rather than left as the
        // half-finished result of a change nobody authorized.
        if (!authorize()) {
            deleteOurObject(contentStorage, itemId)
            return NivaraResult.Failure(VaultImportFailure.NotAuthorized)
        }

        val item = VaultItem(
            id = itemId,
            name = name,
            mimeType = mimeType,
            sizeBytes = verified.plaintextBytes,
            importedAtEpochMillis = clock(),
            contentFormatVersion = VaultIndexCodec.VERSION,
            contentDigest = VaultContentDigest.fromBytes(verified.digest)
                ?: return NivaraResult.Failure(VaultImportFailure.VerificationFailed),
        )

        val commit = commitIndex(
            location = location,
            key = key,
            previous = existing,
            items = existing.items + item,
        )
        if (commit !is NivaraResult.Success) {
            // The object stays: it is complete and unindexed, and deleting it would destroy content
            // its owner may still recover. It is reported as unindexed, never as an item.
            return NivaraResult.Failure(commit.importFailureOf())
        }
        return NivaraResult.Success(item)
    }

    /** What reading an object back found. */
    private sealed interface ObjectVerification {

        data class Verified(val digest: ByteArray, val plaintextBytes: Long) : ObjectVerification

        data class Failed(val failure: VaultImportFailure) : ObjectVerification
    }

    /**
     * Reads the object back, authenticates every record, and reports the digest of the stored bytes.
     *
     * This is what makes "durably completed and validated" mean something: a provider that dropped,
     * shortened or altered the write cannot pass it, because the object is decrypted end to end with
     * the item's identity bound into every record, and the plaintext that comes out is counted.
     * Memory stays bounded — the plaintext is discarded as it is produced — and the cost is one extra
     * sequential read of the object that was just written.
     */
    private suspend fun verifyObject(
        contentStorage: VaultContentStorage,
        itemId: VaultItemId,
        key: EncryptionKey,
    ): ObjectVerification {
        var digest: ByteArray? = null
        var decryptionFailure: CryptographicFailure? = null
        val counting = ContentDigester.CountingOutputStream()
        val read = contentStorage.readObject(VaultContentNames.objectName(itemId)) { input ->
            val digesting = ContentDigester.DigestingInputStream(input)
            val decrypted = encryptionService.decryptStream(
                ciphertext = digesting,
                plaintext = counting,
                key = key,
                context = EncryptionContext.VaultContent,
                identity = itemId.toBytes(),
            )
            if (decrypted is NivaraResult.Failure) {
                decryptionFailure = decrypted.error as? CryptographicFailure
                    ?: CryptographicFailure.MalformedEnvelope
                return@readObject
            }
            digest = digesting.digest()
        }
        if (read !is NivaraResult.Success) {
            // The object this import just wrote cannot be read back at all — the provider accepted
            // the write and does not have it. That is what "not verified" means, and it is reported
            // as such rather than as a storage that is merely out of reach: the import must not
            // describe a destination that lost the bytes as a destination that could not be asked.
            return ObjectVerification.Failed(VaultImportFailure.VerificationFailed)
        }
        decryptionFailure?.let { failure ->
            return ObjectVerification.Failed(
                if (failure is CryptographicFailure.InvalidKey) {
                    VaultImportFailure.KeyUnavailable
                } else {
                    VaultImportFailure.VerificationFailed
                },
            )
        }
        val stored = digest ?: return ObjectVerification.Failed(VaultImportFailure.VerificationFailed)
        return ObjectVerification.Verified(digest = stored, plaintextBytes = counting.bytesWritten)
    }

    /**
     * Seals a new index generation and puts it where a reader will find it.
     *
     * The write goes into the slot that is not the authoritative one, is read back, is opened again
     * and is compared — items and all — before the superseded slot is pruned. A failure at any point
     * leaves the previous index exactly as it was.
     */
    private suspend fun commitIndex(
        location: VaultLocation,
        key: EncryptionKey,
        previous: IndexRead.Present,
        items: List<VaultItem>,
    ): NivaraResult<Unit> {
        val storage = metadataStorageFactory(location)
        val nextGeneration = previous.generation + VaultIndexCodec.FIRST_GENERATION

        val payload = VaultIndexCodec.encodePayload(
            generation = nextGeneration,
            vaultGeneration = nextGeneration,
            items = items,
        ) ?: return NivaraResult.Failure(VaultImportFailure.IndexFull)

        val envelope = encryptionService.encrypt(
            plaintext = payload,
            key = key,
            context = EncryptionContext.VaultIndex,
            clearPlaintextAfterUse = true,
        ).valueOrNull() ?: return NivaraResult.Failure(VaultImportFailure.CryptographyFailed)

        val record = VaultIndexCodec.encodeRecord(generation = nextGeneration, envelope = envelope)
        val target = targetSlot(previous = previous, generation = nextGeneration)

        if (storage.writeMetadata(target, record) !is NivaraResult.Success) {
            storage.deleteMetadata(target)
            return NivaraResult.Failure(VaultImportFailure.WriteFailed)
        }

        val readBack = storage.readMetadata(target).valueOrNull()
        if (readBack == null || !indexValidates(readBack, nextGeneration, items, key)) {
            storage.deleteMetadata(target)
            return NivaraResult.Failure(VaultImportFailure.VerificationFailed)
        }

        // Only now is the superseded record pruned. If that fails, the newer generation still wins on
        // the next read, so cleanup is best effort rather than part of the commit.
        VaultStructure.INDEX_SLOT_NAMES
            .filter { name -> name != target }
            .forEach { name -> storage.deleteMetadata(name) }
        return NivaraResult.Success(Unit)
    }

    /** Whether [bytes] is the index this attempt wrote: opened, authenticated, with the same items. */
    private suspend fun indexValidates(
        bytes: ByteArray,
        generation: Long,
        items: List<VaultItem>,
        key: EncryptionKey,
    ): Boolean {
        val header = VaultIndexCodec.readHeader(bytes)
        if (header !is VaultIndexCodec.HeaderRead.Present) return false
        if (header.generation != generation) return false
        val opened = openIndex(bytes, generation, key)
        return opened is IndexOpen.Opened && opened.items == items
    }

    /**
     * The slot a new generation is written into.
     *
     * The slot that is *not* the one the current record was read from, so the index a reader would
     * pick is never the one being written. With no index yet — the first import, or a vault whose
     * first write failed — generations start at one and the first slot is used, which is the same
     * rule applied to a generation of zero.
     */
    private fun targetSlot(previous: IndexRead.Present, generation: Long): String {
        val names = VaultStructure.INDEX_SLOT_NAMES
        val current = if (previous.generation > 0L) previous.slot else null
        val other = current?.let { slot -> names.firstOrNull { name -> name != slot } }
        if (other != null) return other
        val generationIndex = ((generation - VaultIndexCodec.FIRST_GENERATION) % names.size).toInt()
        return names[generationIndex]
    }

    /** A fresh item id, checked against the ids the index already names. */
    private fun newItemId(items: List<VaultItem>): VaultItemId? {
        val taken = items.map { item -> item.id }.toSet()
        repeat(MAXIMUM_ID_ATTEMPTS) {
            val candidate = VaultItemId.create(random)
            if (candidate !in taken) return candidate
        }
        return null
    }

    /** Removes an object this import itself just wrote, and never indexed. */
    private suspend fun deleteOurObject(contentStorage: VaultContentStorage, itemId: VaultItemId) {
        contentStorage.deleteObject(VaultContentNames.objectName(itemId))
    }

    private companion object {
        /**
         * How many identifiers are tried before giving up.
         *
         * One is already overwhelming — 128 random bits against a list of items — and a second is
         * there so that a generator returning something already used fails the import instead of
         * silently writing two files to one object.
         */
        const val MAXIMUM_ID_ATTEMPTS = 3
    }
}

/**
 * The source, refusing to give more than the bound.
 *
 * A wrapper rather than a check somewhere in the loop: every read the encryption service makes goes
 * through it, so the bound cannot be forgotten by a caller and a provider that misreports its size
 * stops the import instead of filling the vault. The declared size never decides how much is read —
 * it is a claim, and this is the fact.
 */
private class LimitedSource(
    private val delegate: VaultContentSource,
    private val maximumBytes: Long,
) : VaultContentSource {

    private var total = 0L

    override val displayName: String get() = delegate.displayName
    override val mimeType: String? get() = delegate.mimeType
    override val declaredSizeBytes: Long? get() = delegate.declaredSizeBytes

    override suspend fun read(buffer: ByteArray): Int {
        val read = delegate.read(buffer)
        if (read > 0) {
            total += read
            if (total > maximumBytes) throw VaultSourceException(VaultImportFailure.SourceTooLarge)
        }
        return read
    }

    override suspend fun close() = delegate.close()
}

/**
 * The ciphertext sink, reporting a refused write as the failure it is.
 *
 * Writing to the platform's own stream is the one place where a failure means "the destination would
 * not take this byte" rather than anything cryptographic. Saying so keeps the import's failure
 * accurate — a full or removed destination is not a failed authentication — and it keeps a raw
 * platform exception from travelling upwards as an unnamed error.
 */
private class WriteFailureReportingSink(private val delegate: OutputStream) : OutputStream() {

    override fun write(byte: Int) {
        try {
            delegate.write(byte)
        } catch (refused: IOException) {
            throw VaultImportException(VaultImportFailure.WriteFailed)
        }
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        try {
            delegate.write(buffer, offset, length)
        } catch (refused: IOException) {
            throw VaultImportException(VaultImportFailure.WriteFailed)
        }
    }

    override fun flush() {
        try {
            delegate.flush()
        } catch (refused: IOException) {
            throw VaultImportException(VaultImportFailure.WriteFailed)
        }
    }
}
