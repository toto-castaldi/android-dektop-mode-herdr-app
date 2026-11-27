package io.github.totocastaldi.herdrdesktop.keyboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.totocastaldi.herdrdesktop.R

/** What each shortcut does, as the help panel says it. */
private val DESCRIPTIONS: Map<Shortcut, Int> = mapOf(
    Shortcut.NEW_SPACE to R.string.shortcut_new_space,
    Shortcut.CLOSE_SPACE to R.string.shortcut_close_space,
    Shortcut.NEXT_SPACE to R.string.shortcut_next_space,
    Shortcut.PREVIOUS_SPACE to R.string.shortcut_previous_space,
    Shortcut.NEW_TAB to R.string.shortcut_new_tab,
    Shortcut.CLOSE_TAB to R.string.shortcut_close_tab,
    Shortcut.NEXT_TAB to R.string.shortcut_next_tab,
    Shortcut.PREVIOUS_TAB to R.string.shortcut_previous_tab,
    Shortcut.SPLIT_RIGHT to R.string.shortcut_split_right,
    Shortcut.SPLIT_DOWN to R.string.shortcut_split_down,
    Shortcut.CLOSE_PANE to R.string.shortcut_close_pane,
    Shortcut.NEXT_PANE to R.string.shortcut_next_pane,
    Shortcut.PREVIOUS_PANE to R.string.shortcut_previous_pane,
    Shortcut.TOGGLE_SIDEBAR to R.string.shortcut_toggle_sidebar,
    Shortcut.HELP to R.string.shortcut_help
)

/** The help panel: every shortcut of the app, and the keys that always go to the terminal. */
@Composable
fun ShortcutHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("shortcut-help"),
        title = { Text(stringResource(R.string.shortcut_help_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), Arrangement.spacedBy(4.dp)) {
                Shortcuts.all.forEach { (shortcut, keyCode) ->
                    val description = DESCRIPTIONS[shortcut] ?: return@forEach
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            Shortcuts.label(keyCode),
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(150.dp)
                        )
                        Text(stringResource(description))
                    }
                }
                Text(
                    stringResource(R.string.shortcut_help_terminal),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } }
    )
}
