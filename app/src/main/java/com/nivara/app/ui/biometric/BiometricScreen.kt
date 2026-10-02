package com.nivara.app.ui.biometric

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
import com.nivara.app.ui.components.NivaraSpacing
import com.nivara.app.domain.credential.CredentialInput
import com.nivara.app.domain.credential.PrimaryCredentialType
import com.nivara.app.domain.security.BiometricStatus
import com.nivara.app.domain.security.BiometricUnavailability
import com.nivara.app.ui.components.NivaraLoadingState
import com.nivara.app.ui.components.NivaraMessage
import com.nivara.app.ui.components.NivaraMessageText
import com.nivara.app.ui.credential.CredentialEntry
import com.nivara.app.ui.credential.SecureScreenEffect
import com.nivara.app.ui.credential.credentialTypeNameRes
import com.nivara.app.ui.theme.NivaraTheme

/**
 * Stateful entry point of the biometric screen: creates the view model and observes its state.
 */
@Composable
fun BiometricRoute(
    onOpenCredentialSetup: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BiometricViewModel = viewModel(factory = BiometricViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // The screen collects the primary credential as part of confirming a change, so it is a
    // sensitive screen: no screenshots, no thumbnail in the recents list.
    SecureScreenEffect()

    BiometricScreen(
        uiState = uiState,
        onAuthenticate = viewModel::authenticate,
        onRequestChange = viewModel::requestChange,
        onCancelChange = viewModel::cancelChange,
        onSubmitPrimary = viewModel::submitPrimary,
        secondsUntilRetry = viewModel::secondsUntilRetry,
        onOpenCredentialSetup = onOpenCredentialSetup,
        modifier = modifier,
    )
}

/**
 * Stateless biometric screen: renders [BiometricUiState] and reports user actions upwards.
 *
 * The screen never draws a biometric prompt. Asking for one hands over to Android's own prompt,
 * which is the only thing that ever sees a fingerprint or a face.
 */
@Composable
fun BiometricScreen(
    uiState: BiometricUiState,
    onAuthenticate: () -> Unit,
    onRequestChange: (BiometricPendingChange) -> Unit,
    onCancelChange: () -> Unit,
    onSubmitPrimary: (CredentialInput) -> Unit,
    secondsUntilRetry: (BiometricUiState.Ready) -> Long,
    onOpenCredentialSetup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (uiState) {
        BiometricUiState.Loading -> NivaraLoadingState(modifier = modifier)
        is BiometricUiState.Ready -> BiometricContent(
            state = uiState,
            onAuthenticate = onAuthenticate,
            onRequestChange = onRequestChange,
            onCancelChange = onCancelChange,
            onSubmitPrimary = onSubmitPrimary,
            secondsUntilRetry = secondsUntilRetry,
            onOpenCredentialSetup = onOpenCredentialSetup,
            modifier = modifier,
        )
    }
}

@Composable
private fun BiometricContent(
    state: BiometricUiState.Ready,
    onAuthenticate: () -> Unit,
    onRequestChange: (BiometricPendingChange) -> Unit,
    onCancelChange: () -> Unit,
    onSubmitPrimary: (CredentialInput) -> Unit,
    secondsUntilRetry: (BiometricUiState.Ready) -> Long,
    onOpenCredentialSetup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = NivaraSpacing.screen, vertical = NivaraSpacing.row),
        verticalArrangement = Arrangement.spacedBy(NivaraSpacing.screen),
    ) {
        Text(
            text = stringResource(id = R.string.biometric_screen_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        StatusCard(state = state)

        if (state.retryAtMillis != null) {
            Text(
                text = stringResource(
                    id = R.string.biometric_throttle_notice,
                    secondsUntilRetry(state),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            // The wait is Nivara's own; the primary credential is the way past it.
            OutlinedButton(
                onClick = { onRequestChange(BiometricPendingChange.ClearDelay) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(id = R.string.biometric_action_use_primary))
            }
        }

        if (state.busy) {
            Text(
                text = stringResource(id = R.string.biometric_busy_notice),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.noticeRes?.let { noticeRes ->
            Text(
                text = stringResource(id = noticeRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        state.failure?.let { failure -> NivaraMessageText(message = failure) }

        if (state.pending != null) {
            ChangeConfirmation(
                credentialType = state.credentialType,
                enabled = !state.busy,
                onCancel = onCancelChange,
                onSubmit = onSubmitPrimary,
            )
        } else {
            StatusActions(
                state = state,
                onAuthenticate = onAuthenticate,
                onRequestChange = onRequestChange,
                onOpenCredentialSetup = onOpenCredentialSetup,
            )
        }

        // Nivara's delay and Android's lockout are different things, and the user is told so.
        Text(
            text = stringResource(id = R.string.biometric_system_lockout_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusCard(
    state: BiometricUiState.Ready,
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
            Text(
                text = stringResource(id = biometricStatusRes(state.status)),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(id = biometricStatusSummaryRes(state.status)),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * The actions that make sense for the current status.
 *
 * Nothing here changes the configuration on its own: each action sets a pending change, which the
 * primary credential confirms below. When no credential exists there is nothing biometrics could
 * stand in for, so the only action offered is setting one up.
 */
@Composable
private fun StatusActions(
    state: BiometricUiState.Ready,
    onAuthenticate: () -> Unit,
    onRequestChange: (BiometricPendingChange) -> Unit,
    onOpenCredentialSetup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(NivaraSpacing.small)) {
        when (state.status) {
            BiometricStatus.Enabled -> {
                Button(
                    onClick = onAuthenticate,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(id = R.string.biometric_action_authenticate))
                }
                OutlinedButton(
                    onClick = { onRequestChange(BiometricPendingChange.TurnOff) },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(id = R.string.biometric_action_disable))
                }
            }

            BiometricStatus.Disabled, BiometricStatus.Invalidated ->
                if (state.credentialType == null) {
                    NoCredential(onOpenCredentialSetup = onOpenCredentialSetup)
                } else {
                    Button(
                        onClick = { onRequestChange(BiometricPendingChange.TurnOn) },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(
                                id = if (state.status is BiometricStatus.Invalidated) {
                                    R.string.biometric_action_re_enable
                                } else {
                                    R.string.biometric_action_enable
                                },
                            ),
                        )
                    }
                }

            // Nothing can be turned on while the platform says it cannot authenticate. The reason
            // is already on screen; the credential path is still offered when there is none.
            is BiometricStatus.Unavailable ->
                if (state.credentialType == null) {
                    NoCredential(onOpenCredentialSetup = onOpenCredentialSetup)
                }
        }
    }
}

/** Without a primary credential there is no account state for biometrics to stand in for. */
@Composable
private fun NoCredential(
    onOpenCredentialSetup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(NivaraSpacing.small)) {
        Text(
            text = stringResource(id = R.string.biometric_fallback_no_credential),
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onOpenCredentialSetup, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(id = R.string.biometric_fallback_set_up_credential))
        }
    }
}

/**
 * Collects the primary credential for a pending change.
 *
 * Turning biometric unlock on, turning it off and clearing Nivara's delay all pass through here,
 * which is what makes "authenticated action" true rather than a label on a button. The credential
 * is handed to the view model, which gives it to the manager; nothing keeps it in the composition.
 */
@Composable
private fun ChangeConfirmation(
    credentialType: PrimaryCredentialType?,
    enabled: Boolean,
    onCancel: () -> Unit,
    onSubmit: (CredentialInput) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(NivaraSpacing.small)) {
        Text(
            text = stringResource(id = R.string.biometric_approval_message),
            style = MaterialTheme.typography.bodyMedium,
        )

        when (credentialType) {
            null -> Text(
                text = stringResource(id = R.string.biometric_fallback_no_credential),
                style = MaterialTheme.typography.bodyMedium,
            )

            else -> {
                Text(
                    text = stringResource(id = R.string.biometric_fallback_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(
                        id = R.string.biometric_fallback_hint,
                        stringResource(id = credentialTypeNameRes(credentialType)),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CredentialEntry(
                    type = credentialType,
                    enabled = enabled,
                    submitLabel = stringResource(id = R.string.biometric_fallback_submit),
                    onSubmit = onSubmit,
                )
            }
        }

        OutlinedButton(
            onClick = onCancel,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(id = R.string.biometric_action_cancel))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun BiometricEnabledPreview() {
    NivaraTheme {
        BiometricScreen(
            uiState = BiometricUiState.Ready(
                status = BiometricStatus.Enabled,
                credentialType = PrimaryCredentialType.Pin,
            ),
            onAuthenticate = {},
            onRequestChange = {},
            onCancelChange = {},
            onSubmitPrimary = {},
            secondsUntilRetry = { 0L },
            onOpenCredentialSetup = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun BiometricThrottledPreview() {
    NivaraTheme {
        BiometricScreen(
            uiState = BiometricUiState.Ready(
                status = BiometricStatus.Enabled,
                credentialType = PrimaryCredentialType.Pattern,
                failure = NivaraMessage(textRes = R.string.biometric_error_failed),
                retryAtMillis = 30_000L,
            ),
            onAuthenticate = {},
            onRequestChange = {},
            onCancelChange = {},
            onSubmitPrimary = {},
            secondsUntilRetry = { 30L },
            onOpenCredentialSetup = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun BiometricUnavailablePreview() {
    NivaraTheme {
        BiometricScreen(
            uiState = BiometricUiState.Ready(
                status = BiometricStatus.Unavailable(BiometricUnavailability.NotEnrolled),
                credentialType = null,
            ),
            onAuthenticate = {},
            onRequestChange = {},
            onCancelChange = {},
            onSubmitPrimary = {},
            secondsUntilRetry = { 0L },
            onOpenCredentialSetup = {},
        )
    }
}
