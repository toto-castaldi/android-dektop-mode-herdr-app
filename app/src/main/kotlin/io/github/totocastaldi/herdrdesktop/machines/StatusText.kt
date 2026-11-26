package io.github.totocastaldi.herdrdesktop.machines

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import io.github.totocastaldi.herdrdesktop.R
import io.github.totocastaldi.herdrdesktop.coreclient.connection.MachineStatus
import io.github.totocastaldi.herdrdesktop.coreclient.protocol.Compatibility

private const val MILLIS_PER_SECOND = 1_000L

@Composable
fun statusText(status: MachineStatus): String = when (status) {
    MachineStatus.Disconnected -> stringResource(R.string.status_disconnected)

    MachineStatus.Connecting -> stringResource(R.string.status_connecting)

    is MachineStatus.Connected -> stringResource(R.string.status_connected, status.coreVersion)

    is MachineStatus.Unreachable ->
        stringResource(R.string.status_unreachable, status.reason, (status.retryInMillis / MILLIS_PER_SECOND).toInt())

    MachineStatus.AuthFailed -> stringResource(R.string.status_auth_failed)

    is MachineStatus.HostKeyUnconfirmed -> stringResource(R.string.status_host_key_unconfirmed)

    is MachineStatus.HostKeyChanged -> stringResource(R.string.status_host_key_changed)

    MachineStatus.CoreMissing -> stringResource(R.string.status_core_missing)

    is MachineStatus.CoreIncompatible -> when (val c = status.compatibility) {
        is Compatibility.AppUpdateNeeded -> stringResource(R.string.status_core_app_update)
        is Compatibility.CoreUpdateNeeded -> stringResource(R.string.status_core_update, c.reason)
        Compatibility.Compatible -> stringResource(R.string.status_connected, "")
    }
}

@Composable
fun statusColor(status: MachineStatus): Color = when (status) {
    is MachineStatus.Connected -> MaterialTheme.colorScheme.primary

    MachineStatus.Disconnected, MachineStatus.Connecting, is MachineStatus.HostKeyUnconfirmed ->
        MaterialTheme.colorScheme.onSurfaceVariant

    else -> MaterialTheme.colorScheme.error
}
