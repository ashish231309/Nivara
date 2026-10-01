package com.nivara.app.ui.applications

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Supplies the image an application row draws, or `null` when the device cannot produce one.
 *
 * ### Why this is a seam
 *
 * An application's icon is presentation and nothing else: it is never an identity, it is never
 * stored and it is never compared. Only the platform can produce it — that is a `PackageManager`
 * question — and the presentation layer is not allowed to ask the package manager anything, which
 * the repository's static checks enforce. So the presentation layer declares what it needs, in the
 * type the presentation layer uses, and the platform layer implements it. That keeps the direction
 * of the dependency honest: composing a row knows it wants a small bitmap, and knows nothing about
 * where the bitmap comes from.
 *
 * A `null` answer is ordinary rather than exceptional: an application can be uninstalled between
 * discovery and drawing, an icon resource can be missing, and a device can refuse the query. The
 * row draws its fallback and carries on; nothing about protection or hiding depends on this call,
 * and no failure here is allowed to look like a failure of either.
 */
fun interface ApplicationIconLoader {

    /** The icon for [packageName], or `null` when none can be produced. Never throws. */
    suspend fun iconFor(packageName: String): ImageBitmap?
}
