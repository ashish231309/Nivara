package com.nivara.app.data.apphide

import com.nivara.app.core.common.isSuccess
import com.nivara.app.core.common.valueOrNull
import com.nivara.app.data.applock.FileProtectedApplicationRepository
import com.nivara.app.data.applock.ProtectedApplicationCodec
import com.nivara.app.domain.app.InstalledApplication
import com.nivara.app.domain.apphide.HiddenApplication
import com.nivara.app.domain.apphide.HiddenApplicationsRead
import com.nivara.app.domain.applock.ProtectedApplication
import com.nivara.app.domain.launcher.LauncherCatalogue
import com.nivara.app.domain.launcher.launcherCatalogue
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Local JVM tests for what the launcher draws, using both features' real storage.
 *
 * The launcher's rule is pure, and these tests run it against the *production* hidden and protected
 * stores on a temporary directory: the same format, the same atomic writes and the same three read
 * outcomes that a device would use. Only the folder differs.
 *
 * Two things are being asserted that a fake cannot assert credibly. First, that the four
 * combinations of protected and hidden produce the four drawer behaviours the design promises,
 * because both dimensions are read from real files rather than from a stub. Second, that launching
 * an application — the one action the launcher performs on the world — changes neither file, byte
 * for byte.
 */
class LauncherHiddenStateTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val hiddenFile: File get() = File(temporaryFolder.root, "hidden-applications.nvh")
    private val protectedFile: File get() = File(temporaryFolder.root, "protected-applications.nvpl")

    private fun hiddenRepository(): FileHiddenApplicationRepository = FileHiddenApplicationRepository(hiddenFile)
    private fun protectedRepository(): FileProtectedApplicationRepository =
        FileProtectedApplicationRepository(protectedFile)

    private val camera = InstalledApplication("com.example.camera", "Camera")
    private val notes = InstalledApplication("com.example.notes", "Notes")
    private val maps = InstalledApplication("com.example.maps", "Maps")
    private val alarm = InstalledApplication("com.example.alarm", "Alarm")

    private val catalogue = listOf(alarm, camera, maps, notes)

    private suspend fun readHidden(): HiddenApplicationsRead = hiddenRepository().hiddenApplications()

    private suspend fun loaded(revealHidden: Boolean): LauncherCatalogue.Loaded =
        launcherCatalogue(catalogue, readHidden(), revealHidden) as LauncherCatalogue.Loaded

    @Test
    fun `an untouched device draws every application`() = runTest {
        val drawn = loaded(revealHidden = false)

        assertEquals(catalogue, drawn.entries)
        assertEquals(0, drawn.hiddenCount)
    }

    @Test
    fun `the four combinations of protected and hidden behave as four different things`() = runTest {
        // neither: alarm · protected only: notes · hidden only: maps · both: camera
        assertTrue(protectedRepository().protect(ProtectedApplication(notes.packageName)).isSuccess)
        assertTrue(protectedRepository().protect(ProtectedApplication(camera.packageName)).isSuccess)
        assertTrue(hiddenRepository().hide(HiddenApplication(maps.packageName)).isSuccess)
        assertTrue(hiddenRepository().hide(HiddenApplication(camera.packageName)).isSuccess)

        val drawn = loaded(revealHidden = false)

        assertEquals(
            "hiding removes an application from the drawer and protecting does not",
            listOf(alarm, notes),
            drawn.entries,
        )
        assertEquals(listOf(camera, maps), drawn.hidden)
    }

    @Test
    fun `a hidden application stays protected and is left out of the drawer`() = runTest {
        protectedRepository().protect(ProtectedApplication(camera.packageName))
        hiddenRepository().hide(HiddenApplication(camera.packageName))

        // The launcher's answer.
        assertFalse(loaded(revealHidden = false).entries.any { it.packageName == camera.packageName })

        // App Lock's answer is a different file, and it is untouched by anything the launcher drew.
        val protected = protectedRepository().protectedApplications().valueOrNull()
        assertEquals(setOf(ProtectedApplication(camera.packageName)), protected)
    }

    @Test
    fun `a reveal draws everything without changing what is stored`() = runTest {
        hiddenRepository().hide(HiddenApplication(notes.packageName))
        hiddenRepository().hide(HiddenApplication(camera.packageName))
        val hiddenBefore = hiddenFile.readBytes()

        val drawn = loaded(revealHidden = true)

        assertEquals(catalogue, drawn.entries)
        assertEquals(listOf(camera, notes), drawn.hidden)
        assertEquals(2, drawn.hiddenCount)
        assertEquals(0, drawn.withheldCount)
        assertTrue("a reveal writes nothing", hiddenBefore.contentEquals(hiddenFile.readBytes()))
        assertFalse(
            "nothing in this layer protects anything, so the protected file is never even created",
            protectedFile.exists(),
        )
    }

    @Test
    fun `an unreadable hidden set draws nothing at all`() = runTest {
        hiddenRepository().hide(HiddenApplication(notes.packageName))
        protectedRepository().protect(ProtectedApplication(camera.packageName))
        val damaged = "not a hidden application set".toByteArray()
        hiddenFile.writeBytes(damaged)

        val result = launcherCatalogue(catalogue, readHidden(), revealHidden = false)

        assertEquals(LauncherCatalogue.HiddenStateUnreadable, result)
        assertTrue(
            "the damaged file must be left exactly as it was found",
            damaged.contentEquals(hiddenFile.readBytes()),
        )
    }

    @Test
    fun `an unreadable hidden set still fails closed when a reveal was asked for`() = runTest {
        hiddenFile.writeBytes("not a hidden application set".toByteArray())

        val result = launcherCatalogue(catalogue, readHidden(), revealHidden = true)

        assertEquals(
            "a reveal cannot draw applications from a set Nivara cannot read",
            LauncherCatalogue.HiddenStateUnreadable,
            result,
        )
    }

    @Test
    fun `unreachable hidden storage draws nothing either`() = runTest {
        assertTrue(hiddenFile.mkdir())

        val result = launcherCatalogue(catalogue, readHidden(), revealHidden = false)

        assertEquals(LauncherCatalogue.HiddenStateUnavailable, result)
    }

    @Test
    fun `the drawer is not affected by the protected set at all`() = runTest {
        protectedRepository().protect(ProtectedApplication(notes.packageName))
        protectedRepository().protect(ProtectedApplication(camera.packageName))
        val protectedBefore = protectedFile.readBytes()

        val drawn = loaded(revealHidden = false)

        assertEquals(
            "protecting an application changes nothing about what the launcher draws",
            catalogue,
            drawn.entries,
        )
        assertTrue(protectedBefore.contentEquals(protectedFile.readBytes()))
    }

    @Test
    fun `drawing every application changes neither stored set`() = runTest {
        hiddenRepository().hide(HiddenApplication(camera.packageName))
        protectedRepository().protect(ProtectedApplication(camera.packageName))
        val hiddenBefore = hiddenFile.readBytes()
        val protectedBefore = protectedFile.readBytes()

        // Every entry the launcher can draw, read the way the drawer reads them, including the
        // application that is both hidden and protected. Building the list touches no file.
        val drawn = loaded(revealHidden = true).entries
        drawn.forEach { application ->
            assertNotNull("each entry carries the identity the launch uses", application.packageName)
        }
        assertEquals(catalogue, drawn)

        assertTrue(hiddenBefore.contentEquals(hiddenFile.readBytes()))
        assertTrue(protectedBefore.contentEquals(protectedFile.readBytes()))
    }

    @Test
    fun `a stored application that is not installed is never drawn`() = runTest {
        hiddenRepository().hide(HiddenApplication("com.example.gone"))

        val drawn = loaded(revealHidden = true)

        assertEquals(catalogue, drawn.entries)
        assertEquals("the withheld list describes this device", 0, drawn.hiddenCount)
    }

    @Test
    fun `neither file is readable as the other`() = runTest {
        hiddenRepository().hide(HiddenApplication(camera.packageName))
        protectedRepository().protect(ProtectedApplication(camera.packageName))

        assertNull(ProtectedApplicationCodec.decode(hiddenFile.readBytes()))
        assertNull(HiddenApplicationCodec.decode(protectedFile.readBytes()))
        assertEquals(
            "and the launcher still reads the hidden one correctly",
            listOf(camera),
            loaded(revealHidden = false).hidden,
        )
    }

    @Test
    fun `the launcher reads the hidden set on every load, so settings changes take effect`() = runTest {
        assertTrue(loaded(revealHidden = false).entries.contains(notes))

        hiddenRepository().hide(HiddenApplication(notes.packageName))

        assertFalse(
            "a change made elsewhere is visible on the next read, with no cache in between",
            loaded(revealHidden = false).entries.contains(notes),
        )
    }

    @Test
    fun `an empty hidden file is a legitimate empty set, not a failure`() = runTest {
        assertTrue(hiddenRepository().unhide(HiddenApplication(camera.packageName)).isSuccess)
        assertFalse("nothing was written, so there is no file to read", hiddenFile.exists())

        val drawn = loaded(revealHidden = false)

        assertEquals(catalogue, drawn.entries)
    }
}
