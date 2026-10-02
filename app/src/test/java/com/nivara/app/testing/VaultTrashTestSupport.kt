package com.nivara.app.testing

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.domain.vault.VaultItemId
import com.nivara.app.domain.vault.VaultTrashEntry
import com.nivara.app.domain.vault.VaultTrashFailure
import com.nivara.app.domain.vault.VaultTrashRepository
import com.nivara.app.domain.vault.VaultTrashState

/**
 * Builders the trash suites share.
 *
 * An entry is derived from a seed rather than generated, so an assertion about which item is trashed,
 * when, and in what order can name the value it expects. Nothing here touches a device, a vault or a
 * cipher: an entry is a plain value, which is what the domain contract makes it.
 */
internal fun testTrashEntry(
    seed: Int,
    trashedAtEpochMillis: Long = 1_700_000_000_000L + seed,
): VaultTrashEntry = VaultTrashEntry(
    itemId = testItemId(seed),
    trashedAtEpochMillis = trashedAtEpochMillis,
)

/**
 * The trash record, in memory.
 *
 * A screen test needs the same three answers the repository gives — a record that reads, one that
 * cannot be read, and a change that is refused — without a vault on storage. What is recorded here is
 * what a test asserts on: which changes were asked for, in what order, and whether any of them was
 * allowed to write.
 */
internal class FakeVaultTrashRepository(
    var state: VaultTrashState = VaultTrashState.Missing,
) : VaultTrashRepository {

    /** Every mutation the screen asked for, as the calls it made. */
    val calls: MutableList<String> = mutableListOf()

    var readCalls: Int = 0
        private set

    /** What the next successful mutation should leave behind, when a test wants the record to change. */
    var onSuccess: (() -> Unit)? = null

    /** Changed between calls so a test can close the session in the middle of a mutation. */
    var mutationResultFactory: ((String) -> NivaraResult<*>) = { NivaraResult.Success(Unit) }

    /** The authorizations the repository was handed, so a test can prove the gate was asked. */
    val authorizations: MutableList<Boolean> = mutableListOf()

    fun report(newState: VaultTrashState) {
        state = newState
    }

    fun refuseWith(failure: VaultTrashFailure) {
        mutationResultFactory = { NivaraResult.Failure(failure) }
    }

    fun succeed() {
        mutationResultFactory = { NivaraResult.Success(Unit) }
    }

    override suspend fun read(): VaultTrashState {
        readCalls += 1
        return state
    }

    override suspend fun trash(
        itemId: VaultItemId,
        authorize: () -> Boolean,
    ): NivaraResult<VaultTrashEntry> = mutation("trash:${itemId.value}", authorize)

    override suspend fun restore(
        itemId: VaultItemId,
        authorize: () -> Boolean,
    ): NivaraResult<Unit> = mutation("restore:${itemId.value}", authorize)

    /**
     * Records one mutation, asks the gate, and reports what the test decided.
     *
     * The value a successful result carries is not part of the contract the screen relies on — it
     * reads the record back instead — so the same result serves both mutations.
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
