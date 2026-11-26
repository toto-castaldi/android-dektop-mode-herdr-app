package io.github.totocastaldi.herdrdesktop.machines

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.totocastaldi.herdrdesktop.AppContainer
import io.github.totocastaldi.herdrdesktop.R
import io.github.totocastaldi.herdrdesktop.coreclient.connection.MachineStatus
import io.github.totocastaldi.herdrdesktop.coreclient.keys.SshPublicKey
import io.github.totocastaldi.herdrdesktop.coreclient.machine.LocalMachine

/** Dialog of the local machine currently open, if any. */
class LocalUiState {
    var dialog by mutableStateOf<LocalDialog?>(null)
}

enum class LocalDialog { SETUP, RESET }

/** Actions of the local machine row: they open the Linux Terminal, Android settings or a dialog. */
@Composable
fun rememberLocalActions(container: AppContainer, ui: LocalUiState): LocalActions {
    val context = LocalContext.current
    val openFailed = stringResource(R.string.local_open_failed)
    return remember(container, ui) {
        val opened = { ok: Boolean -> if (!ok) Toast.makeText(context, openFailed, Toast.LENGTH_SHORT).show() }
        LocalActions(
            openTerminal = {
                val ok = container.linuxTerminal.open()
                opened(ok)
                if (ok) container.connections.localTerminalOpened()
            },
            terminalSettings = { opened(container.linuxTerminal.openSettings()) },
            developerOptions = { opened(container.linuxTerminal.openDeveloperOptions()) },
            setup = { ui.dialog = LocalDialog.SETUP },
            resetHostKey = { ui.dialog = LocalDialog.RESET }
        )
    }
}

/** Setup and host key reset dialogs of the local machine; [onReset] runs after the key is forgotten. */
@Composable
fun LocalDialogs(container: AppContainer, ui: LocalUiState, publicKey: SshPublicKey?, onReset: () -> Unit) {
    when (ui.dialog) {
        LocalDialog.SETUP -> LocalSetupDialog(publicKey) { ui.dialog = null }

        LocalDialog.RESET -> LocalResetDialog(
            name = container.machines.get(LocalMachine.ID)?.name.orEmpty(),
            onConfirm = {
                ui.dialog = null
                onReset()
                container.machines.resetLocalHostKey()
                container.connections.connect(LocalMachine.ID)
            },
            onDismiss = { ui.dialog = null }
        )

        null -> Unit
    }
}

/** What the user can do about the Linux Terminal from the local machine row. */
class LocalActions(
    val openTerminal: () -> Unit,
    val terminalSettings: () -> Unit,
    val developerOptions: () -> Unit,
    val setup: () -> Unit,
    val resetHostKey: () -> Unit
)

/** Message and actions under the local machine, by status (docs/design/integrations.md, local states). */
@Composable
fun LocalMachineHelp(status: MachineStatus, actions: LocalActions) {
    val help = when (status) {
        MachineStatus.LocalTerminalMissing -> LocalHelp(R.string.local_help_missing, developerOptions = true)

        is MachineStatus.LocalTerminalNotRunning -> LocalHelp(R.string.local_help_not_running, open = true)

        is MachineStatus.LocalTerminalStarting -> LocalHelp(R.string.local_help_starting)

        is MachineStatus.LocalTerminalNotConfigured -> if (status.keyRefused) {
            LocalHelp(R.string.local_help_key_refused, open = true)
        } else {
            LocalHelp(R.string.local_help_not_configured, open = true, settings = true)
        }

        is MachineStatus.HostKeyChanged -> LocalHelp(null, reset = true)

        is MachineStatus.Connected, MachineStatus.Connecting -> LocalHelp(R.string.local_help_keep_open)

        else -> LocalHelp(null)
    }
    Column {
        help.text?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (help.open) {
                OutlinedButton(onClick = actions.openTerminal) {
                    Text(stringResource(R.string.local_open_terminal))
                }
            }
            if (help.developerOptions) {
                OutlinedButton(onClick = actions.developerOptions) {
                    Text(stringResource(R.string.local_developer_options))
                }
            }
            if (help.settings) {
                OutlinedButton(onClick = actions.terminalSettings) {
                    Text(stringResource(R.string.local_terminal_settings))
                }
            }
            if (help.reset) {
                OutlinedButton(onClick = actions.resetHostKey) {
                    Text(stringResource(R.string.local_reset_host_key))
                }
            }
            TextButton(onClick = actions.setup) { Text(stringResource(R.string.local_setup)) }
        }
    }
}

private class LocalHelp(
    val text: Int?,
    val open: Boolean = false,
    val settings: Boolean = false,
    val developerOptions: Boolean = false,
    val reset: Boolean = false
)

/** The command to paste in the Linux Terminal and the steps around it. */
@Composable
private fun LocalSetupDialog(publicKey: SshPublicKey?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val command = publicKey?.let { LocalMachine.setupCommand(it.authorizedKeysLine()) }
    val copied = stringResource(R.string.local_setup_copied)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.local_setup_title)) },
        text = {
            Column {
                if (command == null) {
                    Text(stringResource(R.string.local_setup_no_key), color = MaterialTheme.colorScheme.error)
                } else {
                    Text(stringResource(R.string.local_setup_steps))
                    Spacer(Modifier.height(12.dp))
                    SelectionContainer { Text(command, fontFamily = FontFamily.Monospace) }
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.local_help_keep_open), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (command != null) {
                TextButton(onClick = {
                    // The command holds only the public key: nothing secret reaches the clipboard.
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Linux Terminal setup", command))
                    Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.local_setup_copy)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } }
    )
}

@Composable
private fun LocalResetDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.local_reset_title, name)) },
        text = { Text(stringResource(R.string.local_reset_text)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.local_reset_host_key)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
