package io.github.totocastaldi.herdrdesktop.coreclient.connection

import io.github.totocastaldi.herdrdesktop.coreclient.install.CoreOperation
import io.github.totocastaldi.herdrdesktop.coreclient.protocol.Compatibility

/**
 * Installs herdr-core on [id] after the user confirmed: allowed when the core is missing and the
 * installer's prerequisites are met, or after a failed installation. On success the app connects again.
 */
fun ConnectionManager.installCore(id: String) {
    val current = status(id)
    require(
        (current is MachineStatus.CoreMissing && current.missingPrerequisites.isEmpty()) ||
            (current is MachineStatus.CoreInstallFailed && current.operation == CoreOperation.INSTALL)
    ) { "herdr-core can be installed only where it is missing" }
    startOperation(id, CoreOperation.INSTALL)
}

/**
 * Updates herdr-core on [id] after the user confirmed: allowed when the core is too old for the app,
 * when a newer version is available for a connected core, or after a failed update. On success the
 * app connects again.
 */
fun ConnectionManager.updateCore(id: String) {
    val current = status(id)
    require(
        (current is MachineStatus.CoreIncompatible && current.compatibility is Compatibility.CoreUpdateNeeded) ||
            (current is MachineStatus.Connected && current.availableUpdate != null) ||
            (current is MachineStatus.CoreInstallFailed && current.operation == CoreOperation.UPDATE)
    ) { "herdr-core can be updated only where it is too old or a newer version is available" }
    startOperation(id, CoreOperation.UPDATE)
}
