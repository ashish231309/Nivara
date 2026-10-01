package com.nivara.app.ui.vault.viewer

import com.nivara.app.R
import com.nivara.app.data.vault.viewer.VaultViewerFailure
import com.nivara.app.domain.vault.VaultContentClassification
import com.nivara.app.domain.vault.VaultContentFailure
import com.nivara.app.domain.vault.VaultContentKind
import com.nivara.app.domain.vault.VaultItemId
import com.nivara.app.domain.vault.VaultState
import com.nivara.app.ui.vault.VaultItemUi
import com.nivara.app.ui.vault.vaultItemTypeRes
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests for what the viewer says.
 *
 * The screen is not drawn here — that is the instrumented suite's job on a device. What is verified is
 * the vocabulary underneath it, which is where this stage's central promise lives: an unsupported
 * format, a missing object, unreadable storage, content that did not authenticate, a decoder that
 * refused the file and a locked session are six different sentences, and none of them is "something
 * went wrong". A second, quieter promise is checked here too: nothing a screen holds can carry bytes,
 * a stream, a decoder or a key.
 */
class VaultViewerPresentationTest {

    private fun item(
        name: String = "holiday.jpg",
        mimeType: String? = "image/jpeg",
        kind: VaultContentKind = VaultContentClassification.kindOf(mimeType),
        sizeBytes: Long = 2_048L,
    ): VaultViewerItem = VaultViewerItem(
        id = VaultItemId("00112233445566778899aabbccddeeff"),
        name = name,
        mimeType = mimeType,
        kind = kind,
        sizeBytes = sizeBytes,
        importedAtEpochMillis = 1_700_000_000_000L,
    )

    /** Every state a viewer can be in, including the ones only a failure produces. */
    private fun everyState(): List<VaultViewerUiState> {
        val target = item()
        return listOf(
            VaultViewerUiState.Closed,
            VaultViewerUiState.Opening(target),
            VaultViewerUiState.Image(target, width = 800, height = 600, sampled = false),
            VaultViewerUiState.Video(target, playing = false, positionMillis = 0L, durationMillis = 1_000L),
            VaultViewerUiState.Audio(target, playing = false, positionMillis = 0L, durationMillis = 1_000L),
            VaultViewerUiState.Text(target, characters = 100, truncated = false),
            VaultViewerUiState.Document(target, page = 0, pageCount = 3),
            VaultViewerUiState.Unsupported(target),
            VaultViewerUiState.Missing(target),
            VaultViewerUiState.Unreadable(target),
            VaultViewerUiState.Corrupt(target),
            VaultViewerUiState.Failed(target),
            VaultViewerUiState.Locked(target),
            VaultViewerUiState.Locked(null),
        )
    }

    // ------------------------------------------------------------------ six different sentences

    @Test
    fun `the states that need explaining each have their own title`() {
        val titles = listOf(
            VaultViewerUiState.Opening(item()),
            VaultViewerUiState.Unsupported(item()),
            VaultViewerUiState.Missing(item()),
            VaultViewerUiState.Unreadable(item()),
            VaultViewerUiState.Corrupt(item()),
            VaultViewerUiState.Failed(item()),
            VaultViewerUiState.Locked(item()),
        ).map { state -> state.titleRes() }

        assertTrue("every one of them says something", titles.all { it != null })
        assertEquals("and no two of them say the same thing", titles.size, titles.toSet().size)
    }

    @Test
    fun `content states carry no sentence over them`() {
        val target = item()
        for (state in listOf(
            VaultViewerUiState.Image(target, 800, 600, sampled = false),
            VaultViewerUiState.Video(target, playing = false, positionMillis = 0L, durationMillis = 1L),
            VaultViewerUiState.Audio(target, playing = false, positionMillis = 0L, durationMillis = 1L),
            VaultViewerUiState.Text(target, characters = 10, truncated = false),
            VaultViewerUiState.Document(target, page = 0, pageCount = 1),
        )) {
            assertNull("an item is its own explanation", state.titleRes())
            assertNull(state.bodyRes())
        }
        assertNull(VaultViewerUiState.Closed.titleRes())
        assertNull(VaultViewerUiState.Closed.bodyRes())
    }

    @Test
    fun `every state that explains itself has something to say underneath`() {
        // Opening is progress rather than an explanation: the screen draws its one line and the
        // explanation card is never reached for it.
        for (state in listOf(
            VaultViewerUiState.Unsupported(item()),
            VaultViewerUiState.Missing(item()),
            VaultViewerUiState.Unreadable(item()),
            VaultViewerUiState.Corrupt(item()),
            VaultViewerUiState.Failed(item()),
            VaultViewerUiState.Locked(item()),
        )) {
            assertTrue("${state::class.simpleName} has a body", state.bodyRes() != null)
        }
    }

    @Test
    fun `retrying is offered only where retrying could change something`() {
        assertTrue(VaultViewerUiState.Unreadable(item()).canRetry())
        assertTrue(VaultViewerUiState.Failed(item()).canRetry())
        // Facts: repeating them would suggest Nivara could change them.
        assertFalse(VaultViewerUiState.Missing(item()).canRetry())
        assertFalse(VaultViewerUiState.Corrupt(item()).canRetry())
        assertFalse(VaultViewerUiState.Unsupported(item()).canRetry())
        assertFalse(VaultViewerUiState.Locked(item()).canRetry())
        assertFalse(VaultViewerUiState.Closed.canRetry())
    }

    @Test
    fun `only the locked state sends anyone to the credential screen`() {
        for (state in everyState()) {
            assertEquals(
                "unlocking fixes exactly one state",
                state is VaultViewerUiState.Locked,
                state.needsUnlock(),
            )
        }
    }

    // ------------------------------------------------------------------ failures, mapped once

    @Test
    fun `every content failure becomes a different state`() {
        val target = item()
        val mapped = listOf(
            VaultContentFailure.NotAuthorized,
            VaultContentFailure.ContentMissing,
            VaultContentFailure.Unreadable,
            VaultContentFailure.Corrupt,
            VaultContentFailure.KeyUnavailable,
            VaultContentFailure.VaultNotReady(VaultState.Missing),
        ).map { failure -> VaultViewerFailure.Content(failure).toUiState(target) }

        assertEquals(VaultViewerUiState.Locked(target), mapped[0])
        assertEquals(VaultViewerUiState.Missing(target), mapped[1])
        assertEquals(VaultViewerUiState.Unreadable(target), mapped[2])
        assertEquals(VaultViewerUiState.Corrupt(target), mapped[3])
        assertEquals(VaultViewerUiState.Unreadable(target), mapped[4])
        assertEquals(VaultViewerUiState.Unreadable(target), mapped[5])
        assertNotEquals(
            "a missing object is never reported as corruption",
            mapped[1],
            mapped[3],
        )
    }

    @Test
    fun `a decoder refusing the file is never reported as damage`() {
        val target = item()

        assertEquals(VaultViewerUiState.Failed(target), VaultViewerFailure.DecodeFailed.toUiState(target))
        assertNotEquals(
            VaultViewerUiState.Corrupt(target),
            VaultViewerFailure.DecodeFailed.toUiState(target),
        )
    }

    @Test
    fun `a viewer that was closed while opening reports nothing`() {
        assertEquals(VaultViewerUiState.Closed, VaultViewerFailure.Cancelled.toUiState(item()))
    }

    @Test
    fun `an item's type is shown by the kind the classifier gave it, never by its name`() {
        // A file whose name says one thing and whose type says nothing is a file.
        val unknown = VaultItemUi(
            id = VaultItemId("00112233445566778899aabbccddeeff"),
            name = "holiday.jpg",
            kind = VaultContentClassification.kindOf(null),
            sizeBytes = 1L,
            importedAtEpochMillis = 1L,
            mimeType = null,
        )
        assertEquals(R.string.vault_item_type_other, vaultItemTypeRes(unknown))

        for ((type, expected) in listOf(
            "image/png" to R.string.vault_item_type_image,
            "video/mp4" to R.string.vault_item_type_video,
            "audio/mpeg" to R.string.vault_item_type_audio,
            "text/plain" to R.string.vault_item_type_text,
            "application/pdf" to R.string.vault_item_type_document,
            "application/octet-stream" to R.string.vault_item_type_other,
        )) {
            val row = unknown.copy(kind = VaultContentClassification.kindOf(type), mimeType = type)
            assertEquals(type, expected, vaultItemTypeRes(row))
        }
    }

    @Test
    fun `the viewer names a type the same way the list does`() {
        for (type in listOf(
            "image/png",
            "video/mp4",
            "audio/mpeg",
            "text/plain",
            "application/pdf",
            "application/octet-stream",
        )) {
            val kind = VaultContentClassification.kindOf(type)
            assertEquals(
                "the two screens must not disagree about $type",
                vaultViewerTypeRes(item(mimeType = type, kind = kind)),
                vaultItemTypeRes(
                    VaultItemUi(
                        id = VaultItemId("00112233445566778899aabbccddeeff"),
                        name = "file",
                        kind = kind,
                        sizeBytes = 1L,
                        importedAtEpochMillis = 1L,
                        mimeType = type,
                    ),
                ),
            )
        }
    }

    // ------------------------------------------------------------------ positions

    @Test
    fun `a playback position is written as a clock reads`() {
        assertEquals("0:00", formatPlaybackTime(0L))
        assertEquals("0:01", formatPlaybackTime(1_000L))
        assertEquals("0:59", formatPlaybackTime(59_999L))
        assertEquals("1:05", formatPlaybackTime(65_000L))
        assertEquals("59:59", formatPlaybackTime(3_599_000L))
        assertEquals("1:00:00", formatPlaybackTime(3_600_000L))
        assertEquals("1:01:01", formatPlaybackTime(3_661_000L))
    }

    @Test
    fun `a negative or absurd position cannot produce nonsense`() {
        assertEquals("0:00", formatPlaybackTime(-5_000L))
        // Long.MAX_VALUE milliseconds is about 292 million years; the format still holds.
        assertTrue(formatPlaybackTime(Long.MAX_VALUE).startsWith("2562047788015:"))
    }

    @Test
    fun `a position is formatted the same way whatever the phone's locale is`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals("1:05", formatPlaybackTime(65_000L))
            Locale.setDefault(Locale.forLanguageTag("de-DE"))
            assertEquals("1:05", formatPlaybackTime(65_000L))
        } finally {
            Locale.setDefault(previous)
        }
    }

    // ------------------------------------------------------------------ what a state may hold

    @Test
    fun `no viewer state can hold bytes, a stream, a decoder or a key`() {
        val forbidden = listOf(
            ByteArray::class.java,
            java.io.InputStream::class.java,
            javax.crypto.Cipher::class.java,
            javax.crypto.SecretKey::class.java,
            android.graphics.Bitmap::class.java,
            android.media.MediaPlayer::class.java,
            android.net.Uri::class.java,
            java.io.File::class.java,
        )
        for (state in everyState()) {
            var type: Class<*>? = state.javaClass
            while (type != null && type != Any::class.java) {
                for (field in type.declaredFields) {
                    assertFalse(
                        "${state::class.simpleName}.${field.name} must not be a ${field.type.simpleName}",
                        field.type in forbidden,
                    )
                }
                type = type.superclass
            }
        }
    }

    @Test
    fun `an item in a state is described by facts and never by an address`() {
        // Whatever the toolchain adds to a compiled class as a static marker, the fields that carry an
        // item are its own facts: an identifier, a name, a declared type, a size and an arrival time.
        val carried = VaultViewerItem::class.java.declaredFields
            .filter { field -> !java.lang.reflect.Modifier.isStatic(field.modifiers) }

        assertEquals(
            "an item carries its own facts and nothing else",
            setOf("id", "name", "mimeType", "kind", "sizeBytes", "importedAtEpochMillis"),
            carried.map { field -> field.name }.toSet(),
        )
        val types = carried.map { field -> field.type }
        assertTrue(types.contains(VaultItemId::class.java))
        assertTrue(types.contains(String::class.java))
        assertTrue(types.contains(Long::class.javaPrimitiveType))
        assertTrue(types.contains(VaultContentKind::class.java))
        // Never an address and never content: no Uri, no file, no stream and no decoded image.
        for (forbidden in listOf(
            android.net.Uri::class.java,
            java.io.File::class.java,
            java.io.InputStream::class.java,
            android.graphics.Bitmap::class.java,
        )) {
            assertFalse(
                "an item must not hold a ${forbidden.simpleName}",
                types.contains(forbidden),
            )
        }
    }
}
