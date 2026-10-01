package com.nivara.app.domain.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for the vault's one content classifier.
 *
 * The classifier is what decides whether a file is shown, played, read or described, so what it does
 * with a missing, malformed or unfamiliar type matters as much as what it does with a familiar one.
 * The rule the tests exist for is that a *name* never decides: a file called `holiday.jpg` with no
 * declared type is not an image, and a file with a declared type Nivara does not know is not guessed
 * at from its extension.
 */
class VaultContentClassificationTest {

    // ------------------------------------------------------------------ categories

    @Test
    fun `every category is reachable from a type a provider would declare`() {
        assertEquals(VaultContentKind.Image, VaultContentClassification.kindOf("image/png"))
        assertEquals(VaultContentKind.Video, VaultContentClassification.kindOf("video/mp4"))
        assertEquals(VaultContentKind.Audio, VaultContentClassification.kindOf("audio/mpeg"))
        assertEquals(VaultContentKind.Document, VaultContentClassification.kindOf("application/pdf"))
        assertEquals(VaultContentKind.Other, VaultContentClassification.kindOf("application/octet-stream"))
    }

    @Test
    fun `a family is recognised by its type, not by a list of exact strings`() {
        // A family Nivara has no viewer for is still classified as that family: the screen may say
        // "this is an image Nivara cannot show" rather than "this is a file".
        assertEquals(VaultContentKind.Image, VaultContentClassification.kindOf("image/tiff"))
        assertEquals(VaultContentKind.Video, VaultContentClassification.kindOf("video/x-msvideo"))
        assertEquals(VaultContentKind.Audio, VaultContentClassification.kindOf("audio/basic"))
        assertEquals(VaultContentKind.Document, VaultContentClassification.kindOf("text/x-log"))
    }

    @Test
    fun `a type is normalised before it is classified`() {
        assertEquals(VaultContentKind.Image, VaultContentClassification.kindOf("IMAGE/PNG"))
        assertEquals(VaultContentKind.Image, VaultContentClassification.kindOf(" image/jpeg "))
        assertEquals(
            VaultContentKind.Document,
            VaultContentClassification.kindOf("text/plain; charset=utf-8"),
        )
    }

    @Test
    fun `an office document is a document even without a viewer`() {
        assertEquals(VaultContentKind.Document, VaultContentClassification.kindOf("application/msword"))
        assertEquals(
            VaultContentKind.Document,
            VaultContentClassification.kindOf(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            ),
        )
    }

    // ------------------------------------------------------------------ conservative fallbacks

    @Test
    fun `a missing type is not guessed at`() {
        assertEquals(VaultContentKind.Other, VaultContentClassification.kindOf(null))
        assertEquals(VaultContentKind.Other, VaultContentClassification.kindOf(""))
        assertEquals(VaultContentKind.Other, VaultContentClassification.kindOf("   "))
    }

    @Test
    fun `a malformed type is treated as a missing one`() {
        assertEquals(VaultContentKind.Other, VaultContentClassification.kindOf("not a type"))
        assertEquals(VaultContentKind.Other, VaultContentClassification.kindOf("image"))
        assertEquals(VaultContentKind.Other, VaultContentClassification.kindOf("/png"))
        assertEquals(VaultContentKind.Other, VaultContentClassification.kindOf("image/"))
        assertEquals(VaultContentKind.Other, VaultContentClassification.kindOf("a/b/c"))
    }

    @Test
    fun `an absurdly long type is refused rather than classified`() {
        assertEquals(VaultContentKind.Other, VaultContentClassification.kindOf("image/" + "a".repeat(400)))
    }

    @Test
    fun `classification never consults a file name`() {
        // There is no parameter for a name, and no name is stored anywhere in this model: what a file
        // is called cannot influence what Nivara thinks it is.
        assertEquals(VaultContentKind.Other, VaultContentClassification.kindOf(null))
        assertFalse(VaultContentClassification.hasViewer(null))
    }

    // ------------------------------------------------------------------ viewer support

    @Test
    fun `images Nivara draws are exactly the ones it claims`() {
        assertTrue(VaultContentClassification.isViewableImage("image/jpeg"))
        assertTrue(VaultContentClassification.isViewableImage("image/png"))
        assertTrue(VaultContentClassification.isViewableImage("image/webp"))
        assertTrue(VaultContentClassification.isViewableImage("image/gif"))
        // Known family, no viewer: classified as an image and not claimed to be drawable.
        assertFalse(VaultContentClassification.isViewableImage("image/tiff"))
        assertFalse(VaultContentClassification.isViewableImage("image/svg+xml"))
    }

    @Test
    fun `video and audio support is the platform's stack, and it is bounded`() {
        assertTrue(VaultContentClassification.isPlayableVideo("video/mp4"))
        assertTrue(VaultContentClassification.isPlayableVideo("video/webm"))
        assertTrue(VaultContentClassification.isPlayableAudio("audio/mpeg"))
        assertTrue(VaultContentClassification.isPlayableAudio("audio/mp4"))
        assertTrue(VaultContentClassification.isPlayableAudio("audio/wav"))
        assertFalse(VaultContentClassification.isPlayableVideo("video/mp2t"))
        assertFalse(VaultContentClassification.isPlayableAudio("audio/amr"))
    }

    @Test
    fun `documents are shown as text or rendered, and nothing else is claimed`() {
        assertTrue(VaultContentClassification.isReadableText("text/plain"))
        assertTrue(VaultContentClassification.isReadableText("text/csv"))
        assertTrue(VaultContentClassification.isRenderableDocument("application/pdf"))
        assertFalse(VaultContentClassification.isReadableText("application/pdf"))
        assertFalse(VaultContentClassification.isRenderableDocument("application/msword"))
    }

    @Test
    fun `having a viewer is exactly the union of what is supported`() {
        assertTrue(VaultContentClassification.hasViewer("image/png"))
        assertTrue(VaultContentClassification.hasViewer("video/mp4"))
        assertTrue(VaultContentClassification.hasViewer("audio/mpeg"))
        assertTrue(VaultContentClassification.hasViewer("text/plain"))
        assertTrue(VaultContentClassification.hasViewer("application/pdf"))
        assertFalse(VaultContentClassification.hasViewer("application/msword"))
        assertFalse(VaultContentClassification.hasViewer("application/octet-stream"))
        assertFalse(VaultContentClassification.hasViewer("image/tiff"))
    }

    @Test
    fun `every kind has a viewer decision, and only other never has one`() {
        for (kind in VaultContentKind.entries) {
            val type = when (kind) {
                VaultContentKind.Image -> "image/png"
                VaultContentKind.Video -> "video/mp4"
                VaultContentKind.Audio -> "audio/mpeg"
                VaultContentKind.Document -> "text/plain"
                VaultContentKind.Other -> "application/octet-stream"
            }
            assertEquals(kind, VaultContentClassification.kindOf(type))
            if (kind == VaultContentKind.Other) {
                assertFalse(VaultContentClassification.hasViewer(type))
            } else {
                assertTrue(VaultContentClassification.hasViewer(type))
            }
        }
    }
}
