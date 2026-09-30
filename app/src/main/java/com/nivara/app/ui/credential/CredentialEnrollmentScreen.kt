package com.nivara.app.ui.credential

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nivara.app.R
import com.nivara.app.domain.credential.CredentialInput
import com.nivara.app.domain.credential.PrimaryCredentialType
import com.nivara.app.ui.components.NivaraLoadingState

/** First-time enrollment: choose a method, enter it, confirm it, save. */
@Composable
fun CredentialSetupRoute(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: CredentialEnrollmentViewModel =
        viewModel(factory = CredentialEnrollmentViewModel.factory(CredentialFlowMode.Setup))

    CredentialEnrollmentRoute(viewModel = viewModel, onDone = onDone, modifier = modifier)
}

/** Replacement: choose a method, enter it, confirm it, authenticate, save. */
@Composable
fun CredentialChangeRoute(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: CredentialEnrollmentViewModel =
        viewModel(factory = CredentialEnrollmentViewModel.factory(CredentialFlowMode.Change))

    CredentialEnrollmentRoute(viewModel = viewModel, onDone = onDone, modifier = modifier)
}

@Composable
private fun CredentialEnrollmentRoute(
    viewModel: CredentialEnrollmentViewModel,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Keeps this screen out of screenshots and the recents thumbnail while it is shown.
    SecureScreenEffect()

    CredentialEnrollmentScreen(
        uiState = uiState,
        onSelectType = viewModel::selectType,
        onEntry = viewModel::submitEntry,
        onConfirmation = viewModel::submitConfirmation,
        onCurrentCredential = viewModel::submitCurrentCredential,
        onDone = onDone,
        modifier = modifier,
    )
}

/**
 * Stateless enrollment screen.
 *
 * It renders a step and reports entry upwards; it holds no credential beyond the field the user
 * is currently typing into, and it never reads back what was submitted.
 */
@Composable
fun CredentialEnrollmentScreen(
    uiState: CredentialEnrollmentUiState,
    onSelectType: (PrimaryCredentialType) -> Unit,
    onEntry: (CredentialInput) -> Unit,
    onConfirmation: (CredentialInput) -> Unit,
    onCurrentCredential: (CredentialInput) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (uiState.step) {
            CredentialEnrollmentStep.ChooseType -> {
                ScreenHeading(
                    titleRes = R.string.credential_choose_type_title,
                    summaryRes = R.string.credential_choose_type_summary,
                )
                CredentialTypeOptions(selected = uiState.selectedType, onSelect = onSelectType)
            }

            CredentialEnrollmentStep.EnterCredential -> {
                ScreenHeading(
                    titleRes = enterTitleRes(uiState.selectedType),
                    summaryRes = enterSummaryRes(uiState.selectedType),
                )
                CredentialEntry(
                    type = uiState.selectedType,
                    enabled = true,
                    submitLabel = stringResource(id = R.string.credential_action_continue),
                    onSubmit = onEntry,
                )
                FailureMessage(uiState.failure)
            }

            CredentialEnrollmentStep.ConfirmCredential -> {
                ScreenHeading(
                    titleRes = R.string.credential_confirm_title,
                    summaryRes = R.string.credential_confirm_summary,
                )
                CredentialEntry(
                    type = uiState.selectedType,
                    enabled = true,
                    submitLabel = stringResource(id = R.string.credential_action_save),
                    onSubmit = onConfirmation,
                )
                FailureMessage(uiState.failure)
            }

            CredentialEnrollmentStep.VerifyCurrent -> {
                val configuredType = uiState.configuredType
                ScreenHeading(
                    titleRes = R.string.credential_verify_current_title,
                    summaryRes = R.string.credential_verify_current_summary,
                )
                if (configuredType == null) {
                    Text(
                        text = stringResource(id = R.string.credential_error_invalid_configuration),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    CredentialEntry(
                        type = configuredType,
                        enabled = true,
                        submitLabel = stringResource(id = R.string.credential_action_change),
                        onSubmit = onCurrentCredential,
                    )
                }
                FailureMessage(uiState.failure)
            }

            CredentialEnrollmentStep.Working -> NivaraLoadingState(
                message = stringResource(id = R.string.credential_working_message),
            )

            CredentialEnrollmentStep.Saved -> {
                ScreenHeading(
                    titleRes = R.string.credential_saved_title,
                    summaryRes = R.string.credential_saved_summary,
                )
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(id = R.string.credential_action_done))
                }
            }
        }
    }
}

@Composable
private fun ScreenHeading(@StringRes titleRes: Int, @StringRes summaryRes: Int) {
    Text(text = stringResource(id = titleRes), style = MaterialTheme.typography.titleMedium)
    Text(
        text = stringResource(id = summaryRes),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun FailureMessage(message: CredentialMessage?) {
    if (message != null) {
        CredentialMessageText(message = message)
    }
}

@Composable
private fun CredentialTypeOptions(
    selected: PrimaryCredentialType,
    onSelect: (PrimaryCredentialType) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryCredentialType.entries.forEach { type ->
            CredentialTypeOption(
                type = type,
                selected = type == selected,
                onSelect = { onSelect(type) },
            )
        }
    }
}

@Composable
private fun CredentialTypeOption(
    type: PrimaryCredentialType,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Column {
                Text(
                    text = stringResource(id = credentialTypeNameRes(type)),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(id = typeSummaryRes(type)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@StringRes
private fun typeSummaryRes(type: PrimaryCredentialType): Int = when (type) {
    PrimaryCredentialType.Pin -> R.string.credential_type_pin_summary
    PrimaryCredentialType.Password -> R.string.credential_type_password_summary
    PrimaryCredentialType.Pattern -> R.string.credential_type_pattern_summary
}

@StringRes
private fun enterTitleRes(type: PrimaryCredentialType): Int = when (type) {
    PrimaryCredentialType.Pin -> R.string.credential_enter_pin_title
    PrimaryCredentialType.Password -> R.string.credential_enter_password_title
    PrimaryCredentialType.Pattern -> R.string.credential_enter_pattern_title
}

@StringRes
private fun enterSummaryRes(type: PrimaryCredentialType): Int = when (type) {
    PrimaryCredentialType.Pin -> R.string.credential_enter_pin_summary
    PrimaryCredentialType.Password -> R.string.credential_enter_password_summary
    PrimaryCredentialType.Pattern -> R.string.credential_enter_pattern_summary
}
