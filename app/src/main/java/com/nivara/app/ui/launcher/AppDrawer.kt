package com.nivara.app.ui.launcher

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nivara.app.R
import com.nivara.app.domain.app.InstalledApplication
import com.nivara.app.ui.applications.ApplicationIconLoader
import com.nivara.app.ui.applications.ApplicationSortOrder
import com.nivara.app.ui.applications.NivaraApplicationIcon

/**
 * The app drawer: the applications Nivara may show, and the controls for finding one.
 *
 * A grid rather than a list because that is what an application drawer is, and because the layout
 * keeps working when Stage 19 adds whatever it adds to it. The grid is adaptive, so a small phone
 * shows two columns and a tablet several without a second layout.
 *
 * ### What a cell is
 *
 * The icon if the device can produce one, the label, and the whole cell as the tap target. The
 * package name is the identity the tap carries and is never drawn; the icon is decoration and is
 * never the identity. A cell whose icon cannot be produced still works, which is the point of the
 * placeholder.
 *
 * ### What it does not do
 *
 * It draws what it is given and reports taps. It does not decide what is visible — [state] came from
 * the view model, whose list came from the domain's rule — and it knows nothing about hiding beyond
 * the copy it is handed.
 */
@Composable
fun AppDrawer(
    state: LauncherUiState.Ready,
    iconLoader: ApplicationIconLoader,
    onQueryChange: (String) -> Unit,
    onSortChange: (ApplicationSortOrder) -> Unit,
    onSectionChange: (LauncherSection) -> Unit,
    onLaunch: (InstalledApplication) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(text = stringResource(id = R.string.launcher_drawer_search_label)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )

        // The hidden section only exists while a reveal does. Offering it the rest of the time would
        // suggest the user can browse what is hidden, which is exactly what they cannot do.
        if (state.revealed) {
            Text(
                text = stringResource(id = R.string.launcher_reveal_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            SelectableRow(
                labelRes = R.string.launcher_drawer_all,
                selected = state.section == LauncherSection.All,
                onSelect = { onSectionChange(LauncherSection.All) },
            )
            SelectableRow(
                labelRes = R.string.launcher_drawer_hidden,
                selected = state.section == LauncherSection.Hidden,
                onSelect = { onSectionChange(LauncherSection.Hidden) },
            )
        }

        SelectableRow(
            labelRes = R.string.launcher_drawer_sort_name_ascending,
            selected = state.sort == ApplicationSortOrder.NameAscending,
            onSelect = { onSortChange(ApplicationSortOrder.NameAscending) },
        )
        SelectableRow(
            labelRes = R.string.launcher_drawer_sort_name_descending,
            selected = state.sort == ApplicationSortOrder.NameDescending,
            onSelect = { onSortChange(ApplicationSortOrder.NameDescending) },
        )

        if (state.entries.isEmpty()) {
            val emptyMessageRes = when (state.emptiness) {
                LauncherListEmptiness.DeviceHasNoApplications -> R.string.launcher_empty_device
                LauncherListEmptiness.AllApplicationsHidden -> R.string.launcher_empty_all_hidden
                LauncherListEmptiness.NoSearchResults -> R.string.launcher_empty_search
                LauncherListEmptiness.NothingHidden -> R.string.launcher_empty_hidden
                null -> null
            }
            if (emptyMessageRes != null) {
                Text(
                    text = stringResource(id = emptyMessageRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 88.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items = state.entries, key = { application -> application.packageName }) { application ->
                ApplicationCell(
                    application = application,
                    iconLoader = iconLoader,
                    onLaunch = { onLaunch(application) },
                )
            }
        }
    }
}

/**
 * One application in the drawer.
 *
 * The tap target is the whole cell, and its accessible name is the application's label, so a screen
 * reader announces the application and that it can be activated — no package name, no icon
 * description, and nothing that would make the icon the identity.
 */
@Composable
private fun ApplicationCell(
    application: InstalledApplication,
    iconLoader: ApplicationIconLoader,
    onLaunch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val icon = rememberApplicationIcon(iconLoader, application.packageName)
    val label = application.label

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = label, onClick = onLaunch)
            .semantics { contentDescription = label }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        NivaraApplicationIcon(icon = icon)
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One option of an exclusive group: the whole row is the target, which is what a screen reader expects. */
@Composable
private fun SelectableRow(
    labelRes: Int,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The label is the semantics; the radio button itself is decoration inside the row.
        RadioButton(selected = selected, onClick = null)
        Text(text = stringResource(id = labelRes), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Loads one cell's icon, once per package name for as long as the cell is composed. */
@Composable
private fun rememberApplicationIcon(
    loader: ApplicationIconLoader,
    packageName: String,
): ImageBitmap? {
    val icon by produceState<ImageBitmap?>(null, loader, packageName) {
        value = loader.iconFor(packageName)
    }
    return icon
}
