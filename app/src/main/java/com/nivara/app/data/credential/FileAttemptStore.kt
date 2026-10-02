package com.nivara.app.data.credential

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.nivaraRunCatching
import com.nivara.app.domain.credential.AttemptStore
import com.nivara.app.domain.credential.ThrottleState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stores the attempt counters next to the credential record.
 *
 * Like the record store, this is plain file I/O in the application's private directory, written
 * atomically, so the JVM tests can exercise exactly the code that runs on a device.
 *
 * A missing or unreadable file is reported as [ThrottleState.Clear] rather than as a failure,
 * and writes that fail are ignored by the tracker: the counters exist to make guessing tedious,
 * and a damaged counter must never become a lock the user cannot open.
 */
internal class FileAttemptStore(private val file: File) : AttemptStore {

    override suspend fun load(): NivaraResult<ThrottleState> = withContext(Dispatchers.IO) {
        nivaraRunCatching {
            val bytes = AtomicFiles.readOrNull(file) ?: return@nivaraRunCatching ThrottleState.Clear
            ThrottleStateCodec.decode(bytes) ?: ThrottleState.Clear
        }.asStorageFailure()
    }

    override suspend fun save(state: ThrottleState): NivaraResult<Unit> = withContext(Dispatchers.IO) {
        nivaraRunCatching {
            AtomicFiles.write(file, ThrottleStateCodec.encode(state))
        }.asStorageFailure()
    }
}
