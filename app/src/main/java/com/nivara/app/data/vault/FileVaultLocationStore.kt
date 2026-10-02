package com.nivara.app.data.vault

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.nivaraRunCatching
import com.nivara.app.data.credential.AtomicFiles
import com.nivara.app.domain.vault.VaultFailure
import com.nivara.app.domain.vault.VaultLocation
import com.nivara.app.domain.vault.VaultLocationRead
import com.nivara.app.domain.vault.VaultLocationStore
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Remembers which folder the user pointed Nivara at.
 *
 * The record is one platform reference in one small file, written through the project's atomic-write
 * discipline; [VaultLocationCodec] owns its shape and explains why it holds nothing else.
 *
 * ### The durable grant
 *
 * Storing a reference is only useful if Nivara can come back to it. Android grants access to a
 * document tree for the current task; keeping it across restarts requires taking the *persisted*
 * permission on the selection, which is what [storeLocation] does before it writes anything. The
 * grant is taken for reading and writing — a vault that could be opened but not written would be a
 * trap — and for the exact tree the user picked, because that is the only access Nivara ever wants.
 *
 * A selection that is not a document tree, or for which the platform refuses durable access, is
 * rejected *without* storing anything: the previous selection then survives a failed attempt, which
 * is the behaviour that keeps a mistyped pick from forgetting where a real vault is.
 *
 * ### Damage
 *
 * A record that cannot be read is reported as [VaultLocationRead.Unreadable] and left exactly as it
 * was found. Nivara does not delete it and does not fall back to another location: the damaged bytes
 * are the only remaining hint of where the vault is, and turning "I cannot read where the vault is"
 * into "no vault is configured" is the failure this stage exists to prevent.
 */
internal class FileVaultLocationStore(
    private val context: Context,
    private val file: File,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : VaultLocationStore {

    override suspend fun storedLocation(): VaultLocationRead = withContext(dispatcher) {
        val bytes = when (val read = nivaraRunCatching { AtomicFiles.readOrNull(file) }) {
            // The record is there and cannot be read: that is not "no vault is configured", and it is
            // not a reason to forget the selection.
            is NivaraResult.Failure -> return@withContext VaultLocationRead.Unreadable
            is NivaraResult.Success -> read.value ?: return@withContext VaultLocationRead.None
        }
        VaultLocationCodec.decode(bytes)?.let { location -> VaultLocationRead.Present(location) }
            ?: VaultLocationRead.Unreadable
    }

    override suspend fun storeLocation(location: VaultLocation): NivaraResult<Unit> =
        withContext(dispatcher) {
            val uri = Uri.parse(location.reference)
            if (!DocumentsContract.isTreeUri(uri)) {
                return@withContext NivaraResult.Failure(VaultFailure.InvalidLocation)
            }
            // The durable grant comes first: a reference Nivara cannot come back to must not be
            // stored, and the failure must leave the previous selection in place.
            val grant = nivaraRunCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }.mapLocationFailure()
            if (grant !is NivaraResult.Success) return@withContext grant

            nivaraRunCatching { AtomicFiles.write(file, VaultLocationCodec.encode(location)) }
                .mapLocationFailure()
        }

    private fun <T> NivaraResult<T>.mapLocationFailure(): NivaraResult<T> = when (this) {
        is NivaraResult.Success -> this
        is NivaraResult.Failure -> NivaraResult.Failure(
            when (error) {
                is VaultFailure -> error
                is SecurityException -> VaultFailure.AccessDenied
                else -> VaultFailure.WriteFailed
            },
        )
    }
}
