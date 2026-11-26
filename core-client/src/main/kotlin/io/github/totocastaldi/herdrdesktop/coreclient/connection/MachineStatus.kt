package io.github.totocastaldi.herdrdesktop.coreclient.connection

import io.github.totocastaldi.herdrdesktop.coreclient.hostkey.HostKey
import io.github.totocastaldi.herdrdesktop.coreclient.protocol.Compatibility

/** Connection state of a machine, as shown next to it in the app. */
sealed interface MachineStatus {
    data object Disconnected : MachineStatus

    data object Connecting : MachineStatus

    data class Connected(val coreName: String?, val coreVersion: String) : MachineStatus

    /** The machine cannot be reached; the app retries in [retryInMillis]. */
    data class Unreachable(val reason: String, val retryInMillis: Long) : MachineStatus

    data object AuthFailed : MachineStatus

    /** First connection: the user must confirm the fingerprint of [presented]. */
    data class HostKeyUnconfirmed(val presented: HostKey) : MachineStatus

    /** The host key differs from the confirmed one: the connection is blocked. */
    data class HostKeyChanged(val confirmed: HostKey, val presented: HostKey) : MachineStatus

    data object CoreMissing : MachineStatus

    /** The host is on the local network and the app must ask the user for the local network permission. */
    data object LocalNetworkPermissionNeeded : MachineStatus

    /** The user denied the local network permission: no connection is attempted until it is granted. */
    data object LocalNetworkDenied : MachineStatus

    data class CoreIncompatible(val compatibility: Compatibility) : MachineStatus

    /** Local machine: the Linux Terminal app is not installed or not enabled. */
    data object LocalTerminalMissing : MachineStatus

    /** Local machine: nothing listens on its port, the Linux Terminal is not running; retried in [retryInMillis]. */
    data class LocalTerminalNotRunning(val retryInMillis: Long) : MachineStatus

    /** Local machine: the Linux Terminal was just opened and its VM is starting; retried in [retryInMillis]. */
    data class LocalTerminalStarting(val retryInMillis: Long) : MachineStatus

    /**
     * Local machine: the Terminal is open but sshd does not answer or the port is not accepted, or the
     * app key is refused ([keyRefused], no retry); otherwise retried in [retryInMillis].
     */
    data class LocalTerminalNotConfigured(val keyRefused: Boolean, val retryInMillis: Long?) : MachineStatus
}
