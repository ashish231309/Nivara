package com.nivara.app.data.credential

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.nivaraRunCatching
import com.nivara.app.domain.credential.CredentialFailure
import com.nivara.app.domain.credential.CredentialStore
import com.nivara.app.domain.credential.StoredCredential
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stores the credential record in the application's private directory.
 *
 * The store takes a [File] rather than a `Context`, which keeps it free of Android types and
 * lets it run, unchanged, in local JVM tests — the persistence tests exercise the real file
 * format and the real atomic write, not a stand-in.
 *
 * Android's private storage is the protection here: the directory is owned by the application's
 * user id and is not readable by other applications. Encryption on top of that would add a key
 * to protect a record that holds no secret — the verifier is one-way, the salt and the
 * parameters are public by design — while making the credential unusable whenever the platform
 * key store is unavailable. That trade is recorded in the design document rather than hidden.
 */
internal class FileCredentialRecordStore(private val file: File) : CredentialStore {

    override suspend fun load(): NivaraResult<StoredCredential?> = withContext(Dispatchers.IO) {
        nivaraRunCatching {
            val bytes = AtomicFiles.readOrNull(file) ?: return@nivaraRunCatching null
            CredentialRecordCodec.decode(bytes) ?: throw CredentialFailure.InvalidConfiguration
        }.asStorageFailure()
    }

    override suspend fun save(credential: StoredCredential): NivaraResult<Unit> = withContext(Dispatchers.IO) {
        nivaraRunCatching {
            AtomicFiles.write(file, CredentialRecordCodec.encode(credential))
        }.asStorageFailure()
    }
}
