package com.nivara.app.ui.vault.viewer

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nivara.app.R
import com.nivara.app.domain.vault.VaultContentClassification
import com.nivara.app.domain.vault.VaultContentKind
import com.nivara.app.domain.vault.VaultItemId
import com.nivara.app.ui.theme.NivaraTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for what the viewer draws.
 *
 * The stateless screen is handed a state and the actions it reports are observed, so what is verified
 * here is the composition: each state appears with its own words and its own controls, an unsupported
 * file is described rather than drawn, a missing object still names the entry the vault keeps, a
 * locked viewer points at the credential screen that already exists, and the picture, playback and
 * page controls do what they say.
 *
 * The screen is drawn on a device, but the vault is not: no content reader, no platform decoder and no
 * media player is involved in any test here. What that leaves unverified is stated plainly rather
 * than implied — real decoding of real files, playback through the vault's stream, Storage Access
 * Framework reads, large-file rendering and TalkBack traversal are exercised only when the suite runs
 * on a device with content that only a person can import.
 */
@RunWith(AndroidJUnit4::class)
class VaultViewerScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun string(id: Int): String = context.getString(id)

    private fun string(id: Int, vararg arguments: Any): String = context.getString(id, *arguments)

    private fun item(
        name: String = "holiday.jpg",
        mimeType: String? = "image/jpeg",
        kind: VaultContentKind = VaultContentClassification.kindOf(mimeType),
        sizeBytes: Long = 4_096L,
    ): VaultViewerItem = VaultViewerItem(
        id = VaultItemId("00112233445566778899aabbccddeeff"),
        name = name,
        mimeType = mimeType,
        kind = kind,
        sizeBytes = sizeBytes,
        importedAtEpochMillis = 1_700_000_000_000L,
    )

    private fun bitmap(width: Int = 40, height: Int = 30): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    // ------------------------------------------------------------------ what an item looks like

    @Test
    fun an_opened_file_is_named_with_its_type_and_size() {
        rule.setContent { content(VaultViewerUiState.Image(item(), 1, 1, sampled = false), bitmap()) }

        rule.onNodeWithText("holiday.jpg").assertIsDisplayed()
        // The header draws the type and the size in one sentence, so the type is looked for inside a
        // node rather than as the whole of one.
        rule.onNodeWithText(string(R.string.vault_item_type_image), substring = true).assertIsDisplayed()
    }

    @Test
    fun an_image_is_drawn_with_a_description_naming_the_file() {
        rule.setContent { content(VaultViewerUiState.Image(item(), 40, 30, sampled = false), bitmap()) }

        rule.onNodeWithContentDescription(string(R.string.vault_viewer_image_description, "holiday.jpg"))
            .assertIsDisplayed()
    }

    @Test
    fun a_picture_that_was_reduced_says_so() {
        rule.setContent { content(VaultViewerUiState.Image(item(), 40, 30, sampled = true), bitmap()) }

        rule.onNodeWithText(string(R.string.vault_viewer_image_scaled)).assertIsDisplayed()
    }

    @Test
    fun an_image_that_has_not_arrived_yet_is_shown_as_opening() {
        rule.setContent { content(VaultViewerUiState.Image(item(), 40, 30, sampled = false), null) }

        rule.onNodeWithText(string(R.string.vault_viewer_opening)).assertIsDisplayed()
    }

    // ------------------------------------------------------------------ states that explain themselves

    @Test
    fun a_file_with_no_viewer_is_described_and_offers_no_controls() {
        val target = item(name = "scan.tiff", mimeType = "image/tiff")
        rule.setContent { content(VaultViewerUiState.Unsupported(target), null) }

        rule.onNodeWithText(string(R.string.vault_viewer_unsupported_title)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_viewer_unsupported_body, "image/tiff")).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.state_retry_action)).assertDoesNotExist()
        rule.onNodeWithText(string(R.string.vault_viewer_play)).assertDoesNotExist()
        // The entry itself is still named: the file is in the vault, it simply has no viewer.
        rule.onNodeWithText("scan.tiff").assertIsDisplayed()
    }

    @Test
    fun a_missing_object_says_the_entry_is_still_kept() {
        rule.setContent { content(VaultViewerUiState.Missing(item()), null) }

        rule.onNodeWithText(string(R.string.vault_viewer_missing_title)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_viewer_missing_body)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.state_retry_action)).assertDoesNotExist()
    }

    @Test
    fun content_that_did_not_authenticate_says_nothing_of_it_was_shown() {
        rule.setContent { content(VaultViewerUiState.Corrupt(item()), null) }

        rule.onNodeWithText(string(R.string.vault_viewer_corrupt_title)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_viewer_corrupt_body)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.state_retry_action)).assertDoesNotExist()
    }

    @Test
    fun unreadable_storage_offers_to_try_again() {
        var retries = 0
        rule.setContent {
            content(VaultViewerUiState.Unreadable(item()), null, onRetry = { retries++ })
        }

        rule.onNodeWithText(string(R.string.vault_viewer_unreadable_title)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.state_retry_action)).performClick()

        assertEquals(1, retries)
    }

    @Test
    fun a_locked_viewer_points_at_the_existing_unlock() {
        var unlocks = 0
        var retries = 0
        rule.setContent {
            content(
                VaultViewerUiState.Locked(item()),
                null,
                onUnlock = { unlocks++ },
                onRetry = { retries++ },
            )
        }

        rule.onNodeWithText(string(R.string.vault_viewer_locked_title)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_unlock_action)).performClick()

        assertEquals("the viewer never authenticates anything itself", 1, unlocks)
        assertEquals("and never retries a locked read", 0, retries)
    }

    @Test
    fun the_back_action_closes_the_viewer() {
        var closes = 0
        rule.setContent { content(VaultViewerUiState.Image(item(), 1, 1, sampled = false), bitmap(), onClose = { closes++ }) }

        rule.onNodeWithText(string(R.string.vault_viewer_close_action)).performClick()

        assertEquals(1, closes)
    }

    @Test
    fun a_closed_viewer_shows_nothing_but_the_way_back() {
        rule.setContent { content(VaultViewerUiState.Closed, null) }

        rule.onNodeWithText(string(R.string.vault_viewer_opening)).assertDoesNotExist()
        rule.onNodeWithText("holiday.jpg").assertDoesNotExist()
        rule.onNodeWithText(string(R.string.vault_viewer_close_action)).assertIsDisplayed()
    }

    // ------------------------------------------------------------------ text and documents

    @Test
    fun a_text_file_is_shown_with_the_fact_that_it_is_a_beginning() {
        val target = item(name = "note.txt", mimeType = "text/plain")
        rule.setContent {
            content(
                VaultViewerUiState.Text(target, characters = 40_000, truncated = true),
                null,
                text = "the beginning of the file",
            )
        }

        rule.onNodeWithText(string(R.string.vault_viewer_text_characters_format, 40_000)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_viewer_text_truncated)).assertIsDisplayed()
        rule.onNodeWithText("the beginning of the file").assertIsDisplayed()
    }

    @Test
    fun a_document_shows_its_page_and_moves_through_them() {
        val target = item(name = "report.pdf", mimeType = "application/pdf")
        var nexts = 0
        var previous = 0
        rule.setContent {
            content(
                VaultViewerUiState.Document(target, page = 0, pageCount = 3),
                null,
                onNextPage = { nexts++ },
                onPreviousPage = { previous++ },
            )
        }

        rule.onNodeWithText(string(R.string.vault_viewer_document_page_format, 1, 3)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_viewer_previous_page)).assertIsNotEnabled()
        rule.onNodeWithText(string(R.string.vault_viewer_next_page)).assertIsEnabled().performClick()

        assertEquals(1, nexts)
        assertEquals("the first page has nothing before it", 0, previous)
    }

    @Test
    fun the_last_page_offers_nothing_after_it() {
        val target = item(name = "report.pdf", mimeType = "application/pdf")
        rule.setContent {
            content(VaultViewerUiState.Document(target, page = 2, pageCount = 3), null)
        }

        rule.onNodeWithText(string(R.string.vault_viewer_document_page_format, 3, 3)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_viewer_next_page)).assertIsNotEnabled()
        rule.onNodeWithText(string(R.string.vault_viewer_previous_page)).assertIsEnabled()
    }

    // ------------------------------------------------------------------ playback controls

    @Test
    fun playback_offers_play_and_reports_it() {
        val target = item(name = "clip.mp4", mimeType = "video/mp4")
        var plays = 0
        var pauses = 0
        rule.setContent {
            content(
                VaultViewerUiState.Video(target, playing = false, positionMillis = 0L, durationMillis = 30_000L),
                null,
                onPlay = { plays++ },
                onPause = { pauses++ },
            )
        }

        rule.onNodeWithText(string(R.string.vault_viewer_position_format, "0:00", "0:30")).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_viewer_play)).performClick()

        assertEquals(1, plays)
        assertEquals(0, pauses)
    }

    @Test
    fun a_playing_file_offers_pause_instead() {
        val target = item(name = "clip.mp4", mimeType = "video/mp4")
        var pauses = 0
        rule.setContent {
            content(
                VaultViewerUiState.Video(target, playing = true, positionMillis = 5_000L, durationMillis = 30_000L),
                null,
                onPause = { pauses++ },
            )
        }

        rule.onNodeWithText(string(R.string.vault_viewer_position_format, "0:05", "0:30")).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.vault_viewer_pause)).performClick()

        assertEquals(1, pauses)
    }

    @Test
    fun audio_is_played_without_a_video_surface() {
        val target = item(name = "song.mp3", mimeType = "audio/mpeg")
        rule.setContent {
            content(
                VaultViewerUiState.Audio(target, playing = false, positionMillis = 0L, durationMillis = 12_000L),
                null,
            )
        }

        rule.onNodeWithText(string(R.string.vault_viewer_play)).assertIsDisplayed()
        rule.onNodeWithContentDescription(string(R.string.vault_viewer_video_description, "song.mp3"))
            .assertDoesNotExist()
    }

    @Test
    fun a_file_whose_length_is_unknown_shows_a_position_without_inventing_one() {
        val target = item(name = "clip.mp4", mimeType = "video/mp4")
        rule.setContent {
            content(
                VaultViewerUiState.Video(target, playing = true, positionMillis = 3_000L, durationMillis = 0L),
                null,
            )
        }

        rule.onNodeWithText(string(R.string.vault_viewer_position_unknown_format, "0:03")).assertIsDisplayed()
        rule.onNodeWithContentDescription(string(R.string.vault_viewer_seek_description)).assertDoesNotExist()
    }

    // ------------------------------------------------------------------ helpers

    @Composable
    private fun content(
        state: VaultViewerUiState,
        image: Bitmap?,
        text: String? = null,
        page: Bitmap? = null,
        onPlay: () -> Unit = {},
        onPause: () -> Unit = {},
        onSeekTo: (Long) -> Unit = {},
        onNextPage: () -> Unit = {},
        onPreviousPage: () -> Unit = {},
        onSurfaceAvailable: (android.view.Surface) -> Unit = {},
        onSurfaceDestroyed: () -> Unit = {},
        onRetry: () -> Unit = {},
        onUnlock: () -> Unit = {},
        onClose: () -> Unit = {},
    ) {
        NivaraTheme {
            VaultItemViewerScreen(
                state = state,
                image = image,
                text = text,
                documentPage = page,
                onPlay = onPlay,
                onPause = onPause,
                onSeekTo = onSeekTo,
                onNextPage = onNextPage,
                onPreviousPage = onPreviousPage,
                onSurfaceAvailable = onSurfaceAvailable,
                onSurfaceDestroyed = onSurfaceDestroyed,
                onRetry = onRetry,
                onUnlock = onUnlock,
                onClose = onClose,
            )
        }
    }
}
