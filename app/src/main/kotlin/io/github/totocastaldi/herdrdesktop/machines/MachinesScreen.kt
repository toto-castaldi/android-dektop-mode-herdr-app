package io.github.totocastaldi.herdrdesktop.machines

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.totocastaldi.herdrdesktop.AppContainer
import io.github.totocastaldi.herdrdesktop.R
import io.github.totocastaldi.herdrdesktop.coreclient.connection.MachineStatus
import io.github.totocastaldi.herdrdesktop.coreclient.connection.status
import io.github.totocastaldi.herdrdesktop.coreclient.install.CoreOperation
import io.github.totocastaldi.herdrdesktop.coreclient.keys.SshKeyStore
import io.github.totocastaldi.herdrdesktop.coreclient.keys.SshPublicKey
import io.github.totocastaldi.herdrdesktop.coreclient.machine.LocalMachine
import io.github.totocastaldi.herdrdesktop.coreclient.machine.Machine
import io.github.totocastaldi.herdrdesktop.coreclient.machine.MachineAuth
import io.github.totocastaldi.herdrdesktop.coreclient.machine.isLocal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Machine list with status, key management and host key confirmation (REQ-CONN-001, REQ-NFR-001). */
@Composable
fun MachinesScreen(container: AppContainer, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val machines by container.machines.machines.collectAsState()
    val statuses by container.connections.statuses.collectAsState()
    var publicKey by remember { mutableStateOf(container.keys.publicKey(SshKeyStore.DEFAULT_ALIAS)) }
    var editing by remember { mutableStateOf<EditTarget?>(null) }
    var removing by remember { mutableStateOf<Machine?>(null) }
    var confirmingCore by remember { mutableStateOf<Pair<Machine, CoreOperation>?>(null) }
    var dismissedChanged by remember { mutableStateOf(emptySet<String>()) }
    val localUi = remember { LocalUiState() }
    val scope = rememberCoroutineScope()

    Surface(modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), Arrangement.spacedBy(16.dp)) {
            Header { editing = EditTarget(null) }
            KeyCard(publicKey) {
                scope.launch {
                    publicKey = withContext(Dispatchers.Default) { container.keys.generate(SshKeyStore.DEFAULT_ALIAS) }
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(machines, key = { it.id }) { machine ->
                    MachineRow(
                        machine = machine,
                        status = statuses[machine.id] ?: MachineStatus.Disconnected,
                        local = if (machine.isLocal) rememberLocalActions(container, localUi) else null,
                        openAppSettings = { container.localNetwork.openAppSettings() },
                        actions = MachineActions(
                            connect = {
                                dismissedChanged = dismissedChanged - machine.id
                                container.connections.connect(machine.id)
                            },
                            disconnect = { container.connections.disconnect(machine.id) },
                            open = { onOpen(machine.id) },
                            edit = { editing = EditTarget(machine) },
                            remove = { removing = machine },
                            proposeCore = { confirmingCore = machine to it }
                        )
                    )
                }
            }
        }
    }

    editing?.let { target ->
        MachineDialog(
            machine = target.machine,
            keyAvailable = publicKey != null,
            onSave = { input ->
                save(container, target.machine, input)
                editing = null
            },
            onDismiss = { editing = null }
        )
    }

    removing?.let { RemoveDialog(container, it) { removing = null } }

    confirmingCore?.let { CoreConfirmPrompt(container, it) { confirmingCore = null } }

    LocalDialogs(container, localUi, publicKey) { dismissedChanged = dismissedChanged - LocalMachine.ID }

    HostKeyPrompts(machines, statuses, dismissedChanged, container) { dismissedChanged = dismissedChanged + it }
}

private class EditTarget(val machine: Machine?)

@Composable
private fun Header(onAdd: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.machines_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.weight(1f))
        Button(onClick = onAdd) { Text(stringResource(R.string.machine_add)) }
    }
}

private class MachineActions(
    val connect: () -> Unit,
    val disconnect: () -> Unit,
    val open: () -> Unit,
    val edit: () -> Unit,
    val remove: () -> Unit,
    val proposeCore: (CoreOperation) -> Unit
)

@Composable
private fun RemoveDialog(container: AppContainer, machine: Machine, onDismiss: () -> Unit) {
    val onConfirm = {
        container.connections.forget(machine.id)
        container.machines.remove(machine.id)
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.machine_remove_title, machine.name)) },
        text = { Text(stringResource(R.string.machine_remove_text)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.machine_remove)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun HostKeyPrompts(
    machines: List<Machine>,
    statuses: Map<String, MachineStatus>,
    dismissedChanged: Set<String>,
    container: AppContainer,
    onDismissChanged: (String) -> Unit
) {
    val pending = machines.firstNotNullOfOrNull { m ->
        (statuses[m.id] as? MachineStatus.HostKeyUnconfirmed)?.let {
            m to
                it
        }
    }
    if (pending != null) {
        val (machine, status) = pending
        HostKeyConfirmDialog(
            machineName = machine.name,
            key = status.presented,
            local = machine.isLocal,
            onConfirm = { container.connections.confirmHostKey(machine.id, status.presented) },
            onDismiss = { container.connections.disconnect(machine.id) }
        )
        return
    }
    val changed = machines.firstNotNullOfOrNull { m ->
        (statuses[m.id] as? MachineStatus.HostKeyChanged)?.takeIf { m.id !in dismissedChanged }?.let { m to it }
    }
    if (changed != null) {
        val (machine, status) = changed
        HostKeyChangedDialog(machine.name, status.confirmed, status.presented, machine.isLocal) {
            onDismissChanged(machine.id)
        }
    }
}

private fun save(container: AppContainer, existing: Machine?, input: MachineInput) {
    val auth = when {
        input.useKey -> MachineAuth.Key(SshKeyStore.DEFAULT_ALIAS)

        input.password != null -> {
            val bytes = String(input.password).toByteArray()
            input.password.fill('\u0000')
            MachineAuth.Password(container.secrets.encrypt(bytes)).also { bytes.fill(0) }
        }

        else -> checkNotNull(existing?.auth as? MachineAuth.Password) { "Password required" }
    }
    if (existing == null) {
        container.machines.add(input.name, input.host, input.port, input.user, auth)
    } else {
        container.machines.update(
            existing.copy(name = input.name, host = input.host, port = input.port, user = input.user, auth = auth)
        )
        if (container.connections.status(existing.id) != MachineStatus.Disconnected) {
            container.connections.connect(existing.id)
        }
    }
}

@Composable
private fun KeyCard(publicKey: SshPublicKey?, onGenerate: () -> Unit) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.key_title), style = MaterialTheme.typography.titleMedium)
            if (publicKey == null) {
                Text(stringResource(R.string.key_none))
                Button(onClick = onGenerate) { Text(stringResource(R.string.key_generate)) }
            } else {
                Text(stringResource(R.string.key_info, publicKey.algorithm.sshName, publicKey.fingerprint))
                SelectionContainer {
                    Text(
                        publicKey.authorizedKeysLine(),
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val copied = stringResource(R.string.key_copied)
                Button(onClick = {
                    copyPublicKey(context, publicKey)
                    Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.key_copy)) }
            }
        }
    }
}

private fun copyPublicKey(context: Context, key: SshPublicKey) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("SSH public key", key.authorizedKeysLine()))
}

@Composable
private fun MachineRow(
    machine: Machine,
    status: MachineStatus,
    local: LocalActions?,
    openAppSettings: () -> Boolean,
    actions: MachineActions
) {
    val endpoint = "${machine.user}@${machine.host}:${machine.port}"
    Card(Modifier.fillMaxWidth().testTag(machineRowTag(machine.id))) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(machine.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (local != null) stringResource(R.string.local_machine_endpoint, endpoint) else endpoint,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    statusText(status),
                    color = statusColor(status),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(machineStatusTag(machine.id))
                )
                if (local != null) LocalMachineHelp(status, local)
                CoreInstallHelp(status, actions.proposeCore)
                if (status == MachineStatus.LocalNetworkDenied) LocalNetworkDeniedHelp(openAppSettings)
            }
            if (status is MachineStatus.Connected) {
                Button(onClick = actions.open, modifier = Modifier.testTag("open-${machine.id}")) {
                    Text(stringResource(R.string.machine_open))
                }
                Spacer(Modifier.width(8.dp))
            }
            if (status is MachineStatus.CoreInstalling) {
                // The install or update command runs to its end: no connect or disconnect meanwhile.
            } else if (status.isRetrying()) {
                OutlinedButton(onClick = actions.disconnect) { Text(stringResource(R.string.machine_disconnect)) }
            } else {
                Button(onClick = actions.connect) { Text(stringResource(R.string.machine_connect)) }
            }
            // The local machine has a fixed endpoint and is always listed.
            if (local == null) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = actions.edit) { Text(stringResource(R.string.machine_edit)) }
                TextButton(onClick = actions.remove) { Text(stringResource(R.string.machine_remove)) }
            }
        }
    }
}

/** True while the connection is up, being set up or waiting to retry; false when it waits for the user. */
private fun MachineStatus.isRetrying(): Boolean = when (this) {
    MachineStatus.Disconnected, is MachineStatus.HostKeyChanged, MachineStatus.AuthFailed,
    is MachineStatus.CoreMissing, is MachineStatus.CoreIncompatible, is MachineStatus.CoreInstallFailed,
    MachineStatus.LocalTerminalMissing,
    MachineStatus.LocalNetworkPermissionNeeded, MachineStatus.LocalNetworkDenied -> false

    is MachineStatus.LocalTerminalNotConfigured -> retryInMillis != null

    else -> true
}

/** Why the machine is not connected and how to grant the local network permission. */
@Composable
private fun LocalNetworkDeniedHelp(openAppSettings: () -> Boolean) {
    val context = LocalContext.current
    val openFailed = stringResource(R.string.local_open_failed)
    Text(stringResource(R.string.local_network_help_denied), style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = {
        if (!openAppSettings()) Toast.makeText(context, openFailed, Toast.LENGTH_SHORT).show()
    }) { Text(stringResource(R.string.local_network_open_settings)) }
}
