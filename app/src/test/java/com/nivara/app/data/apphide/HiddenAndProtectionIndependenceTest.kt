package com.nivara.app.data.apphide

import com.nivara.app.core.common.isSuccess
import com.nivara.app.core.common.valueOrNull
import com.nivara.app.data.applock.FileProtectedApplicationRepository
import com.nivara.app.data.applock.ProtectedApplicationCodec
import com.nivara.app.domain.app.InstalledApplication
import com.nivara.app.domain.apphide.HiddenApplication
import com.nivara.app.domain.apphide.HiddenApplicationsRead
import com.nivara.app.domain.applock.ProtectedApplication
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Local JVM tests for the independence of hiding and protecting.
 *
 * Nivara keeps two decisions about an application — whether it is locked, and whether it is out of
 * sight — and the two are genuinely independent: all four combinations occur, and none of them is a
 * contradiction. What this suite verifies is that the independence is real rather than a promise:
 * both sets are stored by the production repositories, in the same directory and through the same
 * low-level byte helpers, and every change to one of them leaves the other exactly as it was.
 *
 * Both dimensions are exercised with their real persistence, on a temporary directory rather than
 * the application's private storage. The repositories are the ones the device uses; only the folder
 * differs.
 */
class HiddenAndProtectionIndependenceTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** The two features' files, as the container names them, side by side. */
    private val hiddenFile: File get() = File(temporaryFolder.root, "hidden-applications.nvh")
    private val protectedFile: File get() = File(temporaryFolder.root, "protected-applications.nvpl")

    private fun hiddenRepository(): FileHiddenApplicationRepository = FileHiddenApplicationRepository(hiddenFile)
    private fun protectedRepository(): FileProtectedApplicationRepository =
        FileProtectedApplicationRepository(protectedFile)

    private val camera = InstalledApplication("com.example.camera", "Camera")
    private val notes = InstalledApplication("com.example.notes", "Notes")

    private suspend fun hiddenSet(): Set<HiddenApplication> =
        (hiddenRepository().hiddenApplications() as HiddenApplicationsRead.Available).hidden

    private suspend fun protectedSet(): Set<ProtectedApplication> =
        requireNotNull(protectedRepository().protectedApplications().valueOrNull())

    @Test
    fun `an application can be neither protected nor hidden`() = runTest {
        assertTrue(hiddenSet().isEmpty())
        assertTrue(protectedSet().isEmpty())
        assertFalse(hiddenFile.exists())
        assertFalse(protectedFile.exists())
    }

    @Test
    fun `an application can be hidden on its own`() = runTest {
        assertTrue(hiddenRepository().hide(HiddenApplication(camera.packageName)).isSuccess)

        assertEquals(setOf(camera.packageName), hiddenSet().map { it.packageName }.toSet())
        assertTrue("hiding is not protecting", protectedSet().isEmpty())
    }

    @Test
    fun `an application can be protected on its own`() = runTest {
        assertTrue(protectedRepository().protect(ProtectedApplication(notes.packageName)).isSuccess)

        assertEquals(setOf(notes.packageName), protectedSet().map { it.packageName }.toSet())
        assertTrue("protecting is not hiding", hiddenSet().isEmpty())
    }

    @Test
    fun `an application can be both protected and hidden`() = runTest {
        assertTrue(protectedRepository().protect(ProtectedApplication(camera.packageName)).isSuccess)
        assertTrue(hiddenRepository().hide(HiddenApplication(camera.packageName)).isSuccess)

        assertEquals(setOf(camera.packageName), protectedSet().map { it.packageName }.toSet())
        assertEquals(setOf(camera.packageName), hiddenSet().map { it.packageName }.toSet())
    }

    @Test
    fun `all four combinations exist side by side without contradicting each other`() = runTest {
        // neither: alarm, protected only: notes, hidden only: map, both: camera.
        val alarm = InstalledApplication("com.example.alarm", "Alarm")
        val map = InstalledApplication("com.example.maps", "Maps")

        assertTrue(protectedRepository().protect(ProtectedApplication(notes.packageName)).isSuccess)
        assertTrue(protectedRepository().protect(ProtectedApplication(camera.packageName)).isSuccess)
        assertTrue(hiddenRepository().hide(HiddenApplication(map.packageName)).isSuccess)
        assertTrue(hiddenRepository().hide(HiddenApplication(camera.packageName)).isSuccess)

        val protectedNames = protectedSet().map { it.packageName }.toSet()
        val hiddenNames = hiddenSet().map { it.packageName }.toSet()

        assertEquals(setOf(notes.packageName, camera.packageName), protectedNames)
        assertEquals(setOf(map.packageName, camera.packageName), hiddenNames)
        assertFalse("neither", alarm.packageName in protectedNames || alarm.packageName in hiddenNames)
        assertTrue("protected only", notes.packageName in protectedNames && notes.packageName !in hiddenNames)
        assertTrue("hidden only", map.packageName in hiddenNames && map.packageName !in protectedNames)
        assertTrue("both", camera.packageName in protectedNames && camera.packageName in hiddenNames)
    }

    @Test
    fun `hiding does not protect, and unhiding does not unprotect`() = runTest {
        val protected = protectedRepository()
        val hidden = hiddenRepository()
        protected.protect(ProtectedApplication(camera.packageName))

        hidden.hide(HiddenApplication(camera.packageName))
        hidden.unhide(HiddenApplication(camera.packageName))

        assertEquals(
            "the protected set must be untouched by anything hiding does",
            setOf(camera.packageName),
            protectedSet().map { it.packageName }.toSet(),
        )
    }

    @Test
    fun `protecting does not hide, and unprotecting does not unhide`() = runTest {
        val protected = protectedRepository()
        val hidden = hiddenRepository()
        hidden.hide(HiddenApplication(camera.packageName))

        protected.protect(ProtectedApplication(camera.packageName))
        protected.unprotect(ProtectedApplication(camera.packageName))

        assertEquals(
            "the hidden set must be untouched by anything protecting does",
            setOf(camera.packageName),
            hiddenSet().map { it.packageName }.toSet(),
        )
    }

    @Test
    fun `each feature writes its own file`() = runTest {
        hiddenRepository().hide(HiddenApplication(camera.packageName))

        assertTrue("hiding must not create the App Lock file", hiddenFile.exists())
        assertFalse("hiding must not create the App Lock file", protectedFile.exists())

        protectedRepository().protect(ProtectedApplication(notes.packageName))

        assertTrue(protectedFile.exists())
        assertEquals(
            "the protected file holds the protected set only",
            setOf(notes.packageName),
            protectedSet().map { it.packageName }.toSet(),
        )
        assertEquals(
            "and the hidden file still holds the hidden set only",
            setOf(camera.packageName),
            hiddenSet().map { it.packageName }.toSet(),
        )
    }

    @Test
    fun `damage to one file leaves the other feature untouched`() = runTest {
        hiddenRepository().hide(HiddenApplication(camera.packageName))
        protectedRepository().protect(ProtectedApplication(notes.packageName))
        val damaged = "not a protected set"

        protectedFile.writeBytes(damaged.toByteArray())

        assertEquals(
            "a broken App Lock file says nothing about what is hidden",
            setOf(camera.packageName),
            hiddenSet().map { it.packageName }.toSet(),
        )
        assertTrue(
            "and it does not cause hiding to rewrite its own file",
            hiddenFile.readBytes().isNotEmpty(),
        )
        assertEquals(
            "the damaged file is left exactly as it was found",
            damaged,
            String(protectedFile.readBytes()),
        )
    }

    @Test
    fun `one feature's file is not readable as the other's`() = runTest {
        hiddenRepository().hide(HiddenApplication(camera.packageName))
        protectedRepository().protect(ProtectedApplication(notes.packageName))

        assertNull(
            "a protected set must never decode as a hidden set",
            HiddenApplicationCodec.decode(protectedFile.readBytes()),
        )
        assertNull(
            "a hidden set must never decode as a protected set",
            ProtectedApplicationCodec.decode(hiddenFile.readBytes()),
        )
    }

    @Test
    fun `the two files are not the same file`() {
        assertFalse(hiddenFile.absolutePath == protectedFile.absolutePath)
    }

    @Test
    fun `a failure in one feature leaves the other feature's set authoritative`() = runTest {
        hiddenRepository().hide(HiddenApplication(camera.packageName))
        protectedRepository().protect(ProtectedApplication(notes.packageName))

        // An unreadable hidden file refuses hiding but says nothing about App Lock.
        hiddenFile.writeBytes("not a hidden set".toByteArray())
        val refused = hiddenRepository().hide(HiddenApplication(notes.packageName))

        assertFalse(refused.isSuccess)
        assertEquals(
            "the App Lock set is unaffected by the hidden file's damage",
            setOf(notes.packageName),
            protectedSet().map { it.packageName }.toSet(),
        )
        assertEquals(
            "and the damaged hidden file is not overwritten by the refused change",
            "not a hidden set",
            String(hiddenFile.readBytes()),
        )
    }

    @Test
    fun `both features store an application under the very same package name`() = runTest {
        // The identity rule is shared, and it is the same rule: an application is its package name.
        hiddenRepository().hide(HiddenApplication(camera.packageName))
        protectedRepository().protect(ProtectedApplication(camera.packageName))

        assertEquals(camera.packageName, hiddenSet().single().packageName)
        assertEquals(camera.packageName, protectedSet().single().packageName)
    }

    @Test
    fun `a change to one feature does not rewrite the other's bytes`() = runTest {
        // The clearest form of "no shared mutable state": the other feature's file is byte-identical
        // before and after, so nothing about a hide ran through the App Lock store or vice versa.
        hiddenRepository().hide(HiddenApplication(camera.packageName))
        protectedRepository().protect(ProtectedApplication(notes.packageName))
        val protectedBefore = protectedFile.readBytes()

        hiddenRepository().hide(HiddenApplication(notes.packageName))
        hiddenRepository().unhide(HiddenApplication(camera.packageName))

        assertTrue(
            "hiding must not touch the App Lock bytes",
            protectedBefore.contentEquals(protectedFile.readBytes()),
        )
    }

    @Test
    fun `unprotecting leaves the hidden set readable and unchanged`() = runTest {
        hiddenRepository().hide(HiddenApplication(camera.packageName))
        val hiddenBefore = hiddenFile.readBytes()

        protectedRepository().unprotect(ProtectedApplication(camera.packageName))

        assertTrue(hiddenBefore.contentEquals(hiddenFile.readBytes()))
        assertEquals(setOf(HiddenApplication(camera.packageName)), hiddenSet())
    }
}
