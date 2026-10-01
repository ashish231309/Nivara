package com.nivara.app.ui.vault.viewer

import android.graphics.Bitmap
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.text.format.Formatter
import com.nivara.app.R

/**
 * The viewer: one imported file, drawn from the vault, with a way back.
 *
 * ### What it is given, and what it is not
 *
 * A state, a decoded image, a bounded text preview, a rendered page and callbacks — and no key, no
 * cipher, no stream and no storage handle anywhere in the signature. The decoded content arrives as a
 * parameter owned by the view model, so this composable cannot keep it and cannot outlive it: when the
 * state stops saying "there is an image", there is no bitmap to draw.
 *
 * ### What it refuses to blur together
 *
 * A type with no viewer, content that is not in the vault, storage that refused, bytes that did not
 * authenticate and a decoder that could not handle a file are five different screens. Each says what
 * happened and what it does not mean, which is the only way "this file cannot be shown" does not
 * become "this file is broken".
 */
@Composable
internal fun VaultItemViewerScreen(
    state: VaultViewerUiState,
    image: Bitmap?,
    text: String?,
    documentPage: Bitmap?,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onNextPage: () -> Unit,
    onPreviousPage: () -> Unit,
    onSurfaceAvailable: (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onRetry: () -> Unit,
    onUnlock: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.item?.let { shown -> ViewerHeader(item = shown) }

        when (state) {
            VaultViewerUiState.Closed -> Unit

            is VaultViewerUiState.Opening -> Text(
                text = stringResource(id = R.string.vault_viewer_opening),
                style = MaterialTheme.typography.bodyMedium,
            )

            is VaultViewerUiState.Image -> ImageViewer(state = state, image = image)

            is VaultViewerUiState.Video -> VideoViewer(
                state = state,
                onPlay = onPlay,
                onPause = onPause,
                onSeekTo = onSeekTo,
                onSurfaceAvailable = onSurfaceAvailable,
                onSurfaceDestroyed = onSurfaceDestroyed,
            )

            is VaultViewerUiState.Audio -> PlaybackCard(
                item = state.item,
                playing = state.playing,
                positionMillis = state.positionMillis,
                durationMillis = state.durationMillis,
                onPlay = onPlay,
                onPause = onPause,
                onSeekTo = onSeekTo,
            )

            is VaultViewerUiState.Text -> TextViewer(state = state, text = text)

            is VaultViewerUiState.Document -> DocumentViewer(
                state = state,
                page = documentPage,
                onNextPage = onNextPage,
                onPreviousPage = onPreviousPage,
            )

            else -> ViewerExplanation(state = state, onRetry = onRetry, onUnlock = onUnlock)
        }

        Button(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(id = R.string.vault_viewer_close_action))
        }
    }
}

/** The file's name and its facts: never its identifier, never where it came from. */
@Composable
private fun ViewerHeader(item: VaultViewerItem, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(text = item.name, style = MaterialTheme.typography.titleMedium)
        Text(
            text = stringResource(
                id = R.string.vault_item_details_format,
                stringResource(id = vaultViewerTypeRes(item)),
                Formatter.formatShortFileSize(context, item.sizeBytes),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * The explanation for a state that has nothing to draw.
 *
 * The body is passed the file's declared type for the one case that can name it, because "there is no
 * viewer for image/tiff" is a sentence a person can act on and "unsupported" is not.
 */
@Composable
private fun ViewerExplanation(
    state: VaultViewerUiState,
    onRetry: () -> Unit,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bodyRes = state.bodyRes() ?: return
    val type = state.item?.mimeType ?: stringResource(id = R.string.vault_item_type_other)

    NivaraExplanationCard(
        titleRes = state.titleRes(),
        body = if (bodyRes == R.string.vault_viewer_unsupported_body) {
            stringResource(id = bodyRes, type)
        } else {
            stringResource(id = bodyRes)
        },
        modifier = modifier,
    ) {
        if (state.needsUnlock()) {
            Button(onClick = onUnlock, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(id = R.string.vault_unlock_action))
            }
        }
        if (state.canRetry()) {
            OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(id = R.string.state_retry_action))
            }
        }
    }
}

/** A titled card of explanation, in the shape the rest of the vault's screens use. */
@Composable
private fun NivaraExplanationCard(
    titleRes: Int?,
    body: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            titleRes?.let { title ->
                Text(text = stringResource(id = title), style = MaterialTheme.typography.titleMedium)
            }
            Text(text = body, style = MaterialTheme.typography.bodyMedium)
            actions()
        }
    }
}

/**
 * The picture, fitted to the screen and bounded by the decode.
 *
 * A double tap toggles between fitting the screen and twice that, which is as much zoom as a viewer
 * without panning can offer honestly. Nothing here re-reads the vault: the bitmap was decoded inside
 * the engine's bound before it arrived.
 */
@Composable
private fun ImageViewer(
    state: VaultViewerUiState.Image,
    image: Bitmap?,
    modifier: Modifier = Modifier,
) {
    var zoomed by remember(image) { mutableStateOf(false) }
    val description = stringResource(id = R.string.vault_viewer_image_description, state.item.name)

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (image == null) {
                Text(
                    text = stringResource(id = R.string.vault_viewer_opening),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                val scale = if (zoomed) ZOOM_FACTOR else 1f
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = MAXIMUM_IMAGE_HEIGHT)
                        .clipToBounds()
                        .pointerInput(image) {
                            detectTapGestures(onDoubleTap = { zoomed = !zoomed })
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = image.asImageBitmap(),
                        contentDescription = description,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(state.width.toFloat() / state.height.coerceAtLeast(1))
                            .graphicsLayer(scaleX = scale, scaleY = scale),
                    )
                }
                Text(
                    text = stringResource(id = R.string.vault_viewer_zoom_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (state.sampled) {
                    // Said plainly for the same reason a file's missing content is: a picture that
                    // looks softer than the original should not be a mystery.
                    Text(
                        text = stringResource(id = R.string.vault_viewer_image_scaled),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/**
 * The video, drawn into a surface the platform owns and played by the engine over the vault's stream.
 *
 * The surface's lifecycle belongs to this composable: it is handed to the engine when it exists and
 * withdrawn when it is destroyed, so nothing outside the screen holds a drawing surface.
 */
@Composable
private fun VideoViewer(
    state: VaultViewerUiState.Video,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSurfaceAvailable: (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(id = R.string.vault_viewer_video_description, state.item.name)
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AndroidView(
                factory = { context ->
                    SurfaceView(context).apply {
                        holder.addCallback(
                            object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    holder.surface?.let(onSurfaceAvailable)
                                }

                                override fun surfaceChanged(
                                    holder: SurfaceHolder,
                                    format: Int,
                                    width: Int,
                                    height: Int,
                                ) = Unit

                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    onSurfaceDestroyed()
                                }
                            },
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(VIDEO_ASPECT_RATIO)
                    .semantics { contentDescription = description },
            )
            PlaybackCard(
                item = state.item,
                playing = state.playing,
                positionMillis = state.positionMillis,
                durationMillis = state.durationMillis,
                onPlay = onPlay,
                onPause = onPause,
                onSeekTo = onSeekTo,
            )
        }
    }
}

/** Play or pause, the position, and a way to move it. */
@Composable
private fun PlaybackCard(
    item: VaultViewerItem,
    playing: Boolean,
    positionMillis: Long,
    durationMillis: Long,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val seekDescription = stringResource(id = R.string.vault_viewer_seek_description)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (durationMillis > 0L) {
                stringResource(
                    id = R.string.vault_viewer_position_format,
                    formatPlaybackTime(positionMillis),
                    formatPlaybackTime(durationMillis),
                )
            } else {
                // A file whose length the player cannot report is shown as a position with no total,
                // rather than as a progress bar that would be inventing one.
                stringResource(
                    id = R.string.vault_viewer_position_unknown_format,
                    formatPlaybackTime(positionMillis),
                )
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (durationMillis > 0L) {
            var dragged by remember(item.id, durationMillis) { mutableFloatStateOf(0f) }
            var dragging by remember(item.id, durationMillis) { mutableStateOf(false) }
            Slider(
                value = if (dragging) {
                    dragged
                } else {
                    (positionMillis.toFloat() / durationMillis.toFloat()).coerceIn(0f, 1f)
                },
                onValueChange = { value ->
                    dragging = true
                    dragged = value
                },
                onValueChangeFinished = {
                    dragging = false
                    onSeekTo((dragged * durationMillis).toLong())
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = seekDescription },
            )
        }
        Button(
            onClick = if (playing) onPause else onPlay,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(
                    id = if (playing) R.string.vault_viewer_pause else R.string.vault_viewer_play,
                ),
            )
        }
    }
}

/** The beginning of a text file, bounded by the read, with the fact that it is a beginning. */
@Composable
private fun TextViewer(
    state: VaultViewerUiState.Text,
    text: String?,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(
                    id = R.string.vault_viewer_text_characters_format,
                    state.characters,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.truncated) {
                Text(
                    text = stringResource(id = R.string.vault_viewer_text_truncated),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = text.orEmpty(),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}

/** One rendered page, with the pages either side of it a tap away. */
@Composable
private fun DocumentViewer(
    state: VaultViewerUiState.Document,
    page: Bitmap?,
    onNextPage: () -> Unit,
    onPreviousPage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pageLabel = stringResource(
        id = R.string.vault_viewer_document_page_format,
        state.page + 1,
        state.pageCount,
    )
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = pageLabel, style = MaterialTheme.typography.bodyMedium)
            page?.let { rendered ->
                Image(
                    bitmap = rendered.asImageBitmap(),
                    contentDescription = pageLabel,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onPreviousPage, enabled = state.page > 0) {
                    Text(text = stringResource(id = R.string.vault_viewer_previous_page))
                }
                TextButton(
                    onClick = onNextPage,
                    enabled = state.page + 1 < state.pageCount,
                ) {
                    Text(text = stringResource(id = R.string.vault_viewer_next_page))
                }
            }
        }
    }
}

/** How far a double tap zooms. As much as a viewer without panning can offer honestly. */
private const val ZOOM_FACTOR = 2f

/** The largest a picture is drawn before it is scrolled, in dp. */
private val MAXIMUM_IMAGE_HEIGHT = 480.dp

/** A video surface's shape before the player reports one. A common aspect, not a claim about a file. */
private const val VIDEO_ASPECT_RATIO = 16f / 9f
