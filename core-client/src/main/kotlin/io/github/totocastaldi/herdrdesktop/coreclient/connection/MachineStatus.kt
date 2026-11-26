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

    data class CoreIncompatible(val compatibility: Compatibility) : MachineStatus
}
