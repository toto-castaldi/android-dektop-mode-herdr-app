package io.github.totocastaldi.herdrdesktop.coreclient.connection

import io.github.totocastaldi.herdrdesktop.coreclient.hostkey.HostKey
import io.github.totocastaldi.herdrdesktop.coreclient.install.CoreOperation
import io.github.totocastaldi.herdrdesktop.coreclient.install.MissingPrerequisite
import io.github.totocastaldi.herdrdesktop.coreclient.protocol.Compatibility

/** Connection state of a machine, as shown next to it in the app. */
sealed interface MachineStatus {
    data object Disconnected : MachineStatus

    data object Connecting : MachineStatus

    /**
     * Connected to a compatible core; [availableUpdate] is a newer published version, if the machine
     * reported one (REQ-CONN-004): the installed core stays in use until the user updates it.
     */
    data class Connected(val coreName: String?, val coreVersion: String, val availableUpdate: String? = null) :
        MachineStatus

    /** The machine cannot be reached; the app retries in [retryInMillis]. */
    data class Unreachable(val reason: String, val retryInMillis: Long) : MachineStatus

    data object AuthFailed : MachineStatus

    /** First connection: the user must confirm the fingerprint of [presented]. */
    data class HostKeyUnconfirmed(val presented: HostKey) : MachineStatus

    /** The host key differs from the confirmed one: the connection is blocked. */
    data class HostKeyChanged(val confirmed: HostKey, val presented: HostKey) : MachineStatus

    /**
     * herdr-core is not installed. The app proposes the installer only if [missingPrerequisites] is empty;
     * otherwise it says what is missing.
     */
    data class CoreMissing(val missingPrerequisites: List<MissingPrerequisite> = emptyList()) : MachineStatus

    /** The user confirmed [operation] and its command is running; [output] is its output so far. */
    data class CoreInstalling(val operation: CoreOperation, val output: String) : MachineStatus

    /** The command of [operation] failed or could not run; [output] is its output, or the reason it did not run. */
    data class CoreInstallFailed(val operation: CoreOperation, val exitStatus: Int?, val output: String) :
        MachineStatus

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
