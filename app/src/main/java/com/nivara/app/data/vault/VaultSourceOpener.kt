package com.nivara.app.data.vault

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.domain.vault.VaultContentSource
import com.nivara.app.domain.vault.VaultSourceReference

/**
 * Turns the reference the user picked into something the import can read.
 *
 * This port exists so the pipeline never learns what a platform document is: it is handed a
 * [VaultContentSource] — a name, a declared type, a declared size and bytes — and the adapter below
 * is the only place that resolves a platform reference, holds a grant, or knows that a source is a
 * `Uri` at all.
 *
 * A source is opened for one import and closed when that import ends, and nothing about it is
 * stored: the imported item must keep working after the source has been moved, renamed or deleted,
 * so nothing may depend on it afterwards.
 */
internal interface VaultSourceOpener {

    /** Opens [reference], or reports why the document cannot be read. */
    suspend fun open(reference: VaultSourceReference): NivaraResult<VaultContentSource>
}
