package com.nivara.app.data.apphide

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.nivaraRunCatching
import com.nivara.app.data.credential.AtomicFiles
import com.nivara.app.domain.apphide.HiddenApplication
import com.nivara.app.domain.apphide.HiddenApplicationFailure
import com.nivara.app.domain.apphide.HiddenApplicationRepository
import com.nivara.app.domain.apphide.HiddenApplicationsRead
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Stores the hidden application set in the application's private directory.
 *
 * The store takes a [File] rather than a `Context`, which keeps it free of Android types and lets
 * the JVM suite exercise the real format and the real atomic write instead of a stand-in — the same
 * arrangement the credential record and the protected set use, and for the same reason.
 *
 * ### Why anything is stored at all
 *
 * The set has to survive a restart because the user's choice has to survive a restart: Nivara's
 * launcher, whenever it draws a list, must leave out the same applications the user asked it to,
 * and forgetting one would put an application back in front of whoever is holding the phone. It is
 * the smallest thing that could be stored: package names in the application's private directory,
 * excluded from backup along with the rest of the application's data.
 *
 * ### What is deliberately not here
 *
 * No session state, no unlock flag, no per-application lock state and no timestamps. Whether Nivara
 * is unlocked is the session manager's answer and is never written down; what is written down is
 * only which applications the user asked Nivara to keep out of sight. Nothing in the file is secret
 * and nothing in it is authentication material, so there is no key to lose and nothing to decrypt —
 * and the file is not a hiding place: it is readable by anyone with access to the application's
 * private storage, and this feature never claims otherwise.
 *
 * ### How it is written
 *
 * Every change is a whole-file replace through `AtomicFiles`, which writes a temporary file, syncs
 * it and renames it into place: a reader sees the previous set or the new one, never a half-written
 * list. Changes are serialised through a mutex, so two edits cannot interleave into a set that
 * contains neither view — the mechanism is the one the protected set uses, deliberately, because it
 * is the smallest thing that works.
 *
 * ### What each outcome means
 *
 * * **A missing file is an empty set.** Nothing has been hidden yet on this device, and that is a
 *   read that succeeded.
 * * **A file that exists but cannot be decoded is [HiddenApplicationsRead.Unreadable].** It is not
 *   an empty set: reading it as "nothing is hidden" would put back in front of the user every
 *   application they hid, which is exactly the outcome the user asked to avoid and the one an
 *   attacker with write access to the file would want. Nothing is repaired and nothing is
 *   overwritten while it is in that state.
 * * **Storage that cannot be reached at all is [HiddenApplicationsRead.Unavailable].** A different
 *   fact from damage, reported separately, with the same consequence: nothing may be claimed and
 *   nothing may be written.
 * * **A failed change is [HiddenApplicationFailure.HiddenApplicationsUnwritable]** and leaves the
 *   previous set authoritative rather than replacing it with a partial one.
 */
internal class FileHiddenApplicationRepository(
    private val file: File,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : HiddenApplicationRepository {

    /** Serialises changes, so a read-modify-write pair cannot lose the other's edit. */
    private val updateLock = Mutex()

    override suspend fun hiddenApplications(): HiddenApplicationsRead = withContext(dispatcher) {
        updateLock.withLock { read() }
    }

    override suspend fun hide(application: HiddenApplication): NivaraResult<Unit> =
        update { current -> if (application in current) current else current + application }

    override suspend fun unhide(application: HiddenApplication): NivaraResult<Unit> =
        update { current -> current - application }

    /**
     * Applies [change] to the stored set and writes the result when it actually differs.
     *
     * The read happens inside the same lock as the write, so an interleaved edit cannot be lost. A
     * set that cannot be read aborts the change: the file is left exactly as it was, and the failure
     * says whether it was damage or unreachable storage.
     */
    private suspend fun update(
        change: (Set<HiddenApplication>) -> Set<HiddenApplication>,
    ): NivaraResult<Unit> = withContext(dispatcher) {
        updateLock.withLock {
            when (val read = read()) {
                is HiddenApplicationsRead.Available -> nivaraRunCatching {
                    val updated = change(read.hidden)
                    if (updated != read.hidden) {
                        AtomicFiles.write(file, HiddenApplicationCodec.encode(updated))
                    }
                }.asHiddenFailure(HiddenApplicationFailure.HiddenApplicationsUnwritable)

                HiddenApplicationsRead.Unreadable ->
                    NivaraResult.Failure(HiddenApplicationFailure.HiddenApplicationsUnreadable)

                HiddenApplicationsRead.Unavailable ->
                    NivaraResult.Failure(HiddenApplicationFailure.HiddenApplicationsUnavailable)
            }
        }
    }

    /**
     * The stored set as one of the three outcomes.
     *
     * The two failures are separated here rather than downstream: a decode failure is damage to
     * data that was read, and an I/O failure is data that could not be read at all. Both are
     * reported as themselves and neither becomes an empty set.
     */
    private fun read(): HiddenApplicationsRead {
        val bytes = try {
            AtomicFiles.readOrNull(file)
        } catch (error: Exception) {
            // A directory that cannot be opened, a permission problem or a file that vanished
            // mid-read. None of these is "nothing is hidden".
            return HiddenApplicationsRead.Unavailable
        } ?: return HiddenApplicationsRead.Available(emptySet())

        val decoded = HiddenApplicationCodec.decode(bytes)
            ?: return HiddenApplicationsRead.Unreadable

        return HiddenApplicationsRead.Available(decoded)
    }
}

/**
 * Maps storage trouble onto the typed failures callers expect.
 *
 * A missing directory, a permission problem or a failed rename all reach the caller as a
 * [HiddenApplicationFailure], never as a platform exception with a path or a stack trace in it. A
 * failure that is already typed is passed through unchanged, so a refusal that happened before the
 * write is not misreported as a write failure.
 */
private fun <T> NivaraResult<T>.asHiddenFailure(reason: HiddenApplicationFailure): NivaraResult<T> =
    if (this is NivaraResult.Failure) {
        if (error is HiddenApplicationFailure) this else NivaraResult.Failure(reason)
    } else {
        this
    }
