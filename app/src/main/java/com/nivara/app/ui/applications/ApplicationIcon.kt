package com.nivara.app.ui.applications

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.painterResource
import com.nivara.app.R
import com.nivara.app.ui.components.NivaraSize

/**
 * The icon a row draws for an application, or a neutral placeholder when the device could not
 * produce one.
 *
 * The icon is decoration: the application's label sits beside it and is what identifies the row, so
 * the image carries no content description of its own — a screen reader reads the label once rather
 * than announcing a picture, and the row stays intelligible without any image at all.
 *
 * One composable rather than one per screen, so every list of applications shows the same
 * placeholder: an icon this device cannot produce must not look like a missing application.
 */
@Composable
fun NivaraApplicationIcon(
    icon: ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    // Drawn slightly smaller than it is loaded, so the bitmap is never scaled up and never blurry.
    val iconSize = Modifier.size(NivaraSize.rowIcon)
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = null,
            modifier = modifier.then(iconSize),
        )
    } else {
        Image(
            painter = painterResource(id = R.drawable.ic_app_placeholder),
            contentDescription = null,
            modifier = modifier.then(iconSize),
        )
    }
}
