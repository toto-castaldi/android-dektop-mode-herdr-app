package io.github.totocastaldi.herdrdesktop.machines

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import io.github.totocastaldi.herdrdesktop.R
import io.github.totocastaldi.herdrdesktop.coreclient.connection.MachineStatus
import io.github.totocastaldi.herdrdesktop.coreclient.install.CoreOperation
import io.github.totocastaldi.herdrdesktop.coreclient.protocol.Compatibility

private const val MILLIS_PER_SECOND = 1_000L

/** Test tag of the status line of machine [id]. */
fun machineStatusTag(id: String) = "machine-status-$id"

/** Test tag of the card of machine [id]. */
fun machineRowTag(id: String) = "machine-row-$id"

@Composable
fun statusText(status: MachineStatus): String = when (status) {
    MachineStatus.Disconnected -> stringResource(R.string.status_disconnected)

    MachineStatus.Connecting -> stringResource(R.string.status_connecting)

    is MachineStatus.Connected -> status.availableUpdate?.let {
        stringResource(R.string.status_connected_update, status.coreVersion, it)
    } ?: stringResource(R.string.status_connected, status.coreVersion)

    is MachineStatus.Unreachable ->
        stringResource(R.string.status_unreachable, status.reason, (status.retryInMillis / MILLIS_PER_SECOND).toInt())

    MachineStatus.AuthFailed -> stringResource(R.string.status_auth_failed)

    is MachineStatus.HostKeyUnconfirmed -> stringResource(R.string.status_host_key_unconfirmed)

    is MachineStatus.HostKeyChanged -> stringResource(R.string.status_host_key_changed)

    else -> coreStatusText(status) ?: localStatusText(status)
}

@Composable
private fun coreStatusText(status: MachineStatus): String? = when (status) {
    is MachineStatus.CoreMissing -> stringResource(R.string.status_core_missing)

    is MachineStatus.CoreIncompatible -> when (val c = status.compatibility) {
        is Compatibility.AppUpdateNeeded -> stringResource(R.string.status_core_app_update)

        is Compatibility.CoreUpdateNeeded -> stringResource(
            R.string.status_core_update,
            c.foundVersion ?: stringResource(R.string.core_version_unknown),
            c.requiredVersion
        )

        Compatibility.Compatible -> stringResource(R.string.status_connected, "")
    }

    is MachineStatus.CoreInstalling -> stringResource(
        if (status.operation ==
            CoreOperation.INSTALL
        ) {
            R.string.status_core_installing
        } else {
            R.string.status_core_updating
        }
    )

    is MachineStatus.CoreInstallFailed -> {
        val exit = status.exitStatus?.toString() ?: "?"
        stringResource(
            if (status.operation == CoreOperation.INSTALL) {
                R.string.status_core_install_failed
            } else {
                R.string.status_core_update_failed
            },
            exit
        )
    }

    else -> null
}

@Composable
private fun localStatusText(status: MachineStatus): String = when (status) {
    MachineStatus.LocalNetworkPermissionNeeded -> stringResource(R.string.status_local_network_needed)

    MachineStatus.LocalNetworkDenied -> stringResource(R.string.status_local_network_denied)

    MachineStatus.LocalTerminalMissing -> stringResource(R.string.status_local_missing)

    is MachineStatus.LocalTerminalNotRunning -> stringResource(
        R.string.status_local_not_running,
        status.retryInMillis.seconds()
    )

    is MachineStatus.LocalTerminalStarting -> stringResource(
        R.string.status_local_starting,
        status.retryInMillis.seconds()
    )

    is MachineStatus.LocalTerminalNotConfigured -> status.retryInMillis?.let {
        stringResource(R.string.status_local_not_configured_retry, it.seconds())
    } ?: stringResource(R.string.status_local_not_configured)

    else -> error("Not a local machine or local network status: $status")
}

private fun Long.seconds(): Int = (this / MILLIS_PER_SECOND).toInt()

@Composable
fun statusColor(status: MachineStatus): Color = when (status) {
    is MachineStatus.Connected -> MaterialTheme.colorScheme.primary

    MachineStatus.Disconnected, MachineStatus.Connecting, is MachineStatus.HostKeyUnconfirmed,
    MachineStatus.LocalNetworkPermissionNeeded, is MachineStatus.CoreInstalling,
    is MachineStatus.LocalTerminalStarting ->
        MaterialTheme.colorScheme.onSurfaceVariant

    else -> MaterialTheme.colorScheme.error
}
