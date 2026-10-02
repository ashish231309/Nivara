package com.nivara.app.data.vault.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.nivara.app.core.common.NivaraResult
import com.nivara.app.data.vault.VaultContentInputStream
import com.nivara.app.domain.vault.VaultContentHandle
import com.nivara.app.domain.vault.VaultContentReader
import com.nivara.app.domain.vault.VaultItemId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * How large a decoded image may be.
 *
 * A bitmap is the one place where a viewer's memory is inherently proportional to the content, so the
 * bound is explicit rather than hoped for: a picture is decoded to fit a screen, not to its original
 * size. It also keeps a hostile file from asking for an allocation it cannot have — a decoded image
 * costing a gigabyte is a denial of service, not a photograph.
 */
internal object VaultImageBounded {

    /** The longest side of a decoded image, in pixels. Beyond this a screen cannot show more. */
    const val MAXIMUM_DIMENSION: Int = 2_048

    /** How many pixels a decoded image may hold, which bounds the bytes behind it. */
    const val MAXIMUM_PIXELS: Int = 4_000_000

    /** The most a decoder may be asked to consider: beyond this the file is refused, not decoded. */
    const val MAXIMUM_DECLARED_DIMENSION: Int = 32_768

    /** Whether a decoder's declared dimensions are a picture at all. */
    fun isDecodable(width: Int, height: Int): Boolean =
        width in 1..MAXIMUM_DECLARED_DIMENSION && height in 1..MAXIMUM_DECLARED_DIMENSION

    /**
     * The power-of-two sampling an image of [width] by [height] is decoded with.
     *
     * Computed rather than guessed: the sample is doubled while either the longest side or the pixel
     * count is still over the bound, and it never exceeds what the declared dimensions can take. The
     * result is what the platform's decoder is asked for, so the memory a decode costs is decided
     * before the decode happens.
     */
    fun sampleSize(
        width: Int,
        height: Int,
        maximumDimension: Int = MAXIMUM_DIMENSION,
        maximumPixels: Int = MAXIMUM_PIXELS,
    ): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (sample < MAXIMUM_SAMPLE) {
            val scaledWidth = max(width / sample, 1)
            val scaledHeight = max(height / sample, 1)
            val withinDimension = scaledWidth <= maximumDimension && scaledHeight <= maximumDimension
            val withinPixels = scaledWidth.toLong() * scaledHeight.toLong() <= maximumPixels.toLong()
            if (withinDimension && withinPixels) break
            sample *= 2
        }
        return sample
    }

    /** The largest sampling the loop considers, which bounds an absurd image's work. */
    const val MAXIMUM_SAMPLE: Int = 1 shl 12
}

/** One decoded image, with the facts the screen needs to draw it correctly. */
internal data class VaultDecodedImage(
    val bitmap: Bitmap,
    val width: Int,
    val height: Int,
    val sampled: Boolean,
)

/**
 * Decodes one vault image inside the memory bound.
 *
 * ### Two passes, one decryption each
 *
 * The decoder is asked for its dimensions first — the header is all that read needs — and then the
 * item is restarted and decoded with a sampling chosen from those dimensions. Both passes read
 * through the vault's content handle, so the decrypted bytes are never a file on disk and never a
 * whole `ByteArray` in memory, and a pass that is abandoned does not keep its decryption running.
 *
 * ### Failure means failure
 *
 * A file the decoder refuses is [VaultViewerFailure.DecodeFailed], never a damaged vault: the bytes
 * authenticated (that happened before the decoder saw them) and simply are not a picture this device
 * can draw. A file whose bytes did *not* authenticate never reaches a decoder at all.
 */
internal interface VaultImageEngine {

    /** Decodes [itemId], or reports why it could not be shown. */
    suspend fun decode(
        itemId: VaultItemId,
        sizeBytes: Long,
        authorize: () -> Boolean,
    ): NivaraResult<VaultDecodedImage>

    /** Releases the last decoded image. Safe to call more than once. */
    fun release()
}

/** The platform's decoder, reading through the vault's content handle. */
internal class NivaraVaultImageEngine(
    private val reader: VaultContentReader,
) : VaultImageEngine {

    private var decoded: Bitmap? = null

    override suspend fun decode(
        itemId: VaultItemId,
        sizeBytes: Long,
        authorize: () -> Boolean,
    ): NivaraResult<VaultDecodedImage> =
        reader.withContent(itemId, sizeBytes, authorize) { handle ->
            val outcome = decodeWithHandle(handle)
            outcome
        }

    private suspend fun decodeWithHandle(
        handle: VaultContentHandle,
    ): NivaraResult<VaultDecodedImage> = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            BitmapFactory.decodeStream(VaultContentInputStream(handle), null, bounds)
        } catch (unreadable: Exception) {
            // A read failure is a read failure, not a decoder's opinion: it travels as itself.
            return@withContext contentFailure(unreadable.asViewerContentFailure())
        }
        if (!VaultImageBounded.isDecodable(bounds.outWidth, bounds.outHeight)) {
            return@withContext viewerFailure(VaultViewerFailure.DecodeFailed)
        }

        val sample = VaultImageBounded.sampleSize(bounds.outWidth, bounds.outHeight)
        handle.restart()
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = try {
            BitmapFactory.decodeStream(VaultContentInputStream(handle), null, options)
        } catch (unreadable: Exception) {
            return@withContext contentFailure(unreadable.asViewerContentFailure())
        } ?: return@withContext viewerFailure(VaultViewerFailure.DecodeFailed)

        release()
        decoded = bitmap
        NivaraResult.Success(
            VaultDecodedImage(
                bitmap = bitmap,
                width = bitmap.width,
                height = bitmap.height,
                sampled = sample > 1,
            ),
        )
    }

    override fun release() {
        val previous = decoded ?: return
        decoded = null
        if (!previous.isRecycled) previous.recycle()
    }
}
