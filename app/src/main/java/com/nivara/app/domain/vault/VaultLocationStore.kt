package com.nivara.app.domain.vault

import com.nivara.app.core.common.NivaraResult

/**
 * Keeps the reference of the root Nivara uses.
 *
 * One selection, not a list: Nivara's vault lives in one place, and offering several would mean
 * deciding which is authoritative. Re-selecting a root replaces the record; nothing is merged and
 * nothing is copied.
 *
 * Implementations are responsible for whatever durable access the platform needs to reopen that
 * exact root later — on Android, taking the persisted permission on the user's document-tree
 * selection — and must fail rather than store a reference they cannot come back to.
 */
interface VaultLocationStore {

    /** The stored location, or one of the two "cannot answer" cases. Never throws. */
    suspend fun storedLocation(): VaultLocationRead

    /**
     * Adopts [location] as the vault root.
     *
     * Fails with [VaultFailure.InvalidLocation] when the reference cannot be used as a vault root and
     * with [VaultFailure.AccessDenied] when the platform refuses durable access: in either case
     * nothing is stored, so the previous selection survives a failed attempt.
     */
    suspend fun storeLocation(
        location: VaultLocation,
    ): NivaraResult<Unit>
}
