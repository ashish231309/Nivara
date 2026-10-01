package com.nivara.app.data.app

import android.content.Context
import android.util.TypedValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.nivara.app.core.common.nivaraRunCatching
import com.nivara.app.core.common.valueOrNull
import com.nivara.app.ui.applock.management.ApplicationIconLoader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [ApplicationIconLoader] built on the platform package manager.
 *
 * ### What it does
 *
 * Reads the application record for the package and renders its icon into a bitmap the size a list
 * row needs. The icon is scaled at load time rather than stored at full size: launcher icons can be
 * adaptive, several hundred pixels across and expensive to keep, and a row needs about forty-eight
 * density-independent points.
 *
 * ### What it deliberately does not do
 *
 * It writes nothing, anywhere. Nothing about an icon is persisted, copied into the application's
 * storage or sent anywhere, and the memory cache below lives only as long as the process: an icon
 * is a picture of something the device already holds, and keeping a copy would be a copy of another
 * application's artwork for no benefit. It also never throws: a package that disappeared, a broken
 * icon resource or a platform refusal all come back as `null`, because a missing picture must never
 * look like a missing protection.
 *
 * ### Why the cache exists
 *
 * A list re-composes as the user types in the search field or scrolls, and the platform's own icon
 * lookup is not free. The cache is keyed by package name, bounded by the number of installed
 * applications, and cleared with the process. It is a rendering detail, not state: nothing in the
 * application reads it, and removing it would change nothing except speed.
 */
internal class AndroidApplicationIconLoader(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ApplicationIconLoader {

    /** Icons already produced in this process. `null` is cached too — a broken icon stays broken. */
    private val icons = mutableMapOf<String, ImageBitmap?>()

    override suspend fun iconFor(packageName: String): ImageBitmap? {
        synchronized(icons) {
            if (icons.containsKey(packageName)) return icons[packageName]
        }

        val icon = withContext(dispatcher) { load(packageName) }

        synchronized(icons) { icons[packageName] = icon }
        return icon
    }

    /** The rendered icon, or `null` for every kind of failure. */
    private fun load(packageName: String): ImageBitmap? {
        val packageManager = context.packageManager
        val size = iconSizePixels()
        return nivaraRunCatching {
            packageManager.applicationInfo(packageName)
                .loadIcon(packageManager)
                .toBitmap(width = size, height = size)
                .asImageBitmap()
        }.valueOrNull()
    }

    /** The pixel size a row's icon is rendered at: 48dp, converted for this display. */
    private fun iconSizePixels(): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        ICON_SIZE_DP.toFloat(),
        context.resources.displayMetrics,
    ).toInt()

    private companion object {
        /** Material's standard list-icon size, in density-independent points. */
        const val ICON_SIZE_DP = 48
    }
}
