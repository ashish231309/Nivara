package com.nivara.app.data.applock

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.nivaraRunCatching
import com.nivara.app.data.credential.AtomicFiles
import com.nivara.app.domain.applock.AppLockFailure
import com.nivara.app.domain.applock.ProtectedApplication
import com.nivara.app.domain.applock.ProtectedApplicationRepository
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Stores the protected application set in the application's private directory.
 *
 * The store takes a [File] rather than a `Context`, which keeps it free of Android types and lets
 * the JVM suite exercise the real format and the real atomic write instead of a stand-in — the same
 * arrangement the credential record uses, and for the same reason.
 *
 * ### Why anything is stored at all
 *
 * The set is configuration, not authorization. It has to survive a restart because the user's
 * choice has to survive a restart: a protection decision made after the process was recreated must
 * use the same list the user configured, and inventing one — or losing one — would be worse than
 * keeping a few package names on disk. It is the smallest thing that could be stored: package names
 * in the application's private directory, excluded from backup along with the rest of the
 * application's data.
 *
 * ### What is deliberately not here
 *
 * No session state, no unlock flag, no per-application lock state and no timestamps. Whether Nivara
 * is unlocked is the session manager's answer and is never written down; what is written down is
 * only which applications the user asked to protect. Nothing in the file is secret, and nothing in
 * it is authentication material, so there is no key to lose and nothing to decrypt.
 *
 * ### How it is written
 *
 * Every change is a whole-file replace through `AtomicFiles`, which writes a temporary file, syncs
 * it and renames it into place: a reader sees the previous set or the new one, never a half-written
 * list. Changes are serialised through a mutex, so two edits cannot interleave into a set that
 * contains neither view.
 *
 * ### What a damaged file means
 *
 * A missing file is an empty set — a device where nothing has been protected yet. A file that
 * exists but cannot be read exactly is a **failure**, not an empty set: reading it as "nothing is
 * protected" would silently drop every protection the user configured, which is precisely the
 * outcome an attacker with access to the file would want. So the failure is reported, the monitor
 * stops deciding, and the settings screen can offer a repair. A failed change likewise leaves the
 * stored set untouched instead of replacing it with a partial one.
 */
internal class FileProtectedApplicationRepository(
    private val file: File,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ProtectedApplicationRepository {

    /** Serialises changes, so a read-modify-write pair cannot lose the other's edit. */
    private val updateLock = Mutex()

    override suspend fun protectedApplications(): NivaraResult<Set<ProtectedApplication>> =
        withContext(dispatcher) {
            updateLock.withLock {
                nivaraRunCatching { load() }
                    .asAppLockFailure(AppLockFailure.ProtectedApplicationsUnreadable)
            }
        }

    override suspend fun protect(application: ProtectedApplication): NivaraResult<Unit> =
        update(AppLockFailure.ProtectedApplicationsUnwritable) { current ->
            if (application in current) current else current + application
        }

    override suspend fun unprotect(application: ProtectedApplication): NivaraResult<Unit> =
        update(AppLockFailure.ProtectedApplicationsUnwritable) { current -> current - application }

    /**
     * Applies [change] to the stored set and writes the result when it actually differs.
     *
     * The read happens inside the same lock as the write, so an interleaved edit cannot be lost.
     * A set that cannot be read aborts the change: the file is left exactly as it was, and the
     * failure reported to the caller says which of the two problems occurred.
     */
    private suspend fun update(
        reason: AppLockFailure,
        change: (Set<ProtectedApplication>) -> Set<ProtectedApplication>,
    ): NivaraResult<Unit> = withContext(dispatcher) {
        updateLock.withLock {
            nivaraRunCatching {
                val current = load()
                val updated = change(current)
                if (updated != current) {
                    AtomicFiles.write(file, ProtectedApplicationCodec.encode(updated))
                }
            }.asAppLockFailure(reason)
        }
    }

    /** The stored set. Throws when the file exists but is not a valid set. */
    private fun load(): Set<ProtectedApplication> {
        val bytes = AtomicFiles.readOrNull(file) ?: return emptySet()
        return ProtectedApplicationCodec.decode(bytes)
            ?: throw AppLockFailure.ProtectedApplicationsUnreadable
    }
}

/**
 * Maps storage trouble onto the typed failures callers expect.
 *
 * A missing directory, a permission problem or a corrupt file all reach the caller as an
 * [AppLockFailure], never as a platform exception with a path or a stack trace in it. A failure
 * that is already typed is passed through unchanged, so a change that could not read the set
 * reports "unreadable" rather than blaming the write.
 */
private fun <T> NivaraResult<T>.asAppLockFailure(reason: AppLockFailure): NivaraResult<T> =
    if (this is NivaraResult.Failure) {
        if (error is AppLockFailure) this else NivaraResult.Failure(reason)
    } else {
        this
    }
