package com.nivara.app.domain.vault

/**
 * What the record that lists a vault's contents says.
 *
 * ### Why this is not a list
 *
 * The whole stage rests on one distinction: *the vault holds nothing yet* and *the list of what it
 * holds cannot be read* are different facts, and turning the second into the first would show a
 * person an empty vault while their files are still there. So the states are separate values, a
 * damaged record is never an empty list, and nothing here is repaired, rebuilt or deleted.
 *
 * ### Where an item is, and where it is not
 *
 * An item is in [Ready.items] only when the index record names it *and* its encrypted object is
 * present. An item whose object is missing is reported in [Ready.missingContent] instead of being
 * drawn as a file the vault holds: the index says it should be there and the storage says it is not,
 * which is a fact the owner needs rather than a row that fails when they open it.
 */
sealed interface VaultIndexState {

    /**
     * The vault itself is not ready, so its index was not read.
     *
     * Carries the vault's own state so the screen can say what is wrong with the vault — no folder
     * chosen, a damaged record, an unreachable card — without a second read.
     */
    data class VaultNotReady(val vault: VaultState) : VaultIndexState

    /**
     * The vault holds no index record at all.
     *
     * The ordinary state of a vault that has not imported anything yet, and a valid one to import
     * into. Distinct from a readable index that happens to hold no items — the two mean the same
     * thing to their owner, and different things to a reader, which is why the model keeps them
     * apart.
     */
    data object Missing : VaultIndexState

    /**
     * A readable index.
     *
     * @param items the items the vault holds, in the order they were imported.
     * @param missingContent ids the index names whose encrypted object is not in the content area.
     * @param unindexedObjects how many encrypted objects are present that the index does not name —
     *   the visible result of an import that wrote its object and could not commit. `null` when the
     *   content area could not be listed, which is not the same as "none".
     * @param unfinishedObjects how many objects were left part-way through being written — a crash
     *   or a cancelled import. They are never items, and they are never deleted. `null` as above.
     */
    data class Ready(
        val items: List<VaultItem>,
        val missingContent: Set<VaultItemId> = emptySet(),
        val unindexedObjects: Int? = null,
        val unfinishedObjects: Int? = null,
    ) : VaultIndexState

    /**
     * A record exists and cannot be read.
     *
     * Reported with the reason, because the reasons have different remedies and none of them is
     * "the vault is empty". See [VaultIndexUnreadable].
     */
    data class Unreadable(val reason: VaultIndexUnreadable) : VaultIndexState

    /**
     * A record exists and was written by a newer Nivara.
     *
     * Refusing is the only safe answer: the format may describe items this build cannot show, and
     * treating it as damaged — or replacing it — would lose a vault a later version can still read.
     */
    data class UnsupportedVersion(val fileVersion: Int) : VaultIndexState

    /** The index could not be read because the storage it lives on cannot be reached. */
    data object Unavailable : VaultIndexState

    /** The platform refuses access to the vault root, so the index cannot be read. */
    data object AccessDenied : VaultIndexState

    /** The item with this id, when the index is readable and names it. */
    fun item(id: VaultItemId): VaultItem? =
        (this as? Ready)?.items?.firstOrNull { item -> item.id == id }
}

/**
 * Why an index that exists cannot be read.
 *
 * Each case is a fact about the record or the key that protects it, never a guess:
 *
 * * [MetadataDamaged] — the record is structurally wrong, or it could not be authenticated with
 *   this vault's key, which means it was edited, truncated, or written by something that is not
 *   this vault.
 * * [KeyUnavailable] — the vault key that opens the record cannot be obtained at all (removing the
 *   screen lock destroys the platform key that protects it).
 *
 * There is no third case, because there is no third fact: the index is sealed under the vault key
 * taken from the vault's own authenticated record, so a record that was written for another vault
 * simply cannot be opened with this one — and saying *that* is the same as saying it cannot be read.
 */
enum class VaultIndexUnreadable {

    /** The record is malformed, or it did not authenticate under this vault's key. */
    MetadataDamaged,

    /** The vault key that opens the record is not available. */
    KeyUnavailable,
}
