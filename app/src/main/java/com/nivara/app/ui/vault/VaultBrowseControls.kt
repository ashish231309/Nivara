package com.nivara.app.ui.vault

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nivara.app.R
import com.nivara.app.domain.vault.VaultOrdering
import com.nivara.app.domain.vault.VaultSortDirection
import com.nivara.app.domain.vault.VaultSortField
import com.nivara.app.domain.vault.VaultTrashOrdering
import com.nivara.app.domain.vault.VaultTrashSortField

/**
 * The vault screen's own controls: which collection is shown, what is searched for, and how the list
 * is ordered.
 *
 * All three are decisions about *drawing* the metadata that was read. None of them reads the vault,
 * writes anything, or opens a file: typing in the box filters what is already in memory, and choosing
 * an order rearranges it. That is why they are drawn above the list rather than inside it, and why
 * they are offered even when the list is empty.
 */
@Composable
internal fun VaultBrowseControls(
    section: VaultSection,
    searchQuery: String,
    ordering: VaultOrdering,
    trashOrdering: VaultTrashOrdering,
    busy: Boolean,
    onSectionSelected: (VaultSection) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onSearchCleared: () -> Unit,
    onSortFieldSelected: (VaultSortField) -> Unit,
    onSortDirectionToggled: () -> Unit,
    onTrashSortFieldSelected: (VaultTrashSortField) -> Unit,
    onTrashSortDirectionToggled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionButton(
                selected = section == VaultSection.AllItems,
                labelRes = R.string.vault_section_all_items,
                enabled = !busy,
                onClick = { onSectionSelected(VaultSection.AllItems) },
                modifier = Modifier.weight(1f),
            )
            SectionButton(
                selected = section == VaultSection.Albums,
                labelRes = R.string.vault_section_albums,
                enabled = !busy,
                onClick = { onSectionSelected(VaultSection.Albums) },
                modifier = Modifier.weight(1f),
            )
            SectionButton(
                selected = section == VaultSection.Trash,
                labelRes = R.string.vault_section_trash,
                enabled = !busy,
                onClick = { onSectionSelected(VaultSection.Trash) },
                modifier = Modifier.weight(1f),
            )
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChanged,
            enabled = !busy,
            singleLine = true,
            label = { Text(text = stringResource(id = R.string.vault_search_label)) },
            placeholder = { Text(text = stringResource(id = R.string.vault_search_hint)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    OutlinedButton(
                        onClick = onSearchCleared,
                        enabled = !busy,
                    ) {
                        Text(text = stringResource(id = R.string.vault_search_clear))
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        // The trash has a field the vault's list does not — when the file was moved there — so the
        // control shows the fields that make sense for the collection being looked at.
        if (section == VaultSection.Trash) {
            TrashSortControls(
                ordering = trashOrdering,
                busy = busy,
                onSortFieldSelected = onTrashSortFieldSelected,
                onSortDirectionToggled = onTrashSortDirectionToggled,
            )
        } else {
            SortControls(
                ordering = ordering,
                busy = busy,
                onSortFieldSelected = onSortFieldSelected,
                onSortDirectionToggled = onSortDirectionToggled,
            )
        }
    }
}

/**
 * The order the trash list is drawn in.
 *
 * The same shape as the vault's own sort control, with the one field that belongs to the trash:
 * when the file was moved out of the active collection.
 */
@Composable
private fun TrashSortControls(
    ordering: VaultTrashOrdering,
    busy: Boolean,
    onSortFieldSelected: (VaultTrashSortField) -> Unit,
    onSortDirectionToggled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(id = R.string.vault_sort_field_label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            VaultTrashSortField.entries.forEach { field ->
                val selected = ordering.field == field
                if (selected) {
                    Button(onClick = { onSortFieldSelected(field) }, enabled = !busy) {
                        Text(text = stringResource(id = field.trashLabelRes()))
                    }
                } else {
                    OutlinedButton(onClick = { onSortFieldSelected(field) }, enabled = !busy) {
                        Text(text = stringResource(id = field.trashLabelRes()))
                    }
                }
            }
            OutlinedButton(onClick = onSortDirectionToggled, enabled = !busy, modifier = Modifier.padding(start = 8.dp)) {
                Text(text = stringResource(id = ordering.direction.labelRes()))
            }
        }
    }
}

/** The name of a trash sort field, in the words this screen uses for it. */
private fun VaultTrashSortField.trashLabelRes(): Int = when (this) {
    VaultTrashSortField.TrashedAt -> R.string.vault_trash_sort_by_trashed
    VaultTrashSortField.Name -> R.string.vault_sort_by_name
    VaultTrashSortField.Size -> R.string.vault_sort_by_size
    VaultTrashSortField.ImportedAt -> R.string.vault_sort_by_imported
    VaultTrashSortField.Kind -> R.string.vault_sort_by_type
}

/**
 * One of the two collections.
 *
 * The chosen one is a filled button and the other is an outline, so which list is on screen is
 * answered by the shape as well as by the label.
 */
@Composable
private fun SectionButton(
    selected: Boolean,
    labelRes: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(onClick = onClick, enabled = enabled, modifier = modifier) {
            Text(text = stringResource(id = labelRes))
        }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) {
            Text(text = stringResource(id = labelRes))
        }
    }
}

/**
 * The order the list is drawn in.
 *
 * The field buttons scroll sideways rather than wrapping or hiding behind a menu: four fields, four
 * taps, and no second surface a person has to open to change how their files are arranged.
 */
@Composable
private fun SortControls(
    ordering: VaultOrdering,
    busy: Boolean,
    onSortFieldSelected: (VaultSortField) -> Unit,
    onSortDirectionToggled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(id = R.string.vault_sort_field_label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            VaultSortField.entries.forEach { field ->
                val selected = ordering.field == field
                if (selected) {
                    Button(onClick = { onSortFieldSelected(field) }, enabled = !busy) {
                        Text(text = stringResource(id = field.labelRes()))
                    }
                } else {
                    OutlinedButton(onClick = { onSortFieldSelected(field) }, enabled = !busy) {
                        Text(text = stringResource(id = field.labelRes()))
                    }
                }
            }
            // One control, labelled with the direction it is currently drawing: tapping it reverses
            // the order, and the label says which way that is before the tap, not after it.
            OutlinedButton(onClick = onSortDirectionToggled, enabled = !busy, modifier = Modifier.padding(start = 8.dp)) {
                Text(text = stringResource(id = ordering.direction.labelRes()))
            }
        }
    }
}

/** The name of a sort field, in the words the vault's list has always used. */
private fun VaultSortField.labelRes(): Int = when (this) {
    VaultSortField.Name -> R.string.vault_sort_by_name
    VaultSortField.Size -> R.string.vault_sort_by_size
    VaultSortField.ImportedAt -> R.string.vault_sort_by_imported
    VaultSortField.Kind -> R.string.vault_sort_by_type
}

/** The direction currently in effect, and the one a tap on the control produces. */
private fun VaultSortDirection.labelRes(): Int = when (this) {
    VaultSortDirection.Ascending -> R.string.vault_sort_direction_ascending
    VaultSortDirection.Descending -> R.string.vault_sort_direction_descending
}

/**
 * What the search box found, when there is something to say about it.
 *
 * The one thing this draws is the difference between an answer and no answer: "nothing matches" is
 * only ever shown for a list that was read, and a list that could not be read says so instead.
 */
@Composable
internal fun VaultSearchResultCard(
    section: VaultSection,
    search: VaultSearchUiState,
    searchQuery: String,
    matchCount: Int?,
    totalCount: Int?,
    modifier: Modifier = Modifier,
) {
    if (search == VaultSearchUiState.NotAsked) return

    // The question was about files, albums or the trash, and the answer is worded for the collection
    // the person is actually looking at.
    val albumsSurface = section == VaultSection.Albums
    val trashSurface = section == VaultSection.Trash

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
            when (search) {
                VaultSearchUiState.Matches -> if (matchCount != null && totalCount != null) {
                    Text(
                        text = stringResource(
                            id = when {
                                albumsSurface -> R.string.vault_search_albums_result_format
                                trashSurface -> R.string.vault_trash_search_result_format
                                else -> R.string.vault_search_result_format
                            },
                            matchCount,
                            totalCount,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                VaultSearchUiState.NoMatches -> if (trashSurface) {
                    Text(
                        text = stringResource(id = R.string.vault_trash_search_no_matches, searchQuery.trim()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        text = stringResource(
                            id = if (albumsSurface) {
                                R.string.vault_search_no_album_matches
                            } else {
                                R.string.vault_search_no_matches
                            },
                            searchQuery.trim(),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                VaultSearchUiState.CannotSearch -> Text(
                    text = stringResource(
                        id = when {
                            albumsSurface -> R.string.vault_search_cannot_search_albums
                            trashSurface -> R.string.vault_trash_search_cannot_search
                            else -> R.string.vault_search_cannot_search
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )

                VaultSearchUiState.NotAsked -> Unit
            }
        }
    }
}
