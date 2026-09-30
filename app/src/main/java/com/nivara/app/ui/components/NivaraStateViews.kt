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
import androidx.compose.ui.unit.dp
import com.nivara.app.R
import com.nivara.app.ui.theme.NivaraTheme

/**
 * Loading state shown while a screen waits for its first result.
 *
 * Kept free of screen-specific knowledge so every future screen reports progress the same way.
 */
@Composable
fun NivaraLoadingState(
    modifier: Modifier = Modifier,
    message: String = stringResource(id = R.string.state_loading),
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

/**
 * Error state with a single recovery action.
 *
 * The failure reason is intentionally not displayed: user-facing messages stay generic, and
 * technical details never leave the process. Screens that need richer messaging will provide
 * their own copy in the stage that introduces them.
 */
@Composable
fun NivaraErrorState(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
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
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(
            onClick = onRetry,
            modifier = Modifier.padding(top = 24.dp),
        ) {
            Text(text = stringResource(id = R.string.state_retry_action))
        }
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
