package io.github.totocastaldi.herdrdesktop.machines

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.totocastaldi.herdrdesktop.AppContainer
import io.github.totocastaldi.herdrdesktop.R
import io.github.totocastaldi.herdrdesktop.coreclient.connection.ConnectionManager
import io.github.totocastaldi.herdrdesktop.coreclient.connection.MachineStatus
import io.github.totocastaldi.herdrdesktop.coreclient.connection.installCore
import io.github.totocastaldi.herdrdesktop.coreclient.connection.updateCore
import io.github.totocastaldi.herdrdesktop.coreclient.install.CoreInstaller
import io.github.totocastaldi.herdrdesktop.coreclient.install.CoreOperation
import io.github.totocastaldi.herdrdesktop.coreclient.install.MissingPrerequisite
import io.github.totocastaldi.herdrdesktop.coreclient.machine.Machine
import io.github.totocastaldi.herdrdesktop.coreclient.protocol.Compatibility

/** Test tag of the box with the output of the install or update command. */
const val CORE_OUTPUT_TAG = "core-command-output"

/**
 * Message and action under a machine whose core is missing, too old, has a newer version
 * available, or is being installed or updated (REQ-CONN-003, REQ-CONN-004). [propose] asks the user
 * to confirm the operation; nothing runs before that.
 */
@Composable
fun CoreInstallHelp(status: MachineStatus, propose: (CoreOperation) -> Unit) {
    when (status) {
        is MachineStatus.CoreMissing -> if (status.missingPrerequisites.isEmpty()) {
            HelpText(stringResource(R.string.core_help_missing))
            Button(onClick = { propose(CoreOperation.INSTALL) }) { Text(stringResource(R.string.core_install)) }
        } else {
            HelpText(stringResource(R.string.core_help_prerequisites, missingText(status.missingPrerequisites)))
        }

        is MachineStatus.CoreIncompatible -> (status.compatibility as? Compatibility.CoreUpdateNeeded)?.let { update ->
            missingDetail(update.missing)?.let { HelpText(it) }
            HelpText(stringResource(R.string.core_help_update))
            Button(onClick = { propose(CoreOperation.UPDATE) }) { Text(stringResource(R.string.core_update)) }
        }

        is MachineStatus.Connected -> status.availableUpdate?.let {
            HelpText(stringResource(R.string.core_help_update_available, status.coreVersion, it))
            Button(onClick = { propose(CoreOperation.UPDATE) }) { Text(stringResource(R.string.core_update)) }
        }

        is MachineStatus.CoreInstalling -> {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
            if (status.output.isNotEmpty()) CommandOutputBox(status.output)
        }

        is MachineStatus.CoreInstallFailed -> {
            CommandOutputBox(status.output.ifEmpty { stringResource(R.string.core_no_output) })
            Button(onClick = { propose(status.operation) }) { Text(stringResource(R.string.core_retry)) }
        }

        else -> Unit
    }
}

@Composable
private fun HelpText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall)
}

/** Output of the command, scrolled to its end as it grows. */
@Composable
private fun CommandOutputBox(output: String) {
    val scroll = rememberScrollState()
    LaunchedEffect(output) { scroll.scrollTo(scroll.maxValue) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        SelectionContainer {
            Text(
                output,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.heightIn(max = 200.dp).verticalScroll(scroll).padding(8.dp).testTag(CORE_OUTPUT_TAG)
            )
        }
    }
}

/** Asks to confirm installing or updating the core on [machine], showing the exact command. */
@Composable
fun CoreConfirmDialog(machine: Machine, operation: CoreOperation, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val install = operation == CoreOperation.INSTALL
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (install) R.string.core_confirm_install_title else R.string.core_confirm_update_title,
                    machine.name
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        if (install) R.string.core_confirm_install_text else R.string.core_confirm_update_text,
                        machine.user
                    )
                )
                SelectionContainer {
                    Text(CoreInstaller.command(operation), fontFamily = FontFamily.Monospace)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(if (install) R.string.core_install else R.string.core_update))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun missingText(missing: List<MissingPrerequisite>): String = missing.map {
    when (it) {
        is MissingPrerequisite.MissingCommand -> stringResource(R.string.core_prerequisite_command, it.command)

        is MissingPrerequisite.UnsupportedSystem -> stringResource(R.string.core_prerequisite_system, it.system)

        is MissingPrerequisite.UnsupportedArchitecture ->
            stringResource(R.string.core_prerequisite_architecture, it.architecture)
    }
}.joinToString("; ")

@Composable
private fun missingDetail(missing: Compatibility.Missing?): String? = when (missing) {
    null -> null
    is Compatibility.Missing.Generation -> stringResource(R.string.core_missing_generation, missing.generation)
    is Compatibility.Missing.Methods -> stringResource(R.string.core_missing_methods, missing.methods.joinToString())
    Compatibility.Missing.Framed -> stringResource(R.string.core_missing_framed)
    is Compatibility.Missing.HelloError -> stringResource(R.string.core_missing_hello, missing.kind ?: "?")
}

/** Confirmation of [target]; on confirm the operation starts, in any case [close] is called. */
@Composable
fun CoreConfirmPrompt(container: AppContainer, target: Pair<Machine, CoreOperation>, close: () -> Unit) {
    val (machine, operation) = target
    CoreConfirmDialog(machine, operation, onDismiss = close) {
        close()
        container.connections.startConfirmed(machine.id, operation)
    }
}

/** Starts the [operation] the user just confirmed on machine [id]. */
fun ConnectionManager.startConfirmed(id: String, operation: CoreOperation) = when (operation) {
    CoreOperation.INSTALL -> installCore(id)
    CoreOperation.UPDATE -> updateCore(id)
}
