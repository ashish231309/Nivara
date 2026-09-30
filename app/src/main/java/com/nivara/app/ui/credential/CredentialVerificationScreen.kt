package com.nivara.app.ui.credential

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nivara.app.R
import com.nivara.app.domain.credential.CredentialInput
import com.nivara.app.ui.components.NivaraLoadingState

/**
 * Verifies the primary credential.
 *
 * The success state is a plain confirmation. What an accepted credential *unlocks* — a session,
 * a vault, a protected app — is deliberately absent: this stage establishes authentication, and
 * the session boundary it feeds is consumed later.
 */
@Composable
fun CredentialVerifyRoute(
    onChangeCredential: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: CredentialVerificationViewModel = viewModel(factory = CredentialVerificationViewModel.Factory)

    CredentialVerificationRoute(
        viewModel = viewModel,
        onChangeCredential = onChangeCredential,
        modifier = modifier,
    )
}

@Composable
private fun CredentialVerificationRoute(
    viewModel: CredentialVerificationViewModel,
    onChangeCredential: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // No screenshots or recents thumbnail while a credential is on screen.
    SecureScreenEffect()

    CredentialVerificationScreen(
        uiState = uiState,
        onSubmit = viewModel::submit,
        onChangeCredential = onChangeCredential,
        modifier = modifier,
    )
}

/** Stateless verification screen. */
@Composable
fun CredentialVerificationScreen(
    uiState: CredentialVerificationUiState,
    onSubmit: (CredentialInput) -> Unit,
    onChangeCredential: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (uiState.step) {
            CredentialVerificationStep.Loading -> NivaraLoadingState(
                message = stringResource(id = R.string.state_loading),
            )

            CredentialVerificationStep.Enter -> {
                val type = uiState.type
                Text(
                    text = stringResource(id = R.string.credential_unlock_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(id = R.string.credential_unlock_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (type == null) {
                    Text(
                        text = stringResource(id = R.string.credential_error_invalid_configuration),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    CredentialEntry(
                        type = type,
                        enabled = true,
                        submitLabel = stringResource(id = R.string.credential_action_verify),
                        onSubmit = onSubmit,
                    )
                    uiState.failure?.let { message -> CredentialMessageText(message = message) }
                }
            }

            CredentialVerificationStep.Verifying -> NivaraLoadingState(
                message = stringResource(id = R.string.credential_verifying_message),
            )

            CredentialVerificationStep.Succeeded -> {
                Text(
                    text = stringResource(id = R.string.credential_verified_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(id = R.string.credential_verified_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onChangeCredential, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(id = R.string.credential_action_change))
                }
            }

            CredentialVerificationStep.NotConfigured -> {
                Text(
                    text = stringResource(id = R.string.credential_error_not_configured),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(id = R.string.credential_not_configured_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            CredentialVerificationStep.InvalidConfiguration -> {
                Text(
                    text = stringResource(id = R.string.credential_error_invalid_configuration),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(id = R.string.credential_invalid_configuration_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
