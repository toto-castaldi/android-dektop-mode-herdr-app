package io.github.totocastaldi.herdrdesktop.coreclient.connection

import io.github.totocastaldi.herdrdesktop.coreclient.hostkey.HostKeyDecision
import io.github.totocastaldi.herdrdesktop.coreclient.machine.Machine

/** Byte stream to `herdr-core connect` running on a machine. */
interface CoreStream {
    /** Next chunk from the core, or null at end of stream. */
    suspend fun read(): ByteArray?

    suspend fun write(bytes: ByteArray)

    /** Exit status of the remote command once the stream has ended, or null if unknown. */
    suspend fun exitStatus(): Int?

    suspend fun close()
}

/** Opens a [CoreStream] to a machine: SSH connection, host key check, authentication, remote command. */
fun interface CoreTransport {
    suspend fun open(machine: Machine): OpenResult
}

sealed interface OpenResult {
    class Opened(val stream: CoreStream) : OpenResult

    /** Network failure, timeout or closed connection: worth retrying. */
    data class Unreachable(val reason: String) : OpenResult

    /** The host key is unconfirmed or changed: the connection was refused before authenticating. */
    data class HostKeyRejected(val decision: HostKeyDecision) : OpenResult

    /** The server refused the credentials. */
    data object AuthFailed : OpenResult
}

/**
 * Remote command line, the same that herdr-core uses for `machine add`: `~/.local/bin` is often
 * missing from the `PATH` of non-interactive sessions. Exit status 127 means the core is not installed.
 */
const val CORE_CONNECT_COMMAND =
    "sh -c 'p=\$(command -v herdr-core) || p=\$HOME/.local/bin/herdr-core; " +
        "[ -x \"\$p\" ] || { echo \"herdr-core not found\" >&2; exit 127; }; exec \"\$p\" connect'"

const val CORE_NOT_FOUND_EXIT = 127
