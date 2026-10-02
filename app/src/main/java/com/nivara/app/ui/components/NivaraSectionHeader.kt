package com.nivara.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

/**
 * A section heading inside a screen.
 *
 * One style everywhere a screen divides itself into parts: a title that belongs to the section,
 * and — where the section needs a sentence of context before its rows — a summary underneath in
 * the screen's quieter colour. Spacing comes from the design tokens so two sections can never
 * drift apart from each other.
 */
@Composable
fun NivaraSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = NivaraSpacing.row)
            // A section title announces itself as a heading, so a screen reader's user can jump
            // between the parts of a screen instead of listening to all of it in order.
            .semantics { heading() },
        verticalArrangement = Arrangement.spacedBy(NivaraSpacing.tight),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (summary != null) {
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
