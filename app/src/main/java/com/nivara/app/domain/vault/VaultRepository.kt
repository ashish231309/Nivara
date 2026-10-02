package com.nivara.app.domain.vault

import com.nivara.app.core.common.NivaraResult

/**
 * The vault operations that are part of this stage.
 *
 * Two operations and no more: what is at the root, and create a vault at it. Opening a vault for
 * content, re-wrapping its key for a credential, recovery and file storage belong to the stages that
 * build on this one, and are deliberately absent here rather than present and unused.
 */
interface VaultRepository {

    /**
     * Reports what is at the currently selected root.
     *
     * Reads only: nothing is created, nothing is repaired, nothing is deleted, and no key material is
     * generated. A root that holds no vault stays exactly as it was found, which is what makes this
     * safe to call while the user is still deciding.
     *
     * The call needs no session. It answers whether a vault exists and can be opened as a vault,
     * which is not personal data about its contents; deciding what the user may *do* with an open
     * vault is the screens' business and uses the existing session gate.
     */
    suspend fun inspect(): VaultState

    /**
     * Creates a vault at the currently selected root.
     *
     * Succeeds only when the root holds no vault at all, or — with [replaceUnreadable] — holds
     * records that cannot be opened. Every other state is refused with the matching
     * [VaultFailure], including [VaultFailure.VaultAlreadyExists] for a valid vault and
     * [VaultFailure.UnsupportedVersion] for one written by a newer Nivara.
     *
     * @param replaceUnreadable the user has explicitly said that unreadable records at this location
     *   may be replaced by a new vault. This is the only destructive path in the stage, the UI offers
     *   it only in the unreadable state and with copy that says data will be lost, and it is refused
     *   for a vault that merely belongs to a newer format. Defaults to `false`, so no caller can
     *   replace anything by accident.
     */
    suspend fun initialize(replaceUnreadable: Boolean = false)
        : NivaraResult<Unit>
}
