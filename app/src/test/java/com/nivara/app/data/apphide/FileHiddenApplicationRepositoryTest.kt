package com.nivara.app.data.apphide

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.isFailure
import com.nivara.app.core.common.isSuccess
import com.nivara.app.domain.apphide.HiddenApplication
import com.nivara.app.domain.apphide.HiddenApplicationFailure
import com.nivara.app.domain.apphide.HiddenApplicationsRead
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Local JVM tests for the stored hidden application set.
 *
 * The production store is exercised against real files: the same format, the same atomic write and
 * the same failure mapping that run on a device. Only the directory differs — a temporary folder
 * instead of the application's private storage — which is exactly why the store takes a `File`
 * rather than a `Context`.
 *
 * The security-relevant cases are the ones about damage. A file that cannot be read must be reported
 * as such, must never be read as "nothing is hidden", and must not be quietly replaced by a change
 * that cannot see what it would overwrite — because on a device that would put back in front of the
 * user every application they had hidden.
 */
class FileHiddenApplicationRepositoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val file: File get() = File(temporaryFolder.root, "hidden-applications.nvh")

    private val camera = HiddenApplication("com.example.camera")
    private val notes = HiddenApplication("com.example.notes")

    private fun repository(): FileHiddenApplicationRepository = FileHiddenApplicationRepository(file)

    @Test
    fun `nothing is hidden before anything is written`() = runTest {
        val read = repository().hiddenApplications()

        assertEquals(HiddenApplicationsRead.Available(emptySet()), read)
        assertFalse("reading must not create the file", file.exists())
    }

    @Test
    fun `a hidden application survives a new instance`() = runTest {
        repository().hide(camera)

        val reloaded = repository().hiddenApplications()

        assertEquals(setOf(camera), (reloaded as HiddenApplicationsRead.Available).hidden)
    }

    @Test
    fun `hiding twice stores it once`() = runTest {
        val repository = repository()
        repository.hide(camera)
        val afterFirst = file.readBytes()

        val second = repository.hide(camera)

        assertTrue(second.isSuccess)
        assertEquals(
            "a repeated hide is a no-op, not a duplicate",
            setOf(camera),
            (repository.hiddenApplications() as HiddenApplicationsRead.Available).hidden,
        )
        assertTrue("an unchanged set must not be rewritten", afterFirst.contentEquals(file.readBytes()))
    }

    @Test
    fun `applications accumulate and can be unhidden`() = runTest {
        val repository = repository()
        repository.hide(camera)
        repository.hide(notes)

        assertEquals(
            setOf(camera, notes),
            (repository.hiddenApplications() as HiddenApplicationsRead.Available).hidden,
        )

        val removed = repository.unhide(camera)

        assertTrue(removed.isSuccess)
        assertEquals(
            setOf(notes),
            (repository.hiddenApplications() as HiddenApplicationsRead.Available).hidden,
        )
    }

    @Test
    fun `unhiding something that was never hidden changes nothing`() = runTest {
        val repository = repository()

        val result = repository.unhide(camera)

        assertTrue(result.isSuccess)
        assertFalse("there was nothing to write", file.exists())
    }

    @Test
    fun `unhiding twice is idempotent`() = runTest {
        val repository = repository()
        repository.hide(camera)

        assertTrue(repository.unhide(camera).isSuccess)
        assertTrue(repository.unhide(camera).isSuccess)
        assertEquals(
            HiddenApplicationsRead.Available(emptySet()),
            repository.hiddenApplications(),
        )
    }

    @Test
    fun `a damaged file is reported as unreadable instead of being read as an empty set`() = runTest {
        val damaged = "not a hidden application set".toByteArray()
        file.writeBytes(damaged)

        val read = repository().hiddenApplications()

        assertEquals(
            "a damaged set must never become 'nothing is hidden'",
            HiddenApplicationsRead.Unreadable,
            read,
        )
        assertTrue("reading must not rewrite the file", damaged.contentEquals(file.readBytes()))
    }

    @Test
    fun `a change is refused while the stored set cannot be read, and the file is untouched`() = runTest {
        val damaged = "not a hidden application set".toByteArray()
        file.writeBytes(damaged)
        val repository = repository()

        val result = repository.hide(camera)

        assertTrue(result.isFailure)
        assertEquals(
            HiddenApplicationFailure.HiddenApplicationsUnreadable,
            (result as NivaraResult.Failure).error,
        )
        assertTrue(
            "a change must not replace configuration it cannot read",
            damaged.contentEquals(file.readBytes()),
        )
    }

    @Test
    fun `storage that cannot be reached is unavailable, not empty`() = runTest {
        // A directory where the file belongs cannot be read as a set of applications.
        assertTrue(file.mkdir())

        val read = repository().hiddenApplications()

        assertEquals(HiddenApplicationsRead.Unavailable, read)
    }

    @Test
    fun `a change against unreachable storage is refused as unavailable`() = runTest {
        assertTrue(file.mkdir())

        val result = repository().hide(camera)

        assertTrue(result.isFailure)
        assertEquals(
            HiddenApplicationFailure.HiddenApplicationsUnavailable,
            (result as NivaraResult.Failure).error,
        )
    }

    @Test
    fun `a write that cannot be completed leaves the stored set authoritative`() = runTest {
        val repository = repository()
        repository.hide(camera)
        val before = file.readBytes()

        // A directory where the atomic write puts its temporary file makes the replacement fail,
        // which is the closest a test can come to an interrupted write without controlling the file
        // system.
        assertTrue(File(temporaryFolder.root, "hidden-applications.nvh.tmp").mkdir())

        val result = repository.hide(notes)

        assertTrue(result.isFailure)
        assertEquals(
            HiddenApplicationFailure.HiddenApplicationsUnwritable,
            (result as NivaraResult.Failure).error,
        )
        assertTrue("the previous set stays authoritative", before.contentEquals(file.readBytes()))
        assertEquals(
            setOf(camera),
            (repository.hiddenApplications() as HiddenApplicationsRead.Available).hidden,
        )
    }

    @Test
    fun `no temporary file is left behind by a change`() = runTest {
        repository().hide(camera)

        val leftovers = temporaryFolder.root.listFiles().orEmpty()
            .filter { child -> child.name.endsWith(".tmp") }

        assertTrue("temporary files must not survive a write: $leftovers", leftovers.isEmpty())
    }

    @Test
    fun `the file holds package names and nothing else`() = runTest {
        repository().hide(camera)

        val bytes = file.readBytes()

        assertEquals(setOf(camera), HiddenApplicationCodec.decode(bytes))
        assertEquals(
            HiddenApplicationCodec.MINIMUM_BYTES + 1 + camera.packageName.length,
            bytes.size,
        )
    }

    @Test
    fun `the stored bytes do not depend on the order applications were hidden in`() = runTest {
        val first = File(temporaryFolder.root, "first.nvh")
        val second = File(temporaryFolder.root, "second.nvh")

        val repositoryA = FileHiddenApplicationRepository(first)
        repositoryA.hide(notes)
        repositoryA.hide(camera)
        val repositoryB = FileHiddenApplicationRepository(second)
        repositoryB.hide(camera)
        repositoryB.hide(notes)

        assertTrue(
            "the same set must produce the same file, whatever order it was built in",
            first.readBytes().contentEquals(second.readBytes()),
        )
    }

    @Test
    fun `concurrent changes are serialised so none is lost`() = runTest {
        val repository = repository()
        val names = (1..24).map { index -> HiddenApplication("com.example.app$index") }

        names.map { application -> async { repository.hide(application) } }.awaitAll()

        val stored = repository.hiddenApplications() as HiddenApplicationsRead.Available
        assertEquals(
            "every concurrent change must be present: a lost update would unhide an application",
            names.toSet(),
            stored.hidden,
        )
    }

    @Test
    fun `a read during a change never sees a partial file`() = runTest {
        val repository = repository()
        repository.hide(camera)

        val reads = (1..12).map { async { repository.hiddenApplications() } }
        val writes = (1..12).map { index -> async { repository.hide(HiddenApplication("com.example.app$index")) } }

        // Atomic replacement means every read sees the set from before some write or the set after it
        // — never a half-written file. A partial file would decode as null, which is Unreadable, so
        // "every read succeeded" is exactly the property under test.
        (reads + writes).awaitAll()
        reads.map { read -> read.await() }.forEach { read ->
            assertEquals("a read during a write must see a whole file", true, read.isAvailable)
        }
        assertTrue(
            "the set is intact after interleaved reads and writes",
            (repository.hiddenApplications() as HiddenApplicationsRead.Available).hidden.contains(camera),
        )
    }
}
