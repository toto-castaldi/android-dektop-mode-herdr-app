package io.github.totocastaldi.herdrdesktop.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.totocastaldi.herdrdesktop.R
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.Space
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.Tab
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.WorkspaceNames
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.WorkspaceState

/** Actions on the core and the dialogs that ask before renaming or closing. */
internal class Controls(val actions: WorkspaceActions, val prompt: (Prompt) -> Unit)

/**
 * A selectable entry: shown [text], whether it is [selected], test tag ([tag], and `<tag>-menu` for its menu).
 * [bounded] entries share a width-limited row and shorten their text; the others take the width they need.
 */
internal class EntryModel(val text: String, val selected: Boolean, val tag: String, val bounded: Boolean = false)

@Composable
internal fun SpaceList(
    spaces: List<Space>,
    selected: String?,
    label: (Int, Space) -> String,
    controls: Controls,
    onSelect: (String) -> Unit
) {
    Column(Modifier.width(240.dp).fillMaxHeight().padding(8.dp), Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.workspace_spaces), style = MaterialTheme.typography.titleMedium)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(spaces, key = { _, s -> s.id }) { index, space ->
                val name = label(index, space)
                val actions = controls.actions
                Entry(
                    EntryModel(name, space.id == selected, "space-$index", bounded = true),
                    onClick = { onSelect(space.id) },
                    menu = listOf(
                        stringResource(R.string.workspace_rename) to { controls.prompt(Prompt.RenameSpace(space)) },
                        stringResource(R.string.workspace_move_up) to { actions.moveSpace(space.id, index - 1) },
                        stringResource(R.string.workspace_move_down) to { actions.moveSpace(space.id, index + 1) },
                        stringResource(R.string.workspace_close) to { controls.prompt(Prompt.CloseSpace(space, name)) }
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Button(onClick = controls.actions::createSpace) { Text(stringResource(R.string.workspace_new_space)) }
    }
}

@Composable
internal fun TabBar(
    space: Space,
    selected: String?,
    controls: Controls,
    onSelect: (String) -> Unit,
    onNewTab: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),
        Arrangement.spacedBy(4.dp),
        Alignment.CenterVertically
    ) {
        space.tabs.forEachIndexed { index, tab ->
            val label = WorkspaceNames.tabLabel(tab, index)
            val actions = controls.actions
            Entry(
                EntryModel(label, tab.id == selected, "tab-$index"),
                onClick = { onSelect(tab.id) },
                menu = listOf(
                    stringResource(R.string.workspace_rename) to { controls.prompt(Prompt.RenameTab(space.id, tab)) },
                    stringResource(R.string.workspace_move_left) to { actions.moveTab(space.id, tab.id, index - 1) },
                    stringResource(R.string.workspace_move_right) to { actions.moveTab(space.id, tab.id, index + 1) },
                    stringResource(R.string.workspace_close) to {
                        controls.prompt(Prompt.CloseTab(space.id, tab, label))
                    }
                )
            )
        }
        TextButton(onClick = onNewTab) { Text(stringResource(R.string.workspace_new_tab)) }
    }
}

/** A selectable entry with its menu, opened by the ⋮ button. */
@Composable
private fun Entry(
    model: EntryModel,
    onClick: () -> Unit,
    menu: List<Pair<String, () -> Unit>>,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val background = if (model.selected) colors.secondaryContainer else colors.surface
    Row(
        modifier.background(background, RoundedCornerShape(6.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Only the label selects: the menu button next to it must not.
        Text(
            model.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = (if (model.bounded) Modifier.weight(1f) else Modifier)
                .clickable(onClick = onClick)
                .testTag(model.tag)
                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)
        )
        Box {
            TextButton(onClick = { open = true }, modifier = Modifier.testTag("${model.tag}-menu")) { Text("⋮") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                menu.forEach { (label, action) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = {
                        open = false
                        action()
                    })
                }
            }
        }
    }
}
