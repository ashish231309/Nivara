package com.nivara.app.data.vault

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.domain.security.EncryptionKey
import com.nivara.app.domain.vault.VaultLocation

/**
 * The one way the content path borrows the vault's key.
 *
 * The vault key is created once, wrapped by the device key store and sealed into the vault's own
 * authenticated record — that hierarchy is Stage 13's and it is not extended here. This port is the
 * smallest contract that lets the import borrow that key for a moment: the key is handed to the block
 * and cleared when the block returns, so no caller ever receives key material it could keep, and no
 * key is copied into state, an index record or a log.
 *
 * It is deliberately *not* a domain contract. Key material is a data-layer concern — the domain
 * describes what a vault holds, not what opens it — and a port that carried an [EncryptionKey] through
 * the domain would put key material one import away from a screen.
 *
 * The [VaultLocation] travels with the key because both are resolved from the same record in the same
 * look: the caller must encrypt into the vault whose key it was given, never into a folder that
 * changed in between.
 */
internal interface VaultKeyAccess {

    /**
     * Runs [block] with the vault's key and the location it belongs to.
     *
     * Fails when there is no vault, when its record cannot be opened, or when the key cannot be
     * unwrapped — each reported as the vault failure it is. The key is cleared once [block] returns,
     * successfully or not.
     */
    suspend fun <T> withVaultKey(
        block: suspend (location: VaultLocation, key: EncryptionKey) -> NivaraResult<T>,
    ): NivaraResult<T>
}
