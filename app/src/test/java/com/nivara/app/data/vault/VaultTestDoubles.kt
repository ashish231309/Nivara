package com.nivara.app.data.vault

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.domain.security.CryptographicFailure
import com.nivara.app.domain.security.DeviceKeyConfig
import com.nivara.app.domain.security.DeviceKeyStore
import com.nivara.app.domain.security.EncryptionKey
import com.nivara.app.domain.vault.VaultFailure
import com.nivara.app.domain.vault.VaultLocation
import com.nivara.app.domain.vault.VaultLocationRead
import com.nivara.app.domain.vault.VaultLocationStore
import com.nivara.app.testing.randomKey
import kotlinx.coroutines.CompletableDeferred

/**
 * Test doubles for the vault's storage seam.
 *
 * The whole point of the storage port is that the interesting failures are the ones a device cannot
 * be asked to produce on demand: a write that is refused, a write that returns happily and stores
 * nothing, a read-back that disagrees with what was written, a deletion that fails. The fake keeps the
 * bytes of a directory in a map and lets a test inject each of those, so the repository's promises —
 * nothing reported before it was read back, nothing committed halfway, no answer that collapses
 * damage into absence — are checked rather than assumed.
 *
 * It deliberately behaves like the platform in the places that matter: reading a document that is not
 * there is a failure rather than an empty byte array, a provider that cannot be reached is a failure
 * rather than an empty listing, and deleting a document that is not there succeeds.
 */
internal class FakeVaultRootStorage : VaultRootStorage {

    /** The children of the metadata area, by name. */
    val documents: MutableMap<String, ByteArray> = linkedMapOf()

    /** Names that exist and cannot be read at all. */
    val unreadableDocuments: MutableSet<String> = linkedSetOf()

    var metadataDirectory: Boolean = false
    var contentDirectory: Boolean = false

    var ensureFailure: VaultFailure? = null
    var entriesFailure: VaultFailure? = null
    var readFailure: VaultFailure? = null
    var writeFailure: VaultFailure? = null
    var deleteFailure: VaultFailure? = null

    /** Bytes written before a refused write: an interruption part way through. */
    var writeFailureAfterBytes: Int? = null

    /** A write that reports success and stores nothing, like a provider that dropped it. */
    var swallowWrites: Boolean = false

    /** A write that reports success and stores something else. */
    var corruptWrites: Boolean = false

    /** Held open to keep a write in flight while a test inspects the root. */
    var writeGate: CompletableDeferred<Unit>? = null

    var writeCalls: Int = 0
    var deleteCalls: Int = 0
    var readCalls: Int = 0

    override suspend fun ensureMetadataArea(): NivaraResult<Unit> = ensureDirectory(metadata = true)

    override suspend fun ensureContentArea(): NivaraResult<Unit> = ensureDirectory(metadata = false)

    override suspend fun metadataAreaExists(): NivaraResult<Boolean> = exists(metadata = true)

    override suspend fun contentAreaExists(): NivaraResult<Boolean> = exists(metadata = false)

    override suspend fun metadataEntries(): NivaraResult<List<String>> {
        entriesFailure?.let { failure -> return NivaraResult.Failure(failure) }
        if (!metadataDirectory) return NivaraResult.Success(emptyList())
        return NivaraResult.Success(documents.keys.toList())
    }

    override suspend fun readMetadata(entryName: String): NivaraResult<ByteArray> {
        readCalls += 1
        readFailure?.let { failure -> return NivaraResult.Failure(failure) }
        if (entryName in unreadableDocuments) {
            return NivaraResult.Failure(VaultFailure.StorageUnavailable)
        }
        val bytes = documents[entryName] ?: return NivaraResult.Failure(VaultFailure.StorageUnavailable)
        return NivaraResult.Success(bytes.copyOf())
    }

    override suspend fun writeMetadata(entryName: String, bytes: ByteArray): NivaraResult<Unit> {
        writeCalls += 1
        writeGate?.await()
        writeFailure?.let { failure ->
            writeFailureAfterBytes?.let { count ->
                documents[entryName] = bytes.copyOf(count.coerceAtMost(bytes.size))
            }
            return NivaraResult.Failure(failure)
        }
        when {
            swallowWrites -> Unit
            corruptWrites -> documents[entryName] = bytes.copyOf().also { stored ->
                stored[stored.size - 1] = (stored[stored.size - 1].toInt() xor 0x01).toByte()
            }
            else -> documents[entryName] = bytes.copyOf()
        }
        return NivaraResult.Success(Unit)
    }

    override suspend fun deleteMetadata(entryName: String): NivaraResult<Unit> {
        deleteCalls += 1
        deleteFailure?.let { failure -> return NivaraResult.Failure(failure) }
        documents.remove(entryName)
        unreadableDocuments.remove(entryName)
        return NivaraResult.Success(Unit)
    }

    /** Every byte currently stored, for byte-for-byte comparisons across an operation. */
    fun snapshot(): Map<String, List<Int>> = documents.mapValues { (_, bytes) ->
        bytes.map { byte -> byte.toInt() }
    }

    private fun ensureDirectory(metadata: Boolean): NivaraResult<Unit> {
        ensureFailure?.let { failure -> return NivaraResult.Failure(failure) }
        if (metadata) metadataDirectory = true else contentDirectory = true
        return NivaraResult.Success(Unit)
    }

    private fun exists(metadata: Boolean): NivaraResult<Boolean> {
        entriesFailure?.let { failure -> return NivaraResult.Failure(failure) }
        return NivaraResult.Success(if (metadata) metadataDirectory else contentDirectory)
    }
}

/** The location store, in memory, with the three answers it can give. */
internal class FakeVaultLocationStore(
    var stored: VaultLocationRead = VaultLocationRead.None,
    var storeResult: NivaraResult<Unit> = NivaraResult.Success(Unit),
) : VaultLocationStore {

    val adopted: MutableList<VaultLocation> = mutableListOf()

    override suspend fun storedLocation(): VaultLocationRead = stored

    override suspend fun storeLocation(location: VaultLocation): NivaraResult<Unit> {
        adopted += location
        if (storeResult is NivaraResult.Success) {
            stored = VaultLocationRead.Present(location)
        }
        return storeResult
    }
}

/**
 * A device key store that keeps one key in memory, with the one thing the platform does to it.
 *
 * [destroyKey] models the case that happens on real devices when the user removes their screen lock:
 * the platform deletes keys it can no longer protect. After that the alias is empty — [retrieveKey]
 * fails, and [getOrCreateKey] would create *new* material — which is exactly the shape that makes
 * "the vault is intact and its key is gone" a different answer from "the vault is damaged". [refuseCreation] models
 * a platform that will not create a key at all.
 */
internal class FakeDeviceKeyStore(
    private var key: EncryptionKey?,
    private val generate: () -> EncryptionKey = { randomKey("device-key") },
    var refuseCreation: Boolean = false,
) : DeviceKeyStore {

    var getOrCreateCalls: Int = 0
    var retrieveCalls: Int = 0

    /** Deletes the key, as removing the screen lock does. */
    fun destroyKey() {
        key = null
    }

    /** Puts different material under the alias, as a fresh enrollment can. */
    fun replaceKey(replacement: EncryptionKey) {
        key = replacement
    }

    /** Whether the platform currently has a key under the alias. */
    fun hasKey(): Boolean = key != null

    override suspend fun exists(alias: String): NivaraResult<Boolean> = NivaraResult.Success(hasKey())

    override suspend fun getOrCreateKey(
        alias: String,
        config: DeviceKeyConfig,
    ): NivaraResult<EncryptionKey> {
        getOrCreateCalls += 1
        if (refuseCreation) {
            return NivaraResult.Failure(CryptographicFailure.KeyGenerationFailed)
        }
        key?.let { existing -> return NivaraResult.Success(existing) }
        val created = generate()
        key = created
        return NivaraResult.Success(created)
    }

    override suspend fun retrieveKey(alias: String): NivaraResult<EncryptionKey> {
        retrieveCalls += 1
        val existing = key
            ?: return NivaraResult.Failure(CryptographicFailure.KeyUnavailable)
        return NivaraResult.Success(existing)
    }

    override suspend fun isKeyUsable(alias: String): NivaraResult<Boolean> =
        NivaraResult.Success(hasKey())

    override suspend fun deleteKey(alias: String): NivaraResult<Unit> {
        key = null
        return NivaraResult.Success(Unit)
    }
}
