package com.nivara.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.nivara.app.R
import com.nivara.app.ui.theme.NivaraTheme

/**
 * Loading state shown while a screen waits for its first result.
 *
 * Kept free of screen-specific knowledge so every screen reports progress the same way. It is
 * only for first results: a screen that already has content shows its content and reports work
 * in progress in place, never by replacing what the user was looking at with a spinner.
 */
@Composable
fun NivaraLoadingState(
    modifier: Modifier = Modifier,
    message: String = stringResource(id = R.string.state_loading),
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(NivaraSpacing.screen),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = NivaraSpacing.screen),
        )
    }
}

/**
 * Error state with a single recovery action.
 *
 * The failure reason is intentionally not displayed: user-facing messages stay generic, and
 * technical details never leave the process. A screen whose states carry their own words — the
 * vault, recovery, the application lists — draws those words itself and never reaches for this;
 * this is the surface for the rare place where nothing more specific can be said.
 */
@Composable
fun NivaraErrorState(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(NivaraSpacing.screen),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(id = R.string.state_error_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(id = R.string.state_error_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = NivaraSpacing.small),
        )
        Button(
            onClick = onRetry,
            modifier = Modifier.padding(top = NivaraSpacing.section),
        ) {
            Text(text = stringResource(id = R.string.state_retry_action))
        }
    }
}

/**
 * The empty state of a screen: nothing to show, and exactly why.
 *
 * Every list that can be empty in Nivara is empty for its own reason — the device has nothing,
 * the search matched nothing, the collection has nothing yet, or the record could not be read —
 * and those reasons must never be merged into one generic message. This view draws whichever
 * words the screen chose for its state; it is presentation only and decides nothing itself.
 */
@Composable
fun NivaraEmptyState(
    message: String,
    modifier: Modifier = Modifier,
    title: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(NivaraSpacing.screen),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = if (title != null) NivaraSpacing.small else NivaraSpacing.tight),
        )
    }
}

@Preview(name = "Loading", showBackground = true)
@Composable
private fun NivaraLoadingStatePreview() {
    NivaraTheme { NivaraLoadingState() }
}

@Preview(name = "Error", showBackground = true)
@Composable
private fun NivaraErrorStatePreview() {
    NivaraTheme { NivaraErrorState(onRetry = {}) }
}

@Preview(name = "Empty", showBackground = true)
@Composable
private fun NivaraEmptyStatePreview() {
    NivaraTheme { NivaraEmptyState(message = "Nothing here yet.") }
}
