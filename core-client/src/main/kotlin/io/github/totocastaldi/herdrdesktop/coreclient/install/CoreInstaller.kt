package io.github.totocastaldi.herdrdesktop.coreclient.install

import io.github.totocastaldi.herdrdesktop.coreclient.machine.Machine

/** Installation or update of herdr-core (docs/design/integrations.md, Installazione e aggiornamento). */
enum class CoreOperation { INSTALL, UPDATE }

/** A prerequisite of the herdr-core installer that the machine does not meet. */
sealed interface MissingPrerequisite {
    /** `uname -s` is neither Linux nor Darwin. */
    data class UnsupportedSystem(val system: String) : MissingPrerequisite

    /** `uname -m` has no herdr-core build. */
    data class UnsupportedArchitecture(val architecture: String) : MissingPrerequisite

    /** A command the installer needs (`curl`, `tar`) is not installed. */
    data class MissingCommand(val command: String) : MissingPrerequisite
}

/**
 * Commands that check, install and update herdr-core on a machine, run through [runner]. The install
 * and update commands are run only by [run], which the app calls only after the user confirmed.
 */
class CoreInstaller(private val runner: CommandRunner) {
    /**
     * What the machine lacks to run the installer; empty if nothing is missing or if the check itself
     * could not run (the installer then reports the problem).
     */
    suspend fun missingPrerequisites(machine: Machine): List<MissingPrerequisite> =
        when (val result = runner.run(machine, PREREQUISITES_COMMAND) { }) {
            is CommandResult.Exited -> if (result.succeeded) parsePrerequisites(result.output) else emptyList()
            is CommandResult.NotRun -> emptyList()
        }

    /** Runs the install or update command; [onOutput] receives the output so far. */
    suspend fun run(machine: Machine, operation: CoreOperation, onOutput: (String) -> Unit): CommandResult =
        runner.run(machine, command(operation), onOutput)

    companion object {
        const val INSTALLER_URL = "https://releases.herdr.dev/core/install.sh"

        /** `uname -s`, `uname -m`, then `curl=yes|no` and `tar=yes|no`, one per line. */
        const val PREREQUISITES_COMMAND =
            "sh -c 'uname -s; uname -m; for c in curl tar; do " +
                "if command -v \"\$c\" >/dev/null 2>&1; then echo \"\$c=yes\"; else echo \"\$c=no\"; fi; done'"

        /**
         * The official installer, downloaded first so that a failed download fails the command instead
         * of feeding an empty script to `sh`. Installs into `~/.local/bin` without root; does not start the core.
         */
        const val INSTALL_COMMAND =
            "sh -c 't=\$(mktemp) || exit 1; curl -fsSL $INSTALLER_URL -o \"\$t\" && sh \"\$t\"; " +
                "s=\$?; rm -f \"\$t\"; exit \$s'"

        /** `herdr-core update`, found like the connect command; running terminals survive. */
        const val UPDATE_COMMAND =
            "sh -c 'p=\$(command -v herdr-core) || p=\$HOME/.local/bin/herdr-core; exec \"\$p\" update'"

        private val SYSTEMS = setOf("Linux", "Darwin")
        private val ARCHITECTURES = setOf("x86_64", "amd64", "arm64", "aarch64")
        private val COMMANDS = listOf("curl", "tar")

        fun command(operation: CoreOperation): String = when (operation) {
            CoreOperation.INSTALL -> INSTALL_COMMAND
            CoreOperation.UPDATE -> UPDATE_COMMAND
        }

        /** Parses the output of [PREREQUISITES_COMMAND]; unexpected output means nothing is known to be missing. */
        fun parsePrerequisites(output: String): List<MissingPrerequisite> {
            val lines = output.lines().map(String::trim).filter(String::isNotEmpty)
            if (lines.size < 2) return emptyList()
            val (system, architecture) = lines
            val found = lines.drop(2).mapNotNull { line ->
                line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to (it[1] == "yes") }
            }.toMap()
            return buildList {
                if (system !in SYSTEMS) add(MissingPrerequisite.UnsupportedSystem(system))
                if (architecture !in ARCHITECTURES) add(MissingPrerequisite.UnsupportedArchitecture(architecture))
                COMMANDS.filter { found[it] == false }.forEach { add(MissingPrerequisite.MissingCommand(it)) }
            }
        }
    }
}
