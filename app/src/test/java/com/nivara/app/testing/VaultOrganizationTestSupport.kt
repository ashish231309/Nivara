package com.nivara.app.testing

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.domain.vault.VaultAlbum
import com.nivara.app.domain.vault.VaultAlbumId
import com.nivara.app.domain.vault.VaultContentDigest
import com.nivara.app.domain.vault.VaultItem
import com.nivara.app.domain.vault.VaultItemId
import com.nivara.app.domain.vault.VaultOrganizationFailure
import com.nivara.app.domain.vault.VaultOrganizationRepository
import com.nivara.app.domain.vault.VaultOrganizationState

/**
 * Builders the Stage 16 suites share.
 *
 * Identifiers are derived from a seed rather than generated, so an assertion about order, staleness or
 * tie-breaking can name the value it expects. Nothing here touches a device, a vault or a cipher: an
 * item and an album are plain values, which is what the domain contracts make them.
 */
internal fun testItemId(seed: Int): VaultItemId =
    VaultItemId(String.format("%032x", seed.toLong() and 0xFFFFFFFFL))

internal fun testAlbumId(seed: Int): VaultAlbumId =
    VaultAlbumId(String.format("%032x", (seed.toLong() and 0xFFFFFFFFL) or 0x100000000L))

internal fun testDigest(seed: Int): VaultContentDigest =
    VaultContentDigest(String.format("%064x", seed.toLong() and 0xFFFFFFFFL))

internal fun testItem(
    seed: Int,
    name: String = "file-$seed.bin",
    mimeType: String? = "application/octet-stream",
    sizeBytes: Long = 1_024L,
    importedAtEpochMillis: Long = 1_700_000_000_000L + seed,
): VaultItem = VaultItem(
    id = testItemId(seed),
    name = name,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    importedAtEpochMillis = importedAtEpochMillis,
    contentFormatVersion = 1,
    contentDigest = testDigest(seed),
)

internal fun testAlbum(
    seed: Int,
    name: String = "Album $seed",
    createdAtEpochMillis: Long = 1_700_000_000_000L + seed,
    itemIds: List<VaultItemId> = emptyList(),
): VaultAlbum = VaultAlbum(
    id = testAlbumId(seed),
    name = name,
    createdAtEpochMillis = createdAtEpochMillis,
    itemIds = itemIds,
)

/**
 * The album record, in memory.
 *
 * A screen test needs the same three answers the repository gives — a record that reads, one that
 * cannot be read, and a change that is refused — without a vault on storage. What is recorded here is
 * what a test asserts on: which mutations were asked for, in what order, and whether any of them was
 * allowed to write.
 */
internal class FakeVaultOrganizationRepository(
    var state: VaultOrganizationState = VaultOrganizationState.Missing,
    private var mutationResult: NivaraResult<*> = NivaraResult.Success(Unit),
) : VaultOrganizationRepository {

    /** Every mutation the screen asked for, as the calls it made. */
    val calls: MutableList<String> = mutableListOf()

    var readCalls: Int = 0
        private set

    /** What the next successful mutation should leave behind, when a test wants the record to change. */
    var onSuccess: (() -> Unit)? = null

    /** Changed between calls so a test can close the session in the middle of a mutation. */
    var mutationResultFactory: ((String) -> NivaraResult<*>) = { mutationResult }

    /** The authorizations the repository was handed, so a test can prove the gate was asked. */
    val authorizations: MutableList<Boolean> = mutableListOf()

    fun report(newState: VaultOrganizationState) {
        state = newState
    }

    fun refuseWith(failure: VaultOrganizationFailure) {
        mutationResult = NivaraResult.Failure(failure)
    }

    fun succeed() {
        mutationResult = NivaraResult.Success(Unit)
    }

    override suspend fun read(): VaultOrganizationState {
        readCalls += 1
        return state
    }

    override suspend fun createAlbum(name: String, authorize: () -> Boolean): NivaraResult<VaultAlbum> =
        mutation("create:$name", authorize)

    override suspend fun renameAlbum(
        albumId: VaultAlbumId,
        name: String,
        authorize: () -> Boolean,
    ): NivaraResult<VaultAlbum> = mutation("rename:${albumId.value}:$name", authorize)

    override suspend fun deleteAlbum(albumId: VaultAlbumId, authorize: () -> Boolean): NivaraResult<Unit> =
        mutation("delete:${albumId.value}", authorize)

    override suspend fun addItem(
        albumId: VaultAlbumId,
        itemId: VaultItemId,
        authorize: () -> Boolean,
    ): NivaraResult<VaultAlbum> = mutation("add:${albumId.value}:${itemId.value}", authorize)

    override suspend fun removeItem(
        albumId: VaultAlbumId,
        itemId: VaultItemId,
        authorize: () -> Boolean,
    ): NivaraResult<VaultAlbum> = mutation("remove:${albumId.value}:${itemId.value}", authorize)

    /**
     * Records one mutation, asks the gate, and reports what the test decided.
     *
     * The value a successful result carries is not part of the contract the screen relies on — it
     * reads the record back instead — so the same result serves every mutation.
     */
    private fun <T> mutation(call: String, authorize: () -> Boolean): NivaraResult<T> {
        calls += call
        authorizations += authorize()
        val result = mutationResultFactory(call)
        if (result is NivaraResult.Success<*>) onSuccess?.invoke()
        @Suppress("UNCHECKED_CAST")
        return result as NivaraResult<T>
    }
}
