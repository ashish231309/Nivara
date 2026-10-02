package com.nivara.app.ui.vault

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nivara.app.R
import com.nivara.app.ui.components.NivaraSpacing
import com.nivara.app.domain.vault.VaultAlbumId
import com.nivara.app.domain.vault.VaultItemId

/**
 * The albums: one list a person keeps inside the vault.
 *
 * ### What an album is, on screen
 *
 * A title, when it was made, and the files it names — which are the vault's files, drawn with the same
 * rows as the vault's own list because they are the same rows of the same authenticated metadata. An
 * album holds nothing else: not a copy of a name, not a size, not a file. That is why creating one is
 * instant and why deleting one cannot cost anything.
 *
 * ### What is never drawn
 *
 * A record that cannot be read is drawn as a record that cannot be read, with the albums it may still
 * hold left alone; it is never drawn as "no albums". A reference to a file the vault's list does not
 * have is drawn as exactly that, kept in place, and only the person can remove it. Nothing here offers
 * to delete a file, and deleting an album says in as many words that the files are untouched.
 */
@Composable
internal fun VaultAlbumsCard(
    organization: VaultOrganizationUiState,
    openAlbum: VaultAlbumDetailUi?,
    index: VaultIndexUiState,
    renamingAlbumId: VaultAlbumId?,
    confirmingDeleteId: VaultAlbumId?,
    editingItems: Boolean,
    busy: Boolean,
    searchActive: Boolean,
    onCreate: (String) -> Unit,
    onOpen: (VaultAlbumId) -> Unit,
    onClose: () -> Unit,
    onRenameStarted: (VaultAlbumId) -> Unit,
    onRenameCancelled: () -> Unit,
    onRenameConfirmed: (VaultAlbumId, String) -> Unit,
    onDeleteRequested: (VaultAlbumId) -> Unit,
    onDeleteCancelled: () -> Unit,
    onDeleteConfirmed: (VaultAlbumId) -> Unit,
    onEditingChanged: (Boolean) -> Unit,
    onAddItem: (VaultItemId) -> Unit,
    onRemoveItem: (VaultItemId) -> Unit,
    onOpenItem: (VaultItemUi) -> Unit,
    onTrashItem: ((VaultItemUi) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // While the vault itself is unopened the vault's own card is the whole story; there is nothing to
    // add to it here.
    if (organization is VaultOrganizationUiState.VaultNotReady ||
        organization is VaultOrganizationUiState.Loading
    ) {
        return
    }

    if (openAlbum != null) {
        VaultAlbumDetailCard(
            detail = openAlbum,
            index = index,
            editingItems = editingItems,
            busy = busy,
            onClose = onClose,
            onEditingChanged = onEditingChanged,
            onAddItem = onAddItem,
            onRemoveItem = onRemoveItem,
            onOpenItem = onOpenItem,
            onTrashItem = onTrashItem,
            modifier = modifier,
        )
        return
    }

    VaultAlbumListCard(
        organization = organization,
        renamingAlbumId = renamingAlbumId,
        confirmingDeleteId = confirmingDeleteId,
        busy = busy,
        searchActive = searchActive,
        onCreate = onCreate,
        onOpen = onOpen,
        onRenameStarted = onRenameStarted,
        onRenameCancelled = onRenameCancelled,
        onRenameConfirmed = onRenameConfirmed,
        onDeleteRequested = onDeleteRequested,
        onDeleteCancelled = onDeleteCancelled,
        onDeleteConfirmed = onDeleteConfirmed,
        modifier = modifier,
    )
}

/** The albums themselves, with the one field that makes a new one. */
@Composable
private fun VaultAlbumListCard(
    organization: VaultOrganizationUiState,
    renamingAlbumId: VaultAlbumId?,
    confirmingDeleteId: VaultAlbumId?,
    busy: Boolean,
    searchActive: Boolean,
    onCreate: (String) -> Unit,
    onOpen: (VaultAlbumId) -> Unit,
    onRenameStarted: (VaultAlbumId) -> Unit,
    onRenameCancelled: () -> Unit,
    onRenameConfirmed: (VaultAlbumId, String) -> Unit,
    onDeleteRequested: (VaultAlbumId) -> Unit,
    onDeleteCancelled: () -> Unit,
    onDeleteConfirmed: (VaultAlbumId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val albums = (organization as? VaultOrganizationUiState.Albums)?.albums ?: emptyList()
    val titleRes = organization.titleRes()
    val bodyRes = organization.bodyRes()

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
            if (titleRes != null) {
                Text(text = stringResource(id = titleRes), style = MaterialTheme.typography.titleMedium)
            }
            if (bodyRes != null) {
                Text(text = stringResource(id = bodyRes), style = MaterialTheme.typography.bodyMedium)
            }

            // "No albums yet" is only said when there are none and nothing is filtering them: a
            // search that found no album has its own sentence above, and saying both would suggest the
            // vault is empty when it merely did not match.
            if (albums.isEmpty() && !searchActive) {
                Text(
                    text = stringResource(id = R.string.vault_albums_none),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // A new album is only offered where the record can be read. Where it cannot be, the copy
            // above explains why nothing is being written, and the files are all still there.
            if (organization.acceptsChanges) {
                AlbumNameForm(
                    labelRes = R.string.vault_album_create_label,
                    fieldLabelRes = R.string.vault_album_name_label,
                    confirmRes = R.string.vault_album_create_action,
                    busy = busy,
                    onConfirm = onCreate,
                )
            }

            albums.forEach { album ->
                AlbumRow(
                    album = album,
                    renaming = renamingAlbumId == album.id,
                    confirmingDelete = confirmingDeleteId == album.id,
                    busy = busy,
                    onOpen = onOpen,
                    onRenameStarted = onRenameStarted,
                    onRenameCancelled = onRenameCancelled,
                    onRenameConfirmed = onRenameConfirmed,
                    onDeleteRequested = onDeleteRequested,
                    onDeleteCancelled = onDeleteCancelled,
                    onDeleteConfirmed = onDeleteConfirmed,
                )
            }
        }
    }
}

/**
 * One album in the list.
 *
 * The title opens the album; renaming and deleting are separate taps, and deleting asks first. The
 * confirmation says what a deletion does and — more importantly — what it does not do, because an
 * album is a list and the files in it are not in it.
 */
@Composable
private fun AlbumRow(
    album: VaultAlbumUi,
    renaming: Boolean,
    confirmingDelete: Boolean,
    busy: Boolean,
    onOpen: (VaultAlbumId) -> Unit,
    onRenameStarted: (VaultAlbumId) -> Unit,
    onRenameCancelled: () -> Unit,
    onRenameConfirmed: (VaultAlbumId, String) -> Unit,
    onDeleteRequested: (VaultAlbumId) -> Unit,
    onDeleteCancelled: () -> Unit,
    onDeleteConfirmed: (VaultAlbumId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NivaraSpacing.tight),
    ) {
        if (!renaming) {
            Text(
                text = album.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        enabled = !busy,
                        onClickLabel = stringResource(id = R.string.vault_album_open_action),
                    ) { onOpen(album.id) },
            )
        }

        Text(
            text = stringResource(
                id = R.string.vault_album_created_format,
                DateUtils.getRelativeTimeSpanString(album.createdAtEpochMillis),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = album.memberCountText(),
            style = MaterialTheme.typography.bodySmall,
        )

        if (renaming) {
            AlbumNameForm(
                labelRes = null,
                fieldLabelRes = R.string.vault_album_name_label,
                confirmRes = R.string.vault_album_rename_confirm,
                initialName = album.name,
                busy = busy,
                onConfirm = { name -> onRenameConfirmed(album.id, name) },
                onCancel = onRenameCancelled,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(NivaraSpacing.small)) {
                OutlinedButton(
                    onClick = { onRenameStarted(album.id) },
                    enabled = !busy,
                ) {
                    Text(text = stringResource(id = R.string.vault_album_rename_action))
                }
                OutlinedButton(
                    onClick = { onDeleteRequested(album.id) },
                    enabled = !busy,
                ) {
                    Text(text = stringResource(id = R.string.vault_album_delete_action))
                }
            }
        }

        if (confirmingDelete) {
            Text(
                text = stringResource(id = R.string.vault_album_delete_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(NivaraSpacing.small)) {
                Button(
                    onClick = { onDeleteConfirmed(album.id) },
                    enabled = !busy,
                ) {
                    Text(text = stringResource(id = R.string.vault_album_delete_confirm))
                }
                OutlinedButton(
                    onClick = onDeleteCancelled,
                    enabled = !busy,
                ) {
                    Text(text = stringResource(id = R.string.vault_album_cancel_action))
                }
            }
        }
    }
}

/**
 * One album, open.
 *
 * Its items are the vault's items, so selecting one opens it through the same viewer the vault's own
 * list opens. What the album adds is the stale line: a reference the vault's list no longer names is
 * shown in place, with the one action that can remove it — an explicit decision, never a repair that
 * happens on its own.
 */
@Composable
private fun VaultAlbumDetailCard(
    detail: VaultAlbumDetailUi,
    index: VaultIndexUiState,
    editingItems: Boolean,
    busy: Boolean,
    onClose: () -> Unit,
    onEditingChanged: (Boolean) -> Unit,
    onAddItem: (VaultItemId) -> Unit,
    onRemoveItem: (VaultItemId) -> Unit,
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
            OutlinedButton(onClick = onClose, enabled = !busy) {
                Text(text = stringResource(id = R.string.vault_album_back_action))
            }

            Text(text = detail.album.name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(
                    id = R.string.vault_album_created_format,
                    DateUtils.getRelativeTimeSpanString(detail.album.createdAtEpochMillis),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(text = detail.album.memberCountText(), style = MaterialTheme.typography.bodySmall)

            when (val contents = detail.contents) {
                is VaultAlbumContentsUi.Unresolved -> Text(
                    text = stringResource(id = R.string.vault_album_unresolved),
                    style = MaterialTheme.typography.bodyMedium,
                )

                is VaultAlbumContentsUi.Resolved -> {
                    if (contents.items.isEmpty() &&
                        contents.staleItemIds.isEmpty() &&
                        contents.trashedItemIds.isEmpty()
                    ) {
                        Text(
                            text = stringResource(id = R.string.vault_album_empty),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else if (contents.items.isEmpty() && contents.staleItemIds.isEmpty()) {
                        // The album's members exist, but every one of them is out of the active
                        // collection: the album is not empty, it is out of sight.
                        Text(
                            text = stringResource(id = R.string.vault_album_all_trashed),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else if (contents.items.isEmpty()) {
                        Text(
                            text = stringResource(id = R.string.vault_album_no_matches),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    contents.items.forEach { item ->
                        VaultItemRow(item = item, onOpen = onOpenItem, onTrash = onTrashItem)
                    }

                    contents.staleItemIds.forEach { itemId ->
                        StaleAlbumItem(
                            busy = busy,
                            onRemove = { onRemoveItem(itemId) },
                        )
                    }
                }
            }

            // Adding is offered only where the vault's list can be read: the surface adds files the
            // index knows about, and it never rescans storage to discover more.
            if (index is VaultIndexUiState.Indexed) {
                OutlinedButton(
                    onClick = { onEditingChanged(!editingItems) },
                    enabled = !busy,
                ) {
                    Text(
                        text = stringResource(
                            id = if (editingItems) {
                                R.string.vault_album_manage_items_done
                            } else {
                                R.string.vault_album_manage_items_action
                            },
                        ),
                    )
                }
            }

            if (editingItems && index is VaultIndexUiState.Indexed) {
                Text(
                    text = stringResource(id = R.string.vault_album_manage_items_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                index.items.forEach { item ->
                    VaultAlbumItemEditor(
                        item = item,
                        inAlbum = item.id in detail.memberItemIds,
                        busy = busy,
                        onAdd = { onAddItem(item.id) },
                        onRemove = { onRemoveItem(item.id) },
                    )
                }
            }
        }
    }
}

/**
 * A reference the vault's list no longer names.
 *
 * It is kept, shown, and explained — and it is never removed except by the tap below.
 */
@Composable
private fun StaleAlbumItem(
    busy: Boolean,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NivaraSpacing.hairline),
    ) {
        Text(
            text = stringResource(id = R.string.vault_album_stale_title),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(id = R.string.vault_album_stale_body),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = onRemove, enabled = !busy) {
            Text(text = stringResource(id = R.string.vault_album_stale_forget_action))
        }
    }
}

/**
 * One of the vault's files, seen from inside an album: in it, or not.
 *
 * The state comes from the album record, not from the drawn list, so searching while this is open
 * changes which files are *offered* and never which ones the album is said to hold.
 */
@Composable
private fun VaultAlbumItemEditor(
    item: VaultItemUi,
    inAlbum: Boolean,
    busy: Boolean,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NivaraSpacing.small),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = item.name, style = MaterialTheme.typography.bodyLarge)
            if (inAlbum) {
                Text(
                    text = stringResource(id = R.string.vault_album_in_album),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (inAlbum) {
            OutlinedButton(onClick = onRemove, enabled = !busy) {
                Text(text = stringResource(id = R.string.vault_album_remove_item_action))
            }
        } else {
            OutlinedButton(onClick = onAdd, enabled = !busy) {
                Text(text = stringResource(id = R.string.vault_album_add_item_action))
            }
        }
    }
}

/**
 * A field for an album's title, with the action that uses it.
 *
 * The field is local to this composable and is emptied — or, for a rename, comes back holding the
 * current name — once the action is taken, so a title that was already used is not left sitting in a
 * box that would create a second album with it.
 */
@Composable
private fun AlbumNameForm(
    labelRes: Int?,
    fieldLabelRes: Int,
    confirmRes: Int,
    busy: Boolean,
    onConfirm: (String) -> Unit,
    initialName: String? = null,
    onCancel: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName.orEmpty()) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NivaraSpacing.tight),
    ) {
        if (labelRes != null) {
            Text(
                text = stringResource(id = labelRes),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        OutlinedTextField(
            value = name,
            onValueChange = { typed -> name = typed },
            enabled = !busy,
            singleLine = true,
            label = { Text(text = stringResource(id = fieldLabelRes)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(NivaraSpacing.small)) {
            Button(
                onClick = {
                    val title = name
                    // Cleared before the change is reported: if the vault refuses the title, the copy
                    // explains why, and an empty box is a better invitation to try again than the same
                    // rejected title.
                    name = ""
                    onConfirm(title)
                },
                enabled = !busy && name.isNotEmpty(),
            ) {
                Text(text = stringResource(id = confirmRes))
            }
            onCancel?.let { cancel ->
                OutlinedButton(onClick = cancel, enabled = !busy) {
                    Text(text = stringResource(id = R.string.vault_album_cancel_action))
                }
            }
        }
    }
}

/**
 * An album's title, its item count, and — when the vault's list could not be resolved — the honest
 * version of the same sentence.
 */
@Composable
private fun VaultAlbumUi.memberCountText(): String = if (resolved) {
    when {
        staleCount > 0 && trashedCount > 0 -> stringResource(
            id = R.string.vault_album_member_count_partial_trashed_format,
            memberCount,
            staleCount,
            trashedCount,
        )

        staleCount > 0 -> stringResource(
            id = R.string.vault_album_member_count_partial_format,
            memberCount,
            staleCount,
        )

        trashedCount > 0 -> stringResource(
            id = R.string.vault_album_member_count_trashed_format,
            memberCount,
            trashedCount,
        )

        else -> stringResource(id = R.string.vault_album_member_count_format, memberCount)
    }
} else {
    stringResource(id = R.string.vault_album_count_unresolved_format, memberCount)
}
