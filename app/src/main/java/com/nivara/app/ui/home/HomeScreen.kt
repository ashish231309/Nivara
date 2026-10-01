package com.nivara.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.nivara.app.domain.credential.PrimaryCredentialType
import com.nivara.app.domain.security.AuthenticationSource
import com.nivara.app.domain.security.BiometricStatus
import com.nivara.app.domain.security.SessionState
import com.nivara.app.ui.biometric.biometricStatusRes
import com.nivara.app.ui.components.NivaraErrorState
import com.nivara.app.ui.components.NivaraLoadingState
import com.nivara.app.ui.credential.credentialTypeNameRes
import com.nivara.app.ui.session.sessionStatusRes
import com.nivara.app.ui.session.sessionSummaryRes
import com.nivara.app.ui.theme.NivaraTheme

/**
 * Stateful entry point of the home screen: creates the view model and observes its state.
 */
@Composable
fun HomeRoute(
    onOpenAbout: () -> Unit,
    onOpenCredentialSetup: () -> Unit,
    onOpenCredentialVerify: () -> Unit,
    onOpenCredentialChange: () -> Unit,
    onOpenBiometric: () -> Unit,
    onOpenAppLockSetup: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    HomeScreen(
        uiState = uiState,
        onRetry = viewModel::refresh,
        onLockNow = viewModel::lockNow,
        onOpenAbout = onOpenAbout,
        onOpenCredentialSetup = onOpenCredentialSetup,
        onOpenCredentialVerify = onOpenCredentialVerify,
        onOpenCredentialChange = onOpenCredentialChange,
        onOpenBiometric = onOpenBiometric,
        onOpenAppLockSetup = onOpenAppLockSetup,
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
    onLockNow: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenCredentialSetup: () -> Unit,
    onOpenCredentialVerify: () -> Unit,
    onOpenCredentialChange: () -> Unit,
    onOpenBiometric: () -> Unit,
    onOpenAppLockSetup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (uiState) {
        HomeUiState.Loading -> NivaraLoadingState(modifier = modifier)
        HomeUiState.Error -> NivaraErrorState(onRetry = onRetry, modifier = modifier)
        is HomeUiState.Ready -> HomeContent(
            deviceLockConfigured = uiState.deviceLockConfigured,
            credentialType = uiState.credentialType,
            biometricStatus = uiState.biometricStatus,
            session = uiState.session,
            sessionNoticeRes = uiState.sessionNoticeRes,
            onLockNow = onLockNow,
            onOpenAbout = onOpenAbout,
            onOpenCredentialSetup = onOpenCredentialSetup,
            onOpenCredentialVerify = onOpenCredentialVerify,
            onOpenCredentialChange = onOpenCredentialChange,
            onOpenBiometric = onOpenBiometric,
            onOpenAppLockSetup = onOpenAppLockSetup,
            modifier = modifier,
        )
    }
}

@Composable
private fun HomeContent(
    deviceLockConfigured: Boolean,
    credentialType: PrimaryCredentialType?,
    biometricStatus: BiometricStatus,
    session: SessionState,
    sessionNoticeRes: Int?,
    onLockNow: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenCredentialSetup: () -> Unit,
    onOpenCredentialVerify: () -> Unit,
    onOpenCredentialChange: () -> Unit,
    onOpenBiometric: () -> Unit,
    onOpenAppLockSetup: () -> Unit,
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

        SessionCard(
            session = session,
            noticeRes = sessionNoticeRes,
            onLockNow = onLockNow,
        )

        CredentialCard(
            credentialType = credentialType,
            onOpenCredentialSetup = onOpenCredentialSetup,
            onOpenCredentialVerify = onOpenCredentialVerify,
            onOpenCredentialChange = onOpenCredentialChange,
        )

        InfoCard(
            title = stringResource(id = R.string.home_foundation_title),
            body = stringResource(id = R.string.home_foundation_summary),
        )

        BiometricCard(
            biometricStatus = biometricStatus,
            credentialConfigured = credentialType != null,
            onOpenBiometric = onOpenBiometric,
        )

        AppLockCard(onOpenAppLockSetup = onOpenAppLockSetup)

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

/**
 * The credential's status, and the actions that make sense for it.
 *
 * Only one action can create the credential and only one can replace it; the button shown is
 * derived from the stored state rather than from anything the user chose on this screen.
 */
@Composable
private fun CredentialCard(
    credentialType: PrimaryCredentialType?,
    onOpenCredentialSetup: () -> Unit,
    onOpenCredentialVerify: () -> Unit,
    onOpenCredentialChange: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val body = if (credentialType == null) {
        "${stringResource(id = R.string.home_credential_none)} — " +
            stringResource(id = R.string.home_credential_none_summary)
    } else {
        stringResource(
            id = R.string.home_credential_configured,
            stringResource(id = credentialTypeNameRes(credentialType)),
        ) + " — " + stringResource(id = R.string.home_credential_configured_summary)
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InfoCard(title = stringResource(id = R.string.home_credential_title), body = body)

        if (credentialType == null) {
            Button(onClick = onOpenCredentialSetup, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(id = R.string.home_credential_setup_action))
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenCredentialVerify, modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(id = R.string.home_credential_verify_action))
                }
                OutlinedButton(onClick = onOpenCredentialChange, modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(id = R.string.home_credential_change_action))
                }
            }
        }
    }
}

/**
 * The biometric path's status, and the way into its settings.
 *
 * The card reports what Android and Nivara together say about the secondary path; it never claims
 * that biometrics replace the credential above it, and it stays available even when biometrics
 * cannot be used at all, because the settings screen is where the user finds out why.
 */
@Composable
private fun BiometricCard(
    biometricStatus: BiometricStatus,
    credentialConfigured: Boolean,
    onOpenBiometric: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InfoCard(
            title = stringResource(id = R.string.home_biometric_title),
            body = stringResource(id = biometricStatusRes(biometricStatus)),
        )
        OutlinedButton(
            onClick = onOpenBiometric,
            // Without a primary credential there is nothing for biometrics to stand in for, so the
            // settings entry point is inert rather than misleading.
            enabled = credentialConfigured,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(id = R.string.home_biometric_action))
        }
    }
}

/**
 * The session's state, and Quick Lock.
 *
 * This is the only place the in-memory session is surfaced, and it is surfaced plainly: whether
 * Nivara is currently unlocked, how it got that way, and the one action that ends it immediately.
 * The card never shows a countdown — the exact remaining time is a detail the session manager owns,
 * and a screen that tracked it would be a second source of truth.
 */
@Composable
private fun SessionCard(
    session: SessionState,
    noticeRes: Int?,
    onLockNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InfoCard(
            title = stringResource(id = R.string.session_title),
            body = "${stringResource(id = sessionStatusRes(session))} — " +
                stringResource(id = sessionSummaryRes(session)),
        )

        noticeRes?.let { notice ->
            Text(
                text = stringResource(id = notice),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // Locking is offered only while there is something to lock.
        if (session.isAuthenticated) {
            Button(onClick = onLockNow, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(id = R.string.session_action_lock))
            }
        }

        Text(
            text = stringResource(id = R.string.session_locked_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The way into App Lock preparation.
 *
 * It reports nothing about App Lock's state — the preparation screen owns that — and it promises
 * nothing: the entry point says what the screen checks, not that locking works yet.
 */
@Composable
private fun AppLockCard(
    onOpenAppLockSetup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InfoCard(
            title = stringResource(id = R.string.home_applock_title),
            body = stringResource(id = R.string.home_applock_summary),
        )
        OutlinedButton(onClick = onOpenAppLockSetup, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(id = R.string.home_applock_action))
        }
    }
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

@Preview(name = "Home – no credential, screen lock set", showBackground = true)
@Composable
private fun HomeScreenReadyPreview() {
    NivaraTheme {
        HomeScreen(
            uiState = HomeUiState.Ready(
                deviceLockConfigured = true,
                credentialType = null,
                biometricStatus = BiometricStatus.Disabled,
                session = SessionState.Unauthenticated,
            ),
            onRetry = {},
            onLockNow = {},
            onOpenAbout = {},
            onOpenCredentialSetup = {},
            onOpenCredentialVerify = {},
            onOpenCredentialChange = {},
            onOpenBiometric = {},
            onOpenAppLockSetup = {},
        )
    }
}

@Preview(name = "Home – credential configured, no screen lock", showBackground = true)
@Composable
private fun HomeScreenConfiguredPreview() {
    NivaraTheme {
        HomeScreen(
            uiState = HomeUiState.Ready(
                deviceLockConfigured = false,
                credentialType = PrimaryCredentialType.Pattern,
                biometricStatus = BiometricStatus.Enabled,
                session = SessionState.Authenticated(
                    source = AuthenticationSource.Biometric,
                    startedAtMillis = 0L,
                    expiresAtMillis = 300_000L,
                ),
            ),
            onRetry = {},
            onLockNow = {},
            onOpenAbout = {},
            onOpenCredentialSetup = {},
            onOpenCredentialVerify = {},
            onOpenCredentialChange = {},
            onOpenBiometric = {},
            onOpenAppLockSetup = {},
        )
    }
}

@Preview(name = "Home – error", showBackground = true)
@Composable
private fun HomeScreenErrorPreview() {
    NivaraTheme {
        HomeScreen(
            uiState = HomeUiState.Error,
            onRetry = {},
            onLockNow = {},
            onOpenAbout = {},
            onOpenCredentialSetup = {},
            onOpenCredentialVerify = {},
            onOpenCredentialChange = {},
            onOpenBiometric = {},
            onOpenAppLockSetup = {},
        )
    }
}
