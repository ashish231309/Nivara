package com.nivara.app.ui.vault.recovery

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nivara.app.R
import com.nivara.app.ui.components.NivaraMotion
import com.nivara.app.ui.components.NivaraSpacing
import com.nivara.app.ui.components.NivaraMessageText
import com.nivara.app.ui.components.rememberNivaraMotionScale
import com.nivara.app.ui.components.scaledDurationMillis

/**
 * The recovery screen's route.
 *
 * The screen owns its own folder picker: the selection it produces is surveyed, never adopted, and
 * the vault screen's picker stays the one that adopts folders. On a successful reconnection the
 * screen reports it and offers the way back; navigation back is the caller's, as everywhere else.
 */
@Composable
fun VaultRecoveryRoute(
    onRecovered: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VaultRecoveryViewModel = viewModel(factory = VaultRecoveryViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Android's own folder chooser, used exactly as the vault screen uses it: the result is a
    // document tree the user selected, handed over as a platform-shaped reference that is never
    // drawn, logged or interpreted here.
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        uri?.let { chosen -> viewModel.onLocationPicked(chosen.toString()) }
    }

    VaultRecoveryScreen(
        uiState = uiState,
        onChooseLocation = { picker.launch(null) },
        onCodeChanged = viewModel::onCodeChanged,
        onRecover = viewModel::onRecoverRequested,
        onRestart = viewModel::onRestartRequested,
        onDone = onRecovered,
        modifier = modifier,
    )
}

@Composable
internal fun VaultRecoveryScreen(
    uiState: VaultRecoveryUiState,
    onChooseLocation: () -> Unit,
    onCodeChanged: (String) -> Unit,
    onRecover: () -> Unit,
    onRestart: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motionScale = rememberNivaraMotionScale()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = NivaraSpacing.screen, vertical = NivaraSpacing.row),
        verticalArrangement = Arrangement.spacedBy(NivaraSpacing.row),
    ) {
        Text(
            text = stringResource(id = R.string.vault_recovery_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // The phase is the screen's state; it changes when the survey answers or an attempt
        // finishes, and the content crossfades between answers rather than snapping. The motion
        // follows the device's own animator scale — a person who asked for less of it gets the
        // same phases without the fade.
        Crossfade(
            targetState = uiState.phase,
            animationSpec = tween(
                durationMillis = scaledDurationMillis(NivaraMotion.STANDARD_MILLIS, motionScale),
            ),
            label = "recovery phase",
        ) { phase ->
        when (phase) {
            VaultRecoveryPhase.SelectLocation -> VaultRecoverySelection(
                busy = uiState.busy,
                onChooseLocation = onChooseLocation,
            )
            VaultRecoveryPhase.NotAVault -> VaultRecoveryRefusal(
                messageRes = R.string.vault_recovery_not_a_vault,
                busy = uiState.busy,
                onChooseLocation = onChooseLocation,
            )
            VaultRecoveryPhase.RecoveryNotSetUp -> VaultRecoveryRefusal(
                messageRes = R.string.vault_recovery_not_set_up,
                busy = uiState.busy,
                onChooseLocation = onChooseLocation,
            )
            VaultRecoveryPhase.VaultDamaged -> VaultRecoveryRefusal(
                messageRes = R.string.vault_recovery_damaged,
                busy = uiState.busy,
                onChooseLocation = onChooseLocation,
            )
            VaultRecoveryPhase.VaultUnsupported -> VaultRecoveryRefusal(
                messageRes = R.string.vault_recovery_unsupported,
                busy = uiState.busy,
                onChooseLocation = onChooseLocation,
            )
            VaultRecoveryPhase.LocationUnavailable -> VaultRecoveryRefusal(
                messageRes = R.string.vault_recovery_location_unavailable,
                busy = uiState.busy,
                onChooseLocation = onChooseLocation,
            )
            is VaultRecoveryPhase.RecoveryRequired -> VaultRecoveryCodeEntry(
                fingerprint = phase.identityFingerprint,
                codeInput = uiState.codeInput,
                busy = uiState.busy,
                onCodeChanged = onCodeChanged,
                onRecover = onRecover,
                onRestart = onRestart,
            )
            is VaultRecoveryPhase.Reconnected -> VaultRecoverySuccess(
                fingerprint = phase.identityFingerprint,
                onDone = onDone,
            )
        }
        }

        uiState.failure?.let { failure ->
            NivaraMessageText(message = failure)
        }
    }
}

@Composable
private fun VaultRecoverySelection(
    busy: Boolean,
    onChooseLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onChooseLocation,
        enabled = !busy,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(text = stringResource(id = R.string.vault_recovery_choose_action))
    }
}

@Composable
private fun VaultRecoveryRefusal(
    messageRes: Int,
    busy: Boolean,
    onChooseLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(NivaraSpacing.row),
    ) {
        Text(
            text = stringResource(id = messageRes),
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(
            onClick = onChooseLocation,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(id = R.string.vault_recovery_choose_action))
        }
    }
}

@Composable
private fun VaultRecoveryCodeEntry(
    fingerprint: String,
    codeInput: String,
    busy: Boolean,
    onCodeChanged: (String) -> Unit,
    onRecover: () -> Unit,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(NivaraSpacing.row),
    ) {
        Text(
            text = stringResource(id = R.string.vault_recovery_identity_label),
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            text = fingerprint,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = stringResource(id = R.string.vault_recovery_identity_explainer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = codeInput,
            onValueChange = onCodeChanged,
            label = { Text(text = stringResource(id = R.string.vault_recovery_code_label)) },
            enabled = !busy,
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = onRecover,
            enabled = !busy && codeInput.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(id = R.string.vault_recovery_recover_action))
        }
        OutlinedButton(
            onClick = onRestart,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(id = R.string.vault_recovery_choose_action))
        }
    }
}

@Composable
private fun VaultRecoverySuccess(
    fingerprint: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(NivaraSpacing.row),
    ) {
        Text(
            text = stringResource(id = R.string.vault_recovery_success_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(id = R.string.vault_recovery_success),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(id = R.string.vault_recovery_identity_label),
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            text = fingerprint,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
        )
        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(id = R.string.vault_recovery_done_action))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun VaultRecoveryScreenPreview() {
    VaultRecoveryScreen(
        uiState = VaultRecoveryUiState(
            phase = VaultRecoveryPhase.RecoveryRequired(
                identityFingerprint = "abcd ef01 2345 6789 abcd ef01 2345 6789",
            ),
            codeInput = "AAAA-BBBB-CCCC-DDDD",
        ),
        onChooseLocation = {},
        onCodeChanged = {},
        onRecover = {},
        onRestart = {},
        onDone = {},
    )
}
