package com.nivara.app.data.biometric

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.nivaraRunCatching
import com.nivara.app.data.credential.AtomicFiles
import com.nivara.app.domain.security.BiometricFailure
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stores the non-secret biometric token record.
 *
 * The record lives next to the credential record in the application's private directory and is
 * written atomically, with the same temporary-file-and-rename approach: a reader sees the old
 * record in full or the new one in full, never a half-written file. At 71 bytes it is small enough
 * that writing it whole is trivially safe.
 *
 * A missing, unreadable or damaged file is reported as "nothing stored" rather than as a failure.
 * That is deliberate: the worst consequence is that biometric unlock appears not enabled, which
 * the user can fix by enabling it again — the alternative, treating a damaged file as a hard
 * error, would leave a feature permanently stuck in a state the user cannot clear.
 */
internal class BiometricTokenStore(private val file: File) {

    /** The stored token, or `null` when nothing usable is stored. */
    suspend fun load(): BiometricToken? = withContext(Dispatchers.IO) {
        val bytes = try {
            AtomicFiles.readOrNull(file)
        } catch (unreadable: IOException) {
            null
        } ?: return@withContext null

        BiometricTokenCodec.decode(bytes)
    }

    /** Replaces the stored record. */
    suspend fun save(token: BiometricToken): NivaraResult<Unit> = withContext(Dispatchers.IO) {
        nivaraRunCatching { AtomicFiles.write(file, BiometricTokenCodec.encode(token)) }.asStorageFailure()
    }

    /** Removes the record. Succeeds when nothing is stored. */
    suspend fun delete(): NivaraResult<Unit> = withContext(Dispatchers.IO) {
        nivaraRunCatching {
            if (file.exists() && !file.delete()) {
                throw IOException("could not remove the biometric record")
            }
        }.asStorageFailure()
    }

    private fun <T> NivaraResult<T>.asStorageFailure(): NivaraResult<T> =
        if (this is NivaraResult.Failure) {
            NivaraResult.Failure(BiometricFailure.StorageUnavailable)
        } else {
            this
        }
}
