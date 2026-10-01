package com.nivara.app.ui.applock

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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nivara.app.R
import com.nivara.app.domain.app.ApplicationDiscoveryState
import com.nivara.app.domain.app.InstalledApplication
import com.nivara.app.domain.permissions.AppLockSetupState
import com.nivara.app.domain.permissions.UsageAccessStatus
import com.nivara.app.ui.components.NivaraErrorState
import com.nivara.app.ui.components.NivaraLoadingState
import com.nivara.app.ui.components.NivaraMessageText
import com.nivara.app.ui.credential.SecureScreenEffect
import com.nivara.app.ui.theme.NivaraTheme

/** How many applications the preparation screen previews. The App Lock list is a later stage. */
private const val PREVIEW_LIMIT = 5

/**
 * Stateful entry point of the App Lock preparation screen: creates the view model, observes its
 * state and re-reads the capabilities whenever the screen is resumed.
 */
@Composable
fun AppLockSetupRoute(
    modifier: Modifier = Modifier,
    viewModel: AppLockSetupViewModel = viewModel(factory = AppLockSetupViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // The screen lists the device's applications, which Android treats as personal data. The same
    // window flag the credential screens use keeps that list out of screenshots, recordings and the
    // recents thumbnail. There is one implementation of it, and this is not another one.
    SecureScreenEffect()

    // Usage Access is granted in Android's own settings screen, which stops Nivara while it is
    // open. Re-reading on resume is how the screen learns that the answer changed; nothing here
    // assumes it did.
    LifecycleResumeEffect(Unit) {
        viewModel.onResumed()
        onPauseOrDispose { }
    }

    AppLockSetupScreen(
        uiState = uiState,
        onRetry = viewModel::refresh,
        onOpenUsageAccessSettings = viewModel::openUsageAccessSettings,
        modifier = modifier,
    )
}

/**
 * Stateless preparation screen: renders [AppLockSetupUiState] and reports user actions upwards.
 *
 * The screen never grants anything. It reports what Android says, explains why App Lock wants the
 * capability, and offers the one thing it can do: open Android's own settings screen.
 */
@Composable
fun AppLockSetupScreen(
    uiState: AppLockSetupUiState,
    onRetry: () -> Unit,
    onOpenUsageAccessSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (uiState) {
        AppLockSetupUiState.Loading -> NivaraLoadingState(modifier = modifier)
        AppLockSetupUiState.Error -> NivaraErrorState(onRetry = onRetry, modifier = modifier)
        is AppLockSetupUiState.Ready -> AppLockSetupContent(
            state = uiState,
            onRetry = onRetry,
            onOpenUsageAccessSettings = onOpenUsageAccessSettings,
            modifier = modifier,
        )
    }
}

@Composable
private fun AppLockSetupContent(
    state: AppLockSetupUiState.Ready,
    onRetry: () -> Unit,
    onOpenUsageAccessSettings: () -> Unit,
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
            text = stringResource(id = R.string.applock_setup_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ReadinessCard(setup = state.setup)

        DiscoveryCard(discovery = state.setup.discovery, onRetry = onRetry)

        UsageAccessCard(
            status = state.setup.usageAccess,
            busy = state.busy,
            onOpenSettings = onOpenUsageAccessSettings,
        )

        state.noticeRes?.let { noticeRes ->
            Text(
                text = stringResource(id = noticeRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        state.failure?.let { failure -> NivaraMessageText(message = failure) }

        Text(
            text = stringResource(id = R.string.applock_setup_scope_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The summary: what is in place and what is still missing, derived from the same two answers. */
@Composable
private fun ReadinessCard(
    setup: AppLockSetupState,
    modifier: Modifier = Modifier,
) {
    val missingNames = setup.missingPrerequisites.map { prerequisite ->
        stringResource(id = prerequisiteNameRes(prerequisite))
    }
    val body = if (setup.isReady) {
        stringResource(id = R.string.applock_setup_readiness_ready)
    } else {
        stringResource(
            id = R.string.applock_setup_readiness_missing,
            missingNames.joinToString(separator = ", "),
        )
    }

    SetupCard(
        title = stringResource(id = R.string.applock_setup_readiness_title),
        body = body,
        modifier = modifier,
    )
}

/**
 * What discovery found, or the honest statement that it found nothing out.
 *
 * An unavailable query is never drawn as an empty device: it says the list could not be read and
 * offers the retry that can fix it.
 */
@Composable
private fun DiscoveryCard(
    discovery: ApplicationDiscoveryState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (discovery) {
            is ApplicationDiscoveryState.Available -> {
                SetupCard(
                    title = stringResource(id = R.string.applock_setup_discovery_title),
                    body = stringResource(
                        id = R.string.applock_setup_discovery_count,
                        discovery.applications.size,
                    ) + " — " + stringResource(id = R.string.applock_setup_discovery_summary),
                )
                if (discovery.applications.isNotEmpty()) {
                    ApplicationPreview(applications = discovery.applications)
                }
            }

            ApplicationDiscoveryState.Unavailable -> {
                SetupCard(
                    title = stringResource(id = R.string.applock_setup_discovery_title),
                    body = stringResource(id = R.string.applock_setup_discovery_unavailable) + " — " +
                        stringResource(id = R.string.applock_setup_discovery_unavailable_summary),
                )
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(id = R.string.applock_setup_discovery_retry))
                }
            }
        }
    }
}

/**
 * A short preview of the discovered list.
 *
 * This is not the App Lock list: there is no search, no sorting control and no lock state here, and
 * the note under it says so. Only labels are shown — the package name is the identity the code
 * keeps, not something a person needs to read.
 */
@Composable
private fun ApplicationPreview(
    applications: List<InstalledApplication>,
    modifier: Modifier = Modifier,
) {
    val preview = applications.take(PREVIEW_LIMIT)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(id = R.string.applock_setup_discovery_preview_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        preview.forEach { application ->
            Text(
                text = application.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (applications.size > preview.size) {
            Text(
                text = stringResource(
                    id = R.string.applock_setup_discovery_preview_more,
                    applications.size - preview.size,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(id = R.string.applock_setup_discovery_preview_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The Usage Access state and the way to Android's screen for it.
 *
 * The button is offered whatever the state says: it opens Android's screen, and a user who granted
 * the capability may still want to revoke it there. The wording never claims that tapping it grants
 * anything.
 */
@Composable
private fun UsageAccessCard(
    status: UsageAccessStatus,
    busy: Boolean,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SetupCard(
            title = stringResource(id = R.string.applock_setup_usage_access_title),
            body = stringResource(id = usageAccessStatusRes(status)) + " — " +
                stringResource(id = R.string.applock_setup_usage_access_summary),
        )
        Text(
            text = stringResource(id = R.string.applock_setup_usage_access_explanation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onOpenSettings,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(id = R.string.applock_setup_usage_access_action))
        }
        Text(
            text = stringResource(id = R.string.applock_setup_usage_access_settings_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SetupCard(
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

@Preview(name = "App Lock preparation – granted", showBackground = true)
@Composable
private fun AppLockSetupGrantedPreview() {
    NivaraTheme {
        AppLockSetupScreen(
            uiState = AppLockSetupUiState.Ready(
                setup = AppLockSetupState(
                    discovery = ApplicationDiscoveryState.Available(
                        applications = listOf(
                            InstalledApplication(packageName = "com.example.camera", label = "Camera"),
                            InstalledApplication(packageName = "com.example.notes", label = "Notes"),
                        ),
                    ),
                    usageAccess = UsageAccessStatus.Granted,
                ),
            ),
            onRetry = {},
            onOpenUsageAccessSettings = {},
        )
    }
}

@Preview(name = "App Lock preparation – missing prerequisites", showBackground = true)
@Composable
private fun AppLockSetupIncompletePreview() {
    NivaraTheme {
        AppLockSetupScreen(
            uiState = AppLockSetupUiState.Ready(
                setup = AppLockSetupState(
                    discovery = ApplicationDiscoveryState.Unavailable,
                    usageAccess = UsageAccessStatus.NotGranted,
                ),
            ),
            onRetry = {},
            onOpenUsageAccessSettings = {},
        )
    }
}

@Preview(name = "App Lock preparation – loading", showBackground = true)
@Composable
private fun AppLockSetupLoadingPreview() {
    NivaraTheme {
        AppLockSetupScreen(
            uiState = AppLockSetupUiState.Loading,
            onRetry = {},
            onOpenUsageAccessSettings = {},
        )
    }
}
