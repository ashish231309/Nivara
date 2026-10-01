package com.nivara.app.ui.vault

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nivara.app.R
import com.nivara.app.domain.vault.VaultState
import com.nivara.app.domain.vault.VaultUnreadable
import com.nivara.app.ui.components.NivaraLoadingState
import com.nivara.app.ui.components.NivaraMessageText
import com.nivara.app.ui.theme.NivaraTheme

/**
 * Stateful entry point of the vault screen.
 *
 * It creates the view model, observes its state, re-reads the root when the screen is resumed, and
 * owns the one platform interaction this screen needs: the folder picker. The picker is Android's own
 * document-tree chooser, which is what makes the user's choice of a vault root explicit — Nivara never
 * picks a directory for them, and never falls back to one they did not choose.
 *
 * The screen itself stays stateless. Nothing here opens a file, reads a key or touches storage: the
 * picker hands back an opaque platform reference, the screen passes it straight to the view model, and
 * everything else happens behind the domain contract.
 */
@Composable
fun VaultRoute(
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VaultViewModel = viewModel(factory = VaultViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        viewModel.onResumed()
        onPauseOrDispose { }
    }

    // The view model reports that a change needs a session; this screen does not authenticate and
    // does not change anything by itself. It asks for the existing credential screen.
    val ready = uiState as? VaultUiState.Ready
    LaunchedEffect(ready?.unlockRequired) {
        if (ready?.unlockRequired == true) {
            viewModel.onUnlockHandled()
            onUnlock()
        }
    }

    // Android's own folder chooser. The result is a document tree the user selected and granted access
    // to; its reference is platform-shaped and is never displayed, logged or interpreted here.
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        uri?.let { chosen -> viewModel.onRootSelected(chosen.toString()) }
    }

    VaultScreen(
        uiState = uiState,
        onChooseRoot = { picker.launch(null) },
        onInitialize = viewModel::initialize,
        onReplaceUnreadable = viewModel::replaceUnreadable,
        onRetry = viewModel::refresh,
        onUnlock = onUnlock,
        onMessageShown = viewModel::onMessageShown,
        modifier = modifier,
    )
}

/**
 * Stateless vault screen: renders [VaultUiState] and reports user actions upwards.
 *
 * ### What it shows
 *
 * One state at a time, in the vault's own words: no vault yet, the vault ready, the root unreachable,
 * the selection refused, the records unreadable, or a format this build does not know. Each state
 * offers only the actions that are safe in it, and the copy never suggests that a vault which cannot
 * be read is an empty vault.
 *
 * ### What it does not do
 *
 * It decides nothing: the state is what the repository read, and a tap is reported. It does not
 * display the storage reference — a platform URI is unreadable to a person — and it never shows a
 * file, a key or anything from inside the vault. Importing, media, albums, search and trash belong to
 * the stages that follow and are absent here on purpose.
 */
@Composable
fun VaultScreen(
    uiState: VaultUiState,
    onChooseRoot: () -> Unit,
    onInitialize: () -> Unit,
    onReplaceUnreadable: () -> Unit,
    onRetry: () -> Unit,
    onUnlock: () -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (uiState) {
        VaultUiState.Loading -> NivaraLoadingState(modifier = modifier)

        is VaultUiState.Ready -> VaultContent(
            state = uiState,
            onChooseRoot = onChooseRoot,
            onInitialize = onInitialize,
            onReplaceUnreadable = onReplaceUnreadable,
            onRetry = onRetry,
            onUnlock = onUnlock,
            onMessageShown = onMessageShown,
            modifier = modifier,
        )
    }
}

@Composable
private fun VaultContent(
    state: VaultUiState.Ready,
    onChooseRoot: () -> Unit,
    onInitialize: () -> Unit,
    onReplaceUnreadable: () -> Unit,
    onRetry: () -> Unit,
    onUnlock: () -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        VaultStateCard(state = state.vault)

        if (!state.sessionAuthenticated) {
            Text(
                text = stringResource(id = R.string.vault_locked),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onUnlock,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(id = R.string.vault_unlock_action))
            }
        }

        if (state.canInitialize) {
            Button(
                onClick = onInitialize,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(id = R.string.vault_initialize_action))
            }
        }

        if (state.canReplaceUnreadable) {
            // Destructive, and only offered where the records cannot be opened at all. The copy says
            // what it costs rather than leaving the user to find out.
            OutlinedButton(
                onClick = onReplaceUnreadable,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(id = R.string.vault_replace_action))
            }
            Text(
                text = stringResource(id = R.string.vault_replace_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (state.vault is VaultState.Unavailable || state.vault is VaultState.AccessDenied) {
            OutlinedButton(
                onClick = onRetry,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(id = R.string.state_retry_action))
            }
        }

        OutlinedButton(
            onClick = onChooseRoot,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(
                    id = if (state.vault is VaultState.NotConfigured) {
                        R.string.vault_choose_root_action
                    } else {
                        R.string.vault_change_root_action
                    },
                ),
            )
        }

        state.noticeRes?.let { noticeRes ->
            Text(
                text = stringResource(id = noticeRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.dismissibleMessage(onMessageShown),
            )
        }
        state.failure?.let { message ->
            NivaraMessageText(message = message, modifier = Modifier.dismissibleMessage(onMessageShown))
        }

        VaultExplanationCard()
    }
}

/**
 * What is at the root, in one card.
 *
 * Every state says what happened and what it does *not* mean. In particular the unreadable states say
 * that Nivara has not deleted anything, because the alternative reading — that the vault is gone — is
 * exactly the misreading this stage exists to prevent.
 */
@Composable
private fun VaultStateCard(
    state: VaultState,
    modifier: Modifier = Modifier,
) {
    val titleRes = state.titleRes()
    val bodyRes = state.bodyRes()

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(id = titleRes),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(id = bodyRes),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * Where the vault lives and what protects it, in the user's own terms.
 *
 * The copy is deliberately explicit that the folder is the user's own choice, that the vault is one
 * folder on storage they picked, and that the files inside it are encrypted — because those are the
 * three facts a person needs before trusting it with anything.
 */
@Composable
private fun VaultExplanationCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(id = R.string.vault_explanation_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(id = R.string.vault_explanation),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Makes a message tappable so it can be dismissed, as the other screens do. */
private fun Modifier.dismissibleMessage(onDismiss: () -> Unit): Modifier =
    this
        .padding(top = 4.dp)
        .clickable(onClick = onDismiss)

@Preview(name = "Vault – no root chosen", showBackground = true)
@Composable
private fun VaultNotConfiguredPreview() {
    NivaraTheme {
        VaultScreen(
            uiState = previewState(VaultState.NotConfigured),
            onChooseRoot = {},
            onInitialize = {},
            onReplaceUnreadable = {},
            onRetry = {},
            onUnlock = {},
            onMessageShown = {},
        )
    }
}

@Preview(name = "Vault – ready", showBackground = true)
@Composable
private fun VaultReadyPreview() {
    NivaraTheme {
        VaultScreen(
            uiState = previewState(
                VaultState.Ready(
                    identity = com.nivara.app.domain.vault.VaultIdentity("00112233445566778899aabbccddeeff"),
                    formatVersion = 1,
                ),
            ),
            onChooseRoot = {},
            onInitialize = {},
            onReplaceUnreadable = {},
            onRetry = {},
            onUnlock = {},
            onMessageShown = {},
        )
    }
}

@Preview(name = "Vault – unreadable", showBackground = true)
@Composable
private fun VaultUnreadablePreview() {
    NivaraTheme {
        VaultScreen(
            uiState = previewState(VaultState.Unreadable(VaultUnreadable.MetadataDamaged)),
            onChooseRoot = {},
            onInitialize = {},
            onReplaceUnreadable = {},
            onRetry = {},
            onUnlock = {},
            onMessageShown = {},
        )
    }
}

/** A ready state for previews only. */
private fun previewState(vault: VaultState): VaultUiState.Ready = VaultUiState.Ready(
    vault = vault,
    sessionAuthenticated = true,
)
