package io.github.totocastaldi.herdrdesktop.workspace

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.github.totocastaldi.herdrdesktop.R
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.Space
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.Tab

/** Pending dialog: rename, or confirm closing (which ends terminals on the core). */
internal sealed interface Prompt {
    data class RenameSpace(val space: Space) : Prompt

    data class RenameTab(val spaceId: String, val tab: Tab) : Prompt

    data class CloseSpace(val space: Space, val label: String) : Prompt

    data class CloseTab(val spaceId: String, val tab: Tab, val label: String) : Prompt
}

@Composable
internal fun PromptDialog(prompt: Prompt, actions: WorkspaceActions, dismiss: () -> Unit) {
    when (prompt) {
        is Prompt.RenameSpace -> RenameDialog(prompt.space.name, dismiss) {
            actions.renameSpace(prompt.space.id, it)
        }

        is Prompt.RenameTab -> RenameDialog(prompt.tab.name, dismiss) {
            actions.renameTab(prompt.spaceId, prompt.tab.id, it)
        }

        is Prompt.CloseSpace -> ConfirmDialog(
            stringResource(R.string.workspace_close_space_title, prompt.label),
            stringResource(R.string.workspace_close_space_text),
            dismiss
        ) { actions.closeSpace(prompt.space.id) }

        is Prompt.CloseTab -> ConfirmDialog(
            stringResource(R.string.workspace_close_tab_title, prompt.label),
            stringResource(R.string.workspace_close_tab_text),
            dismiss
        ) { actions.closeTab(prompt.spaceId, prompt.tab.id) }
    }
}

@Composable
private fun RenameDialog(current: String, dismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.workspace_rename)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.field_name)) },
                supportingText = { Text(stringResource(R.string.workspace_rename_hint)) },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onRename(name)
                dismiss()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun ConfirmDialog(title: String, text: String, dismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                dismiss()
            }) { Text(stringResource(R.string.workspace_close)) }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
