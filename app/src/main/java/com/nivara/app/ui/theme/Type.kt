package com.nivara.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight

/**
 * Typography for Nivara.
 *
 * Material 3 defaults are kept, with a small adjustment so screen titles and the brand name
 * carry a little more weight. A full type scale is part of the UI stage.
 */
internal val NivaraTypography = Typography().let { base ->
    base.copy(
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}
