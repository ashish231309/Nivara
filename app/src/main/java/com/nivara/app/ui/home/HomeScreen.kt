package com.nivara.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nivara.app.R
import com.nivara.app.ui.components.NivaraErrorState
import com.nivara.app.ui.components.NivaraLoadingState
import com.nivara.app.ui.theme.NivaraTheme

/**
 * Stateful entry point of the home screen: creates the view model and observes its state.
 */
@Composable
fun HomeRoute(
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    HomeScreen(
        uiState = uiState,
        onRetry = viewModel::refresh,
        onOpenAbout = onOpenAbout,
        modifier = modifier,
    )
}

/**
 * Stateless home screen: renders [HomeUiState] and reports user actions upwards.
 */
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onRetry: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (uiState) {
        HomeUiState.Loading -> NivaraLoadingState(modifier = modifier)
        HomeUiState.Error -> NivaraErrorState(onRetry = onRetry, modifier = modifier)
        is HomeUiState.Ready -> HomeContent(
            deviceLockConfigured = uiState.deviceLockConfigured,
            onOpenAbout = onOpenAbout,
            modifier = modifier,
        )
    }
}

@Composable
private fun HomeContent(
    deviceLockConfigured: Boolean,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(id = R.string.home_tagline),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(id = R.string.home_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        DeviceSecurityCard(deviceLockConfigured = deviceLockConfigured)

        InfoCard(
            title = stringResource(id = R.string.home_foundation_title),
            body = stringResource(id = R.string.home_foundation_summary),
        )

        Button(onClick = onOpenAbout) {
            Text(text = stringResource(id = R.string.home_about_action))
        }
    }
}

@Composable
private fun DeviceSecurityCard(
    deviceLockConfigured: Boolean,
    modifier: Modifier = Modifier,
) {
    val statusRes = if (deviceLockConfigured) {
        R.string.home_status_lock_configured
    } else {
        R.string.home_status_lock_missing
    }
    val summaryRes = if (deviceLockConfigured) {
        R.string.home_status_lock_configured_summary
    } else {
        R.string.home_status_lock_missing_summary
    }

    InfoCard(
        title = stringResource(id = R.string.home_status_title),
        body = "${stringResource(id = statusRes)} — ${stringResource(id = summaryRes)}",
        modifier = modifier,
    )
}

@Composable
private fun InfoCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
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
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(text = body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Preview(name = "Home – screen lock set", showBackground = true)
@Composable
private fun HomeScreenReadyPreview() {
    NivaraTheme {
        HomeScreen(uiState = HomeUiState.Ready(deviceLockConfigured = true), onRetry = {}, onOpenAbout = {})
    }
}

@Preview(name = "Home – no screen lock", showBackground = true)
@Composable
private fun HomeScreenNoLockPreview() {
    NivaraTheme {
        HomeScreen(uiState = HomeUiState.Ready(deviceLockConfigured = false), onRetry = {}, onOpenAbout = {})
    }
}

@Preview(name = "Home – error", showBackground = true)
@Composable
private fun HomeScreenErrorPreview() {
    NivaraTheme {
        HomeScreen(uiState = HomeUiState.Error, onRetry = {}, onOpenAbout = {})
    }
}
