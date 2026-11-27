@file:Suppress("MatchingDeclarationName")

package io.github.totocastaldi.herdrdesktop.workspace

import android.content.ClipData
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import io.github.totocastaldi.herdrdesktop.R
import io.github.totocastaldi.herdrdesktop.terminal.TerminalSession
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** An entry of a terminal's context menu after copy and paste, such as an action of its pane. */
internal class TerminalMenuAction(val label: String, val tag: String, val onClick: () -> Unit)

/**
 * The context menu of [session]'s terminal (REQ-DM-003), open at [position] in the pane while it is not null:
 * copy the selection to the clipboard, paste the clipboard's text into the program, then [actions]. Clipboard
 * and selection contents never reach the log.
 */
@Composable
internal fun TerminalContextMenu(
    session: TerminalSession,
    position: Offset?,
    onDismiss: () -> Unit,
    actions: List<TerminalMenuAction> = emptyList()
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val at = position ?: Offset.Zero
    // A point at the click, so that the menu opens under the pointer.
    Box(Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }) {
        DropdownMenu(expanded = position != null, onDismissRequest = onDismiss) {
            val selection = if (position != null) session.selectionText() else null
            DropdownMenuItem(
                text = { Text(stringResource(R.string.terminal_menu_copy)) },
                enabled = !selection.isNullOrEmpty(),
                modifier = Modifier.testTag("terminal-menu-copy"),
                onClick = {
                    onDismiss()
                    selection?.let { text ->
                        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("terminal", text))) }
                    }
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.terminal_menu_paste)) },
                modifier = Modifier.testTag("terminal-menu-paste"),
                onClick = {
                    onDismiss()
                    scope.launch {
                        val clip = clipboard.getClipEntry()?.clipData
                        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                        if (!text.isNullOrEmpty()) session.paste(text)
                    }
                }
            )
            if (actions.isNotEmpty()) HorizontalDivider()
            actions.forEach { action ->
                DropdownMenuItem(
                    text = { Text(action.label) },
                    modifier = Modifier.testTag(action.tag),
                    onClick = {
                        onDismiss()
                        action.onClick()
                    }
                )
            }
        }
    }
}
