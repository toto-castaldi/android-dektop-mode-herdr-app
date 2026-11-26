package io.github.totocastaldi.herdrdesktop.coreclient.install

import io.github.totocastaldi.herdrdesktop.coreclient.machine.Machine

/** Runs one command on a machine over SSH, with the same host key check and authentication as the core stream. */
fun interface CommandRunner {
    /**
     * Runs [command] and returns its exit status and output (stdout and stderr together). [onOutput]
     * receives the output collected so far each time more arrives.
     */
    suspend fun run(machine: Machine, command: String, onOutput: (String) -> Unit): CommandResult
}

sealed interface CommandResult {
    /** The command ran; [status] is null if the server did not report an exit status. */
    data class Exited(val status: Int?, val output: String) : CommandResult {
        val succeeded: Boolean get() = status == 0
    }

    /** The command could not be started: unreachable machine, host key or authentication refused. */
    data class NotRun(val reason: String) : CommandResult
}
