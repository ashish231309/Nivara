package com.nivara.app.ui.vault

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nivara.app.R
import com.nivara.app.domain.vault.VaultItemId
import com.nivara.app.domain.vault.VaultTrashItemStatus

/**
 * The trash, in one card.
 *
 * ### What the card says
 *
 * The trash record's state first, in its own words, and every unreadable state says what was *not*
 * done — nothing deleted, nothing replaced — because the reading this screen must never allow is that
 * a record which cannot be opened means the files are gone. When the record is readable the card draws
 * its entries, each with the file's details as the vault's list holds them and one action: restore.
 *
 * ### What it deliberately does not offer
 *
 * There is no permanent deletion, no "empty trash" and no expiry: moving a file to trash is a state
 * change, and the only way out of it in Nivara is restoring the file. That is why a row has one button
 * and nothing else, and why the copy says the file is still in the vault.
 */
@Composable
internal fun VaultTrashCard(
    trash: VaultTrashUiState,
    busy: Boolean,
    onRestore: (VaultItemId) -> Unit,
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val count = (trash as? VaultTrashUiState.Trashed)?.items?.size
            Text(
                text = if (count != null) {
                    stringResource(id = R.string.vault_trash_count_title, count)
                } else {
                    stringResource(id = R.string.vault_trash_title)
                },
                style = MaterialTheme.typography.titleMedium,
            )

            trash.titleRes()?.let { titleRes ->
                Text(
                    text = stringResource(id = titleRes),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            trash.bodyRes()?.let { bodyRes ->
                Text(text = stringResource(id = bodyRes), style = MaterialTheme.typography.bodyMedium)
            }

            when (trash) {
                VaultTrashUiState.Empty -> Text(
                    text = stringResource(id = R.string.vault_trash_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )

                is VaultTrashUiState.Trashed -> if (trash.items.isEmpty()) {
                    // A readable record with entries none of which match the search box: the search
                    // card above has already said so, and this line keeps the card from looking empty
                    // by accident.
                    Text(
                        text = stringResource(id = R.string.vault_trash_empty),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    trash.items.forEach { item ->
                        VaultTrashRow(item = item, busy = busy, onRestore = onRestore)
                    }
                }

                else -> Unit
            }

            if (trash.acceptsChanges) {
                Text(
                    text = stringResource(id = R.string.vault_trash_explanation),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * One trashed file: what it was called, what it is, how large it is, when it was moved, and the one
 * action that takes it back out of the trash.
 *
 * The details come from the vault's list, not from the trash record, so a file that has been renamed
 * in the vault shows the name it has now. When the list cannot provide them the row says which of the
 * two reasons applies — the file is no longer listed, or the list cannot be read — instead of drawing
 * a row that looks like a file Nivara holds.
 *
 * The row is deliberately not clickable: a trashed file is not opened through normal browsing, and this
 * version offers restore only.
 */
@Composable
private fun VaultTrashRow(
    item: VaultTrashItemUi,
    busy: Boolean,
    onRestore: (VaultItemId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val trashed = DateUtils.getRelativeTimeSpanString(item.trashedAtEpochMillis)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = item.item?.name ?: stringResource(id = R.string.vault_trash_row_unknown_title),
            style = MaterialTheme.typography.bodyLarge,
        )
        item.item?.let { resolved ->
            val kind = stringResource(id = vaultItemTypeRes(resolved))
            val size = Formatter.formatShortFileSize(context, resolved.sizeBytes)
            Text(
                text = stringResource(id = R.string.vault_item_details_format, kind, size),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        when (item.status) {
            VaultTrashItemStatus.NoLongerInVault -> Text(
                text = stringResource(id = R.string.vault_trash_row_missing_from_list),
                style = MaterialTheme.typography.bodySmall,
            )

            VaultTrashItemStatus.VaultListUnreadable -> Text(
                text = stringResource(id = R.string.vault_trash_row_list_unreadable),
                style = MaterialTheme.typography.bodySmall,
            )

            VaultTrashItemStatus.InVault -> Unit
        }
        if (item.contentMissing) {
            Text(
                text = stringResource(id = R.string.vault_trash_row_content_missing),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            text = stringResource(id = R.string.vault_trash_row_trashed_format, trashed),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(
            onClick = { onRestore(item.id) },
            enabled = !busy,
        ) {
            Text(text = stringResource(id = R.string.vault_trash_restore_action))
        }
    }
}
