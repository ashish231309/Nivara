package com.nivara.app.ui.vault

/**
 * What the search box currently means.
 *
 * Four states, and the differences between them are the point:
 *
 * * [NotAsked] — the box is empty. The screen shows the list, not a result set.
 * * [Matches] — a query was asked and the readable index holds files that answer it.
 * * [NoMatches] — a query was asked and nothing in the vault answers it. This is a statement about
 *   the vault, and it is only ever made when the vault's list was actually read.
 * * [CannotSearch] — the list cannot be read, so the query was never asked. The screen shows the
 *   index's own state — damaged, a newer format, unreachable storage — because "cannot search" and
 *   "found nothing" are different facts and only one of them is about the files.
 */
enum class VaultSearchUiState {

    NotAsked,
    Matches,
    NoMatches,
    CannotSearch,
    ;

    /** Whether a query is currently filtering a list. */
    val isActive: Boolean get() = this != NotAsked
}

/**
 * How many files answered the query, out of how many the vault holds.
 *
 * Only ever produced for a readable list, so the numbers are counts of metadata that was actually
 * read rather than of what happened to be drawn.
 */
data class VaultSearchSummary(val matches: Int, val total: Int)
