package com.nivara.app.domain.applock

import com.nivara.app.domain.app.PackageNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Local JVM tests for the foreground-application model.
 *
 * Two things are being verified. The identity is the package name, as it is for a protected
 * application — the two types are different roles for the same identity, not two identities. And
 * `of` is a filter: a platform event that carries no usable name describes no application, so it
 * becomes `null` instead of a value the detector would have to special-case later.
 */
class ForegroundApplicationTest {

    @Test
    fun `a usable package name becomes a foreground application`() {
        assertEquals(
            ForegroundApplication("com.example.camera"),
            ForegroundApplication.of("com.example.camera"),
        )
    }

    @Test
    fun `a missing package name is not an application`() {
        assertNull(ForegroundApplication.of(null))
    }

    @Test
    fun `an unusable package name is not an application`() {
        val unusable = listOf(
            "",
            "   ",
            "com.example camera",
            "com/example/camera",
            ".",
            "a".repeat(PackageNames.MAXIMUM_LENGTH + 1),
        )

        unusable.forEach { name ->
            assertNull("expected '$name' to describe no application", ForegroundApplication.of(name))
        }
    }

    @Test
    fun `a system package is an ordinary foreground application`() {
        // The launcher and the system UI are packages like any other here: the decision layer's
        // only special case is Nivara itself, and it is made from the protected set and the
        // injected package name rather than from what a package looks like.
        assertEquals(
            ForegroundApplication("com.android.systemui"),
            ForegroundApplication.of("com.android.systemui"),
        )
        assertEquals(ForegroundApplication("android"), ForegroundApplication.of("android"))
    }

    @Test
    fun `a blank name cannot be constructed directly either`() {
        assertThrows(IllegalArgumentException::class.java) { ForegroundApplication("") }
    }

    @Test
    fun `the package name is the identity`() {
        val first = ForegroundApplication("com.example.camera")

        assertEquals(first, ForegroundApplication("com.example.camera"))
        assertEquals(first.hashCode(), ForegroundApplication("com.example.camera").hashCode())
        assertNotEquals(first, ForegroundApplication("com.example.notes"))
    }
}
