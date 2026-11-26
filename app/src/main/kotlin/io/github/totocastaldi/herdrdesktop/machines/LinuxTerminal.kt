package io.github.totocastaldi.herdrdesktop.machines

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import io.github.totocastaldi.herdrdesktop.coreclient.connection.LocalTerminalProbe
import io.github.totocastaldi.herdrdesktop.coreclient.machine.LocalMachine

/**
 * The Android Linux Terminal app, seen from outside: the app can only tell whether it is installed
 * and enabled and open it; it cannot start its VM without the user (docs/design/integrations.md).
 */
class LinuxTerminal(private val context: Context) : LocalTerminalProbe {
    /** Installed and enabled: the launcher activity exists only once the user turned the Terminal on. */
    override fun available(): Boolean = launchIntent() != null

    /** Opens the Terminal in its own window; false if it cannot be opened. */
    fun open(): Boolean = launchIntent()?.let(::start) == true

    /** App details of the Terminal, where the user can force stop it. */
    fun openSettings(): Boolean = start(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", LocalMachine.TERMINAL_PACKAGE, null)
        )
    )

    /** Developer options, where the Linux development environment is turned on. */
    fun openDeveloperOptions(): Boolean = start(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))

    private fun launchIntent(): Intent? =
        context.packageManager.getLaunchIntentForPackage(LocalMachine.TERMINAL_PACKAGE)

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
