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
import com.nivara.app.ui.components.NivaraSectionHeader
import com.nivara.app.ui.components.NivaraSpacing
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
    onOpenAppLock: () -> Unit,
    onOpenHiddenApps: () -> Unit,
    onOpenCamouflage: () -> Unit,
    onOpenVault: () -> Unit,
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
        onOpenAppLock = onOpenAppLock,
        onOpenHiddenApps = onOpenHiddenApps,
        onOpenCamouflage = onOpenCamouflage,
        onOpenVault = onOpenVault,
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
    onOpenAppLock: () -> Unit,
    onOpenHiddenApps: () -> Unit,
    onOpenCamouflage: () -> Unit,
    onOpenVault: () -> Unit,
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
            onOpenAppLock = onOpenAppLock,
            onOpenHiddenApps = onOpenHiddenApps,
            onOpenCamouflage = onOpenCamouflage,
            onOpenVault = onOpenVault,
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
    onOpenAppLock: () -> Unit,
    onOpenHiddenApps: () -> Unit,
    onOpenCamouflage: () -> Unit,
    onOpenVault: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = NivaraSpacing.screen, vertical = NivaraSpacing.row),
        verticalArrangement = Arrangement.spacedBy(NivaraSpacing.row),
    ) {
        Text(
            text = stringResource(id = R.string.home_tagline),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(id = R.string.home_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        NivaraSectionHeader(title = stringResource(id = R.string.home_section_security))

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

        NivaraSectionHeader(title = stringResource(id = R.string.home_section_protection))

        AppLockCard(onOpenAppLock = onOpenAppLock)

        HiddenAppsCard(onOpenHiddenApps = onOpenHiddenApps)

        InfoCard(
            title = stringResource(id = R.string.home_launcher_title),
            body = stringResource(id = R.string.home_launcher_summary),
        )

        CamouflageCard(onOpenCamouflage = onOpenCamouflage)

        NivaraSectionHeader(title = stringResource(id = R.string.home_section_vault))

        VaultCard(onOpenVault = onOpenVault)

        OutlinedButton(onClick = onOpenAbout, modifier = Modifier.fillMaxWidth()) {
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

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(NivaraSpacing.small)) {
        InfoCard(title = stringResource(id = R.string.home_credential_title), body = body)

        if (credentialType == null) {
            Button(onClick = onOpenCredentialSetup, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(id = R.string.home_credential_setup_action))
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(NivaraSpacing.small)) {
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
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(NivaraSpacing.small)) {
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
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(NivaraSpacing.small)) {
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
 * The way into hidden-application management.
 *
 * The card says what hiding is and, just as importantly, what it is not: a Nivara preference whose
 * effect is Nivara's own launcher, with Android's launcher unchanged. A user who reads only this card
 * must not come away believing applications have been removed from the device.
 */
@Composable
private fun HiddenAppsCard(
    onOpenHiddenApps: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(NivaraSpacing.small)) {
        InfoCard(
            title = stringResource(id = R.string.home_apphide_title),
            body = stringResource(id = R.string.home_apphide_summary),
        )
        OutlinedButton(onClick = onOpenHiddenApps, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(id = R.string.home_apphide_action))
        }
    }
}

/**
 * The way into the application-identity screen.
 *
 * The card names the feature for what it is — a name and an icon — and repeats the limitation the
 * screen behind it states in full, so the home screen cannot be read as offering a way to hide
 * Nivara from Android. It also names both ways back to Nivara, because that is the part a user
 * needs before changing the name, not after.
 */
@Composable
private fun CamouflageCard(
    onOpenCamouflage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(NivaraSpacing.small)) {
        InfoCard(
            title = stringResource(id = R.string.home_camouflage_title),
            body = stringResource(id = R.string.home_camouflage_summary),
        )
        OutlinedButton(onClick = onOpenCamouflage, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(id = R.string.home_camouflage_action))
        }
    }
}

/**
 * The way into vault storage.
 *
 * The only entry into the vault, and it says the two things a person needs before choosing a folder:
 * the folder is theirs to pick, and Nivara never picks one on its own and never falls back to another.
 * It does not claim that files can be imported yet — that is a later stage.
 */
@Composable
private fun VaultCard(
    onOpenVault: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(NivaraSpacing.small)) {
        InfoCard(
            title = stringResource(id = R.string.home_vault_title),
            body = stringResource(id = R.string.home_vault_summary),
        )
        OutlinedButton(onClick = onOpenVault, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(id = R.string.home_vault_action))
        }
    }
}

/**
 * The way into App Lock.
 *
 * The card states what the feature does, and it leads to the screen where the protected
 * applications are chosen rather than to a promise that locking works: protection is only as good
 * as the capabilities the user has granted, and the screen behind this card says which of them are
 * in place.
 */
@Composable
private fun AppLockCard(
    onOpenAppLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(NivaraSpacing.small)) {
        InfoCard(
            title = stringResource(id = R.string.home_applock_title),
            body = stringResource(id = R.string.home_applock_summary),
        )
        OutlinedButton(onClick = onOpenAppLock, modifier = Modifier.fillMaxWidth()) {
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
            modifier = Modifier.padding(NivaraSpacing.screen),
            verticalArrangement = Arrangement.spacedBy(NivaraSpacing.tight),
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
            onOpenAppLock = {},
            onOpenHiddenApps = {},
            onOpenCamouflage = {},
            onOpenVault = {},
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
            onOpenAppLock = {},
            onOpenHiddenApps = {},
            onOpenCamouflage = {},
            onOpenVault = {},
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
            onOpenAppLock = {},
            onOpenHiddenApps = {},
            onOpenCamouflage = {},
            onOpenVault = {},
        )
    }
}
