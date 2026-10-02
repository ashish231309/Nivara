package com.nivara.app.data.applock

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.isFailure
import com.nivara.app.core.common.isSuccess
import com.nivara.app.core.common.valueOrNull
import com.nivara.app.domain.applock.AppLockFailure
import com.nivara.app.domain.applock.ProtectedApplication
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Local JVM tests for the stored protected application set.
 *
 * The production store is exercised against real files: the same format, the same atomic write and
 * the same failure mapping that run on a device. Only the directory differs — a temporary folder
 * instead of the application's private storage — which is exactly why the store takes a `File`
 * rather than a `Context`.
 *
 * The security-relevant cases are the ones about damage. A file that cannot be read must be
 * reported, must never be read as "nothing is protected", and must not be quietly replaced by a
 * change that cannot see what it would overwrite.
 */
class FileProtectedApplicationRepositoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val file: File get() = File(temporaryFolder.root, "protected-applications.nvl")

    private val camera = ProtectedApplication("com.example.camera")
    private val notes = ProtectedApplication("com.example.notes")

    private fun repository(): FileProtectedApplicationRepository =
        FileProtectedApplicationRepository(file)

    @Test
    fun `nothing is protected before anything is written`() = runTest {
        val result = repository().protectedApplications()

        assertTrue(result.isSuccess)
        assertEquals(emptySet<ProtectedApplication>(), result.valueOrNull())
        assertFalse("reading must not create the file", file.exists())
    }

    @Test
    fun `a protected application survives a new instance`() = runTest {
        repository().protect(camera)

        val reloaded = repository().protectedApplications().valueOrNull()

        assertEquals(setOf(camera), reloaded)
    }

    @Test
    fun `protecting twice stores it once`() = runTest {
        val repository = repository()
        repository.protect(camera)
        val afterFirst = file.readBytes()

        val second = repository.protect(camera)

        assertTrue(second.isSuccess)
        assertEquals(setOf(camera), repository.protectedApplications().valueOrNull())
        assertTrue("an unchanged set must not be rewritten", afterFirst.contentEquals(file.readBytes()))
    }

    @Test
    fun `applications accumulate and can be removed`() = runTest {
        val repository = repository()
        repository.protect(camera)
        repository.protect(notes)

        assertEquals(setOf(camera, notes), repository.protectedApplications().valueOrNull())

        val removed = repository.unprotect(camera)

        assertTrue(removed.isSuccess)
        assertEquals(setOf(notes), repository.protectedApplications().valueOrNull())
    }

    @Test
    fun `removing something that was never protected changes nothing`() = runTest {
        val repository = repository()

        val result = repository.unprotect(camera)

        assertTrue(result.isSuccess)
        assertFalse("there was nothing to write", file.exists())
    }

    @Test
    fun `a damaged file is reported instead of being read as an empty set`() = runTest {
        val damaged = "not a protected application set".toByteArray()
        file.writeBytes(damaged)

        val result = repository().protectedApplications()

        assertTrue("a damaged set must not become 'nothing is protected'", result.isFailure)
        assertTrue(result.error() is AppLockFailure.ProtectedApplicationsUnreadable)
        assertTrue("reading must not rewrite the file", damaged.contentEquals(file.readBytes()))
    }

    @Test
    fun `a change is refused while the stored set cannot be read`() = runTest {
        val damaged = "not a protected application set".toByteArray()
        file.writeBytes(damaged)
        val repository = repository()

        val result = repository.protect(camera)

        assertTrue(result.isFailure)
        assertTrue(result.error() is AppLockFailure.ProtectedApplicationsUnreadable)
        assertTrue(
            "a change must not replace configuration it cannot read",
            damaged.contentEquals(file.readBytes()),
        )
    }

    @Test
    fun `no temporary file is left behind by a change`() = runTest {
        repository().protect(camera)

        val leftovers = temporaryFolder.root.listFiles().orEmpty()
            .filter { child -> child.name.endsWith(".tmp") }

        assertTrue("temporary files must not survive a write: $leftovers", leftovers.isEmpty())
    }

    @Test
    fun `a write that cannot be completed leaves no temporary file behind`() = runTest {
        // A directory where the set belongs makes the atomic rename fail, which is the closest a
        // test can come to an interrupted write without controlling the file system.
        assertTrue(file.mkdir())

        val result = repository().protect(camera)

        assertTrue(result.isFailure)
        assertTrue(result.error() is AppLockFailure.ProtectedApplicationsUnwritable)
        assertTrue(file.isDirectory)
        assertTrue(
            "a failed write must not leave a temporary file",
            temporaryFolder.root.listFiles().orEmpty().none { child -> child.name.endsWith(".tmp") },
        )
    }

    @Test
    fun `the file holds package names and nothing else`() = runTest {
        repository().protect(camera)

        val bytes = file.readBytes()

        assertEquals(setOf(camera), ProtectedApplicationCodec.decode(bytes))
        assertEquals(
            ProtectedApplicationCodec.MINIMUM_BYTES + 1 + camera.packageName.length,
            bytes.size,
        )
    }

    @Test
    fun `the stored bytes do not depend on the order applications were protected in`() = runTest {
        val first = File(temporaryFolder.root, "first.nvl")
        val second = File(temporaryFolder.root, "second.nvl")

        val repositoryA = FileProtectedApplicationRepository(first)
        repositoryA.protect(notes)
        repositoryA.protect(camera)
        val repositoryB = FileProtectedApplicationRepository(second)
        repositoryB.protect(camera)
        repositoryB.protect(notes)

        assertTrue(
            "the same set must produce the same file, whatever order it was built in",
            first.readBytes().contentEquals(second.readBytes()),
        )
    }

    private fun NivaraResult<*>.error(): Exception? = (this as? NivaraResult.Failure)?.error
}
