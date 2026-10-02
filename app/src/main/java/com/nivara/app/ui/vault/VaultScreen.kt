package com.nivara.app.ui.vault

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nivara.app.R
import com.nivara.app.ui.components.NivaraSpacing
import com.nivara.app.domain.vault.VaultAlbumId
import com.nivara.app.domain.vault.VaultImportProgress
import com.nivara.app.domain.vault.VaultItemId
import com.nivara.app.domain.vault.VaultSortField
import com.nivara.app.domain.vault.VaultTrashSortField
import com.nivara.app.domain.vault.VaultState
import com.nivara.app.domain.vault.VaultUnreadableReason
import com.nivara.app.ui.components.NivaraLoadingState
import com.nivara.app.ui.components.NivaraMessageText
import com.nivara.app.ui.components.NivaraMotion
import com.nivara.app.ui.components.rememberNivaraMotionScale
import com.nivara.app.ui.components.scaledDurationMillis
import com.nivara.app.ui.theme.NivaraTheme
import com.nivara.app.ui.vault.viewer.VaultItemViewerScreen
import com.nivara.app.ui.vault.viewer.VaultViewerUiState
import com.nivara.app.ui.vault.viewer.VaultViewerViewModel

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
    onRecover: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VaultViewModel = viewModel(factory = VaultViewModel.Factory),
) {
    // The viewer's own view model, created for this destination next to the vault's. It is taken from
    // here rather than passed in because it is the screen's own type: a public route exposes the
    // states it draws, not the machinery that holds decrypted content.
    val viewerViewModel: VaultViewerViewModel = viewModel(factory = VaultViewerViewModel.Factory)
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val viewerState by viewerViewModel.uiState.collectAsStateWithLifecycle()

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

    // Android's own single-document chooser, for the file to import. It grants access to exactly the
    // document the user picked — no folder, no broader permission — and the grant is *not* persisted:
    // the document is read once during the import, and the vault keeps its own encrypted copy. The
    // handle is converted to the vault's opaque reference in this callback and is never drawn, logged
    // or kept in any state.
    val documentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { chosen -> viewModel.onFileSelected(chosen.toString()) }
    }

    if (viewerState is VaultViewerUiState.Closed) {
        VaultScreen(
            uiState = uiState,
            onChooseRoot = { picker.launch(null) },
            onImport = { documentPicker.launch(arrayOf("*/*")) },
            // One way into a file, whichever collection it was found in: the vault's own list, a
            // search result and an album's items all hand the same row to the same viewer.
            onOpenItem = { item -> viewerViewModel.open(item) },
            onInitialize = viewModel::initialize,
            onReplaceUnreadable = viewModel::replaceUnreadable,
            onRetry = viewModel::refresh,
            onUnlock = onUnlock,
            onRecover = onRecover,
            onSetUpRecovery = viewModel::onSetUpRecoveryRequested,
            onRecoveryCodeAcknowledged = viewModel::onRecoveryCodeAcknowledged,
            onMessageShown = viewModel::onMessageShown,
            onSectionSelected = viewModel::onSectionSelected,
            onSearchQueryChanged = viewModel::onSearchQueryChanged,
            onSearchCleared = viewModel::onSearchCleared,
            onSortFieldSelected = viewModel::onSortFieldSelected,
            onSortDirectionToggled = viewModel::onSortDirectionToggled,
            onAlbumOpened = viewModel::onAlbumOpened,
            onAlbumClosed = viewModel::onAlbumClosed,
            onAlbumCreated = viewModel::onCreateAlbum,
            onAlbumRenameStarted = viewModel::onRenameAlbumStarted,
            onAlbumRenameCancelled = viewModel::onRenameAlbumCancelled,
            onAlbumRenameConfirmed = viewModel::onRenameAlbumConfirmed,
            onAlbumDeleteRequested = viewModel::onDeleteAlbumRequested,
            onAlbumDeleteCancelled = viewModel::onDeleteAlbumCancelled,
            onAlbumDeleteConfirmed = viewModel::onDeleteAlbumConfirmed,
            onAlbumItemsEditingChanged = viewModel::onAlbumItemsEditingChanged,
            onAlbumItemAdded = viewModel::onAddItemToAlbum,
            onAlbumItemRemoved = viewModel::onRemoveItemFromAlbum,
            onTrashItem = viewModel::onTrashItemRequested,
            onRestoreItem = viewModel::onRestoreRequested,
            onTrashSortFieldSelected = viewModel::onTrashSortFieldSelected,
            onTrashSortDirectionToggled = viewModel::onTrashSortDirectionToggled,
            modifier = modifier,
        )
    } else {
        // The viewer is part of this screen rather than a destination of its own: it is one file from
        // the list, and leaving it leaves nothing behind — the view model that owns its content is
        // cleared with the screen.
        BackHandler { viewerViewModel.close() }
        VaultItemViewerScreen(
            state = viewerState,
            image = viewerViewModel.image,
            text = viewerViewModel.text,
            documentPage = viewerViewModel.documentPage,
            onPlay = viewerViewModel::onPlay,
            onPause = viewerViewModel::onPause,
            onSeekTo = viewerViewModel::onSeekTo,
            onNextPage = viewerViewModel::onNextPage,
            onPreviousPage = viewerViewModel::onPreviousPage,
            onSurfaceAvailable = viewerViewModel::onSurfaceAvailable,
            onSurfaceDestroyed = viewerViewModel::onSurfaceDestroyed,
            onRetry = viewerViewModel::onRetry,
            onUnlock = onUnlock,
            onClose = viewerViewModel::close,
            modifier = modifier,
        )
    }
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
 * key or anything that could open the vault. What it does show is the vault's own list of imported
 * files: a name, a kind, a size and an arrival time. Opening those files, rendering them, albums,
 * search and trash belong to the stages that follow and are absent here on purpose.
 */
@Composable
fun VaultScreen(
    uiState: VaultUiState,
    onChooseRoot: () -> Unit,
    onImport: () -> Unit,
    onOpenItem: (VaultItemUi) -> Unit,
    onInitialize: () -> Unit,
    onReplaceUnreadable: () -> Unit,
    onRetry: () -> Unit,
    onUnlock: () -> Unit,
    onMessageShown: () -> Unit,
    onSectionSelected: (VaultSection) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onSearchCleared: () -> Unit,
    onSortFieldSelected: (VaultSortField) -> Unit,
    onSortDirectionToggled: () -> Unit,
    onAlbumOpened: (VaultAlbumId) -> Unit,
    onAlbumClosed: () -> Unit,
    onAlbumCreated: (String) -> Unit,
    onAlbumRenameStarted: (VaultAlbumId) -> Unit,
    onAlbumRenameCancelled: () -> Unit,
    onAlbumRenameConfirmed: (VaultAlbumId, String) -> Unit,
    onAlbumDeleteRequested: (VaultAlbumId) -> Unit,
    onAlbumDeleteCancelled: () -> Unit,
    onAlbumDeleteConfirmed: (VaultAlbumId) -> Unit,
    onAlbumItemsEditingChanged: (Boolean) -> Unit,
    onAlbumItemAdded: (VaultItemId) -> Unit,
    onAlbumItemRemoved: (VaultItemId) -> Unit,
    onTrashItem: (VaultItemId) -> Unit = {},
    onRestoreItem: (VaultItemId) -> Unit = {},
    onTrashSortFieldSelected: (VaultTrashSortField) -> Unit = {},
    onTrashSortDirectionToggled: () -> Unit = {},
    onRecover: () -> Unit = {},
    onSetUpRecovery: () -> Unit = {},
    onRecoveryCodeAcknowledged: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val motionScale = rememberNivaraMotionScale()

    // The one transition this screen animates is progress giving way to what was found. The
    // crossfade targets the kind of state, not the state itself: the Ready value changes with
    // every search keystroke and every sort, and none of those redraws is an arrival.
    Crossfade(
        targetState = uiState is VaultUiState.Ready,
        animationSpec = tween(
            durationMillis = scaledDurationMillis(NivaraMotion.STANDARD_MILLIS, motionScale),
        ),
        label = "vault state",
    ) { ready ->
    when {
        !ready -> NivaraLoadingState(modifier = modifier)

        uiState is VaultUiState.Ready -> VaultContent(
            state = uiState,
            onChooseRoot = onChooseRoot,
            onImport = onImport,
            onOpenItem = onOpenItem,
            onInitialize = onInitialize,
            onReplaceUnreadable = onReplaceUnreadable,
            onRetry = onRetry,
            onUnlock = onUnlock,
            onRecover = onRecover,
            onSetUpRecovery = onSetUpRecovery,
            onRecoveryCodeAcknowledged = onRecoveryCodeAcknowledged,
            onMessageShown = onMessageShown,
            onSectionSelected = onSectionSelected,
            onSearchQueryChanged = onSearchQueryChanged,
            onSearchCleared = onSearchCleared,
            onSortFieldSelected = onSortFieldSelected,
            onSortDirectionToggled = onSortDirectionToggled,
            onAlbumOpened = onAlbumOpened,
            onAlbumClosed = onAlbumClosed,
            onAlbumCreated = onAlbumCreated,
            onAlbumRenameStarted = onAlbumRenameStarted,
            onAlbumRenameCancelled = onAlbumRenameCancelled,
            onAlbumRenameConfirmed = onAlbumRenameConfirmed,
            onAlbumDeleteRequested = onAlbumDeleteRequested,
            onAlbumDeleteCancelled = onAlbumDeleteCancelled,
            onAlbumDeleteConfirmed = onAlbumDeleteConfirmed,
            onAlbumItemsEditingChanged = onAlbumItemsEditingChanged,
            onAlbumItemAdded = onAlbumItemAdded,
            onAlbumItemRemoved = onAlbumItemRemoved,
            onTrashItem = onTrashItem,
            onRestoreItem = onRestoreItem,
            onTrashSortFieldSelected = onTrashSortFieldSelected,
            onTrashSortDirectionToggled = onTrashSortDirectionToggled,
            modifier = modifier,
        )

        // The Ready branch above guards the type; nothing else is possible once `ready` is true.
        else -> Unit
    }
    }
}

@Composable
private fun VaultContent(
    state: VaultUiState.Ready,
    onChooseRoot: () -> Unit,
    onImport: () -> Unit,
    onOpenItem: (VaultItemUi) -> Unit,
    onInitialize: () -> Unit,
    onReplaceUnreadable: () -> Unit,
    onRetry: () -> Unit,
    onUnlock: () -> Unit,
    onRecover: () -> Unit,
    onSetUpRecovery: () -> Unit,
    onRecoveryCodeAcknowledged: () -> Unit,
    onMessageShown: () -> Unit,
    onSectionSelected: (VaultSection) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onSearchCleared: () -> Unit,
    onSortFieldSelected: (VaultSortField) -> Unit,
    onSortDirectionToggled: () -> Unit,
    onAlbumOpened: (VaultAlbumId) -> Unit,
    onAlbumClosed: () -> Unit,
    onAlbumCreated: (String) -> Unit,
    onAlbumRenameStarted: (VaultAlbumId) -> Unit,
    onAlbumRenameCancelled: () -> Unit,
    onAlbumRenameConfirmed: (VaultAlbumId, String) -> Unit,
    onAlbumDeleteRequested: (VaultAlbumId) -> Unit,
    onAlbumDeleteCancelled: () -> Unit,
    onAlbumDeleteConfirmed: (VaultAlbumId) -> Unit,
    onAlbumItemsEditingChanged: (Boolean) -> Unit,
    onAlbumItemAdded: (VaultItemId) -> Unit,
    onAlbumItemRemoved: (VaultItemId) -> Unit,
    onTrashItem: (VaultItemId) -> Unit,
    onRestoreItem: (VaultItemId) -> Unit,
    onTrashSortFieldSelected: (VaultTrashSortField) -> Unit,
    onTrashSortDirectionToggled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = NivaraSpacing.screen, vertical = NivaraSpacing.row),
        verticalArrangement = Arrangement.spacedBy(NivaraSpacing.row),
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

        // Choosing the folder is the first half of a durable change, so it is offered exactly while a
        // change is allowed: with the same gate and the same explanation as every other configuration
        // screen, rather than opening a picker whose result could not be used.
        OutlinedButton(
            onClick = onChooseRoot,
            enabled = !state.busy && state.sessionAuthenticated,
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

        // The way back into a vault that outlived this installation. It is offered exactly where an
        // installation with no memory of a vault would look — no folder chosen, or the record of
        // the folder unreadable — and it surveys the folder the user picks without adopting it.
        if (state.vault is VaultState.NotConfigured || state.vault is VaultState.LocationUnknown) {
            OutlinedButton(
                onClick = onRecover,
                enabled = !state.busy && state.sessionAuthenticated,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(id = R.string.vault_recover_entry_action))
            }
        }

        // A connected vault without recovery material has no way back in once this installation's
        // state is gone; the screen says so and offers setup. A damaged recovery record is offered
        // the same way, because setting up replaces it.
        if (state.recoveryCard == VaultRecoveryCard.NotSetUp ||
            state.recoveryCard == VaultRecoveryCard.Damaged
        ) {
            Text(
                text = stringResource(
                    id = if (state.recoveryCard == VaultRecoveryCard.Damaged) {
                        R.string.vault_recovery_setup_damaged
                    } else {
                        R.string.vault_recovery_setup_card
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onSetUpRecovery,
                enabled = state.canSetUpRecovery,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(id = R.string.vault_recovery_setup_action))
            }
        }

        // The code exists for one moment only: while this dialog is on screen. Acknowledging it is
        // the act that ends that moment, and nothing keeps a copy afterwards.
        val recoveryCode = state.recoveryCode
        if (recoveryCode != null) {
            VaultRecoveryCodeDialog(
                code = recoveryCode,
                onAcknowledged = onRecoveryCodeAcknowledged,
            )
        }

        // Everything below draws the vault's authenticated metadata, and nothing below it is offered
        // at all until the vault itself opens — an album list, a search box and a sort control offered
        // over an unreadable vault would suggest there is something to arrange.
        if (state.vault is VaultState.Ready) {
            VaultBrowseControls(
                section = state.section,
                searchQuery = state.searchQuery,
                ordering = state.ordering,
                trashOrdering = state.trashOrdering,
                busy = state.busy,
                onSectionSelected = onSectionSelected,
                onSearchQueryChanged = onSearchQueryChanged,
                onSearchCleared = onSearchCleared,
                onSortFieldSelected = onSortFieldSelected,
                onSortDirectionToggled = onSortDirectionToggled,
                onTrashSortFieldSelected = onTrashSortFieldSelected,
                onTrashSortDirectionToggled = onTrashSortDirectionToggled,
            )

            VaultSearchResultCard(
                section = state.section,
                search = state.search,
                searchQuery = state.searchQuery,
                matchCount = state.searchSummary?.matches,
                totalCount = state.searchSummary?.total,
            )

            // The trash record cannot be read, so Nivara cannot say which of the files below are out
            // of the active collection. Saying so is the only honest thing to do with a list that may
            // contain files whose state is unknown.
            if (state.trashStateUnknown && state.section != VaultSection.Trash) {
                Text(
                    text = stringResource(id = R.string.vault_trash_state_unknown_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            when (state.section) {
                VaultSection.AllItems -> VaultIndexCard(
                    index = state.index,
                    onOpenItem = onOpenItem,
                    onTrashItem = if (state.trash.acceptsChanges) { item -> onTrashItem(item.id) } else null,
                )

                VaultSection.Trash -> VaultTrashCard(
                    trash = state.trash,
                    busy = state.busy,
                    onRestore = onRestoreItem,
                )

                VaultSection.Albums -> VaultAlbumsCard(
                    organization = state.organization,
                    openAlbum = state.openAlbum,
                    index = state.index,
                    renamingAlbumId = state.renamingAlbumId,
                    confirmingDeleteId = state.confirmingAlbumDeleteId,
                    editingItems = state.editingAlbumItems,
                    busy = state.busy,
                    searchActive = state.search.isActive,
                    onCreate = onAlbumCreated,
                    onOpen = onAlbumOpened,
                    onClose = onAlbumClosed,
                    onRenameStarted = onAlbumRenameStarted,
                    onRenameCancelled = onAlbumRenameCancelled,
                    onRenameConfirmed = onAlbumRenameConfirmed,
                    onDeleteRequested = onAlbumDeleteRequested,
                    onDeleteCancelled = onAlbumDeleteCancelled,
                    onDeleteConfirmed = onAlbumDeleteConfirmed,
                    onEditingChanged = onAlbumItemsEditingChanged,
                    onAddItem = onAlbumItemAdded,
                    onRemoveItem = onAlbumItemRemoved,
                    onOpenItem = onOpenItem,
                    onTrashItem = if (state.trash.acceptsChanges) { item -> onTrashItem(item.id) } else null,
                )
            }
        }

        if (state.importing) {
            ImportProgressCard(progress = state.progress)
        }

        if (state.canImport) {
            Button(
                onClick = onImport,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(id = R.string.vault_import_action))
            }
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
 * The one moment a recovery code exists on screen.
 *
 * The dialog cannot be dismissed by tapping away: the code is shown exactly once, and the only way
 * past is acknowledging that it has been stored — the act that clears it from the screen's state.
 * What it draws is the code itself, a string; the key behind it never reaches this layer.
 */
@Composable
private fun VaultRecoveryCodeDialog(
    code: String,
    onAcknowledged: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { /* the code is acknowledged, not dismissed */ },
        title = {
            Text(text = stringResource(id = R.string.vault_recovery_code_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(NivaraSpacing.row)) {
                Text(
                    text = stringResource(id = R.string.vault_recovery_code_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = code,
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
        confirmButton = {
            Button(onClick = onAcknowledged) {
                Text(text = stringResource(id = R.string.vault_recovery_code_stored_action))
            }
        },
    )
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
            modifier = Modifier.padding(NivaraSpacing.screen),
            verticalArrangement = Arrangement.spacedBy(NivaraSpacing.small),
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
 * The list of files in the vault, in one card.
 *
 * The card draws the list's state as its own fact — empty, readable, unreadable, from a newer Nivara,
 * unreachable — and never lets a failure look like emptiness. Items are drawn as name, kind, size and
 * arrival time: enough to recognise a file, and nothing that could open it.
 */
@Composable
private fun VaultIndexCard(
    index: VaultIndexUiState,
    onOpenItem: (VaultItemUi) -> Unit,
    onTrashItem: ((VaultItemUi) -> Unit)?,
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
            verticalArrangement = Arrangement.spacedBy(NivaraSpacing.small),
        ) {
            Text(
                text = if (index is VaultIndexUiState.Indexed) {
                    stringResource(id = index.titleRes(), index.items.size)
                } else {
                    stringResource(id = index.titleRes())
                },
                style = MaterialTheme.typography.titleMedium,
            )
            index.bodyRes()?.let { bodyRes ->
                Text(text = stringResource(id = bodyRes), style = MaterialTheme.typography.bodyMedium)
            }
            index.noticesRes().forEach { notice ->
                Text(
                    text = stringResource(id = notice.textRes, notice.count),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (index is VaultIndexUiState.Indexed && index.items.isNotEmpty()) {
                index.items.forEach { item ->
                    VaultItemRow(item = item, onOpen = onOpenItem, onTrash = onTrashItem)
                }
            }
        }
    }
}

/**
 * One imported file: what it was called, what kind it is, how large it is and when it arrived.
 *
 * The whole row opens the file. A type Nivara has no viewer for is not marked as broken here — it is
 * a file like any other in the list, and opening it says plainly that this version cannot show it.
 *
 * There is one of these, shared by the vault's list, the search results and an album's items, because
 * all three are the same rows of the same authenticated metadata — and because a file must look the
 * same, and open the same way, wherever it is found.
 */
@Composable
internal fun VaultItemRow(
    item: VaultItemUi,
    onOpen: (VaultItemUi) -> Unit,
    onTrash: ((VaultItemUi) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val kind = stringResource(id = vaultItemTypeRes(item))
    val size = Formatter.formatShortFileSize(context, item.sizeBytes)
    val imported = DateUtils.getRelativeTimeSpanString(item.importedAtEpochMillis)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = stringResource(id = R.string.vault_item_open_action),
            ) { onOpen(item) }
            .padding(top = NivaraSpacing.tight),
        verticalArrangement = Arrangement.spacedBy(NivaraSpacing.hairline),
    ) {
        Text(text = item.name, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = stringResource(id = R.string.vault_item_details_format, kind, size),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = stringResource(id = R.string.vault_item_imported_format, imported),
            style = MaterialTheme.typography.bodySmall,
        )
        // Moving a file out of the active collection is offered only where the trash record can be
        // read: a control that cannot succeed is worse than no control.
        if (onTrash != null) {
            OutlinedButton(onClick = { onTrash(item) }) {
                Text(text = stringResource(id = R.string.vault_trash_move_action))
            }
        }
    }
}

/**
 * How far the encryption of the file being imported has come.
 *
 * The percentage describes the source that is being read; it says nothing about what is written, and
 * it cannot: the object is authenticated as it is produced, and a bar that moved is not a file that
 * is in the vault. Only the index, read back afterwards, says that.
 */
@Composable
private fun ImportProgressCard(
    progress: VaultImportProgress?,
    modifier: Modifier = Modifier,
) {
    val total = progress?.totalBytes
    val processed = progress?.bytesProcessed ?: 0L

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(NivaraSpacing.screen),
            verticalArrangement = Arrangement.spacedBy(NivaraSpacing.small),
        ) {
            Text(
                text = stringResource(id = R.string.vault_import_progress_title),
                style = MaterialTheme.typography.titleMedium,
            )
            if (total != null && total > 0L) {
                LinearProgressIndicator(
                    progress = { (processed.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(
                        id = R.string.vault_import_progress_percent,
                        ((processed * 100) / total).toInt().coerceIn(0, 100),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                // A provider that reports no size cannot be turned into a percentage, and inventing
                // one would be a lie about a file the user is watching being encrypted.
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = stringResource(id = R.string.vault_import_progress_unknown),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
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
            modifier = Modifier.padding(NivaraSpacing.screen),
            verticalArrangement = Arrangement.spacedBy(NivaraSpacing.small),
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
        .padding(top = NivaraSpacing.tight)
        .clickable(onClick = onDismiss)

@Preview(name = "Vault – no root chosen", showBackground = true)
@Composable
private fun VaultNotConfiguredPreview() {
    NivaraTheme {
        VaultScreen(
            uiState = previewState(VaultState.NotConfigured),
            onChooseRoot = {},
            onImport = {},
            onOpenItem = {},
            onInitialize = {},
            onReplaceUnreadable = {},
            onRetry = {},
            onUnlock = {},
            onMessageShown = {},
            onSectionSelected = {},
            onSearchQueryChanged = {},
            onSearchCleared = {},
            onSortFieldSelected = {},
            onSortDirectionToggled = {},
            onAlbumOpened = {},
            onAlbumClosed = {},
            onAlbumCreated = {},
            onAlbumRenameStarted = {},
            onAlbumRenameCancelled = {},
            onAlbumRenameConfirmed = { _, _ -> },
            onAlbumDeleteRequested = {},
            onAlbumDeleteCancelled = {},
            onAlbumDeleteConfirmed = {},
            onAlbumItemsEditingChanged = {},
            onAlbumItemAdded = {},
            onAlbumItemRemoved = {},
            onTrashItem = {},
            onRestoreItem = {},
            onTrashSortFieldSelected = {},
            onTrashSortDirectionToggled = {},
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
            onImport = {},
            onOpenItem = {},
            onInitialize = {},
            onReplaceUnreadable = {},
            onRetry = {},
            onUnlock = {},
            onMessageShown = {},
            onSectionSelected = {},
            onSearchQueryChanged = {},
            onSearchCleared = {},
            onSortFieldSelected = {},
            onSortDirectionToggled = {},
            onAlbumOpened = {},
            onAlbumClosed = {},
            onAlbumCreated = {},
            onAlbumRenameStarted = {},
            onAlbumRenameCancelled = {},
            onAlbumRenameConfirmed = { _, _ -> },
            onAlbumDeleteRequested = {},
            onAlbumDeleteCancelled = {},
            onAlbumDeleteConfirmed = {},
            onAlbumItemsEditingChanged = {},
            onAlbumItemAdded = {},
            onAlbumItemRemoved = {},
            onTrashItem = {},
            onRestoreItem = {},
            onTrashSortFieldSelected = {},
            onTrashSortDirectionToggled = {},
        )
    }
}

@Preview(name = "Vault – unreadable", showBackground = true)
@Composable
private fun VaultUnreadablePreview() {
    NivaraTheme {
        VaultScreen(
            uiState = previewState(VaultState.Unreadable(VaultUnreadableReason.MetadataDamaged)),
            onChooseRoot = {},
            onImport = {},
            onOpenItem = {},
            onInitialize = {},
            onReplaceUnreadable = {},
            onRetry = {},
            onUnlock = {},
            onMessageShown = {},
            onSectionSelected = {},
            onSearchQueryChanged = {},
            onSearchCleared = {},
            onSortFieldSelected = {},
            onSortDirectionToggled = {},
            onAlbumOpened = {},
            onAlbumClosed = {},
            onAlbumCreated = {},
            onAlbumRenameStarted = {},
            onAlbumRenameCancelled = {},
            onAlbumRenameConfirmed = { _, _ -> },
            onAlbumDeleteRequested = {},
            onAlbumDeleteCancelled = {},
            onAlbumDeleteConfirmed = {},
            onAlbumItemsEditingChanged = {},
            onAlbumItemAdded = {},
            onAlbumItemRemoved = {},
            onTrashItem = {},
            onRestoreItem = {},
            onTrashSortFieldSelected = {},
            onTrashSortDirectionToggled = {},
        )
    }
}

/** A ready state for previews only. */
private fun previewState(vault: VaultState): VaultUiState.Ready = VaultUiState.Ready(
    vault = vault,
    sessionAuthenticated = true,
)
