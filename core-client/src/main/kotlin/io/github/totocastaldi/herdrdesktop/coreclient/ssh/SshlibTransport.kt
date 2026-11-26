package io.github.totocastaldi.herdrdesktop.coreclient.ssh

import io.github.totocastaldi.herdrdesktop.coreclient.connection.CORE_CONNECT_COMMAND
import io.github.totocastaldi.herdrdesktop.coreclient.connection.CoreStream
import io.github.totocastaldi.herdrdesktop.coreclient.connection.CoreTransport
import io.github.totocastaldi.herdrdesktop.coreclient.connection.OpenResult
import io.github.totocastaldi.herdrdesktop.coreclient.hostkey.HostKey
import io.github.totocastaldi.herdrdesktop.coreclient.hostkey.HostKeyDecision
import io.github.totocastaldi.herdrdesktop.coreclient.hostkey.HostKeyPolicy
import io.github.totocastaldi.herdrdesktop.coreclient.install.CommandOutput
import io.github.totocastaldi.herdrdesktop.coreclient.install.CommandResult
import io.github.totocastaldi.herdrdesktop.coreclient.install.CommandRunner
import io.github.totocastaldi.herdrdesktop.coreclient.keys.SecretCipher
import io.github.totocastaldi.herdrdesktop.coreclient.keys.SshKeyStore
import io.github.totocastaldi.herdrdesktop.coreclient.machine.Machine
import io.github.totocastaldi.herdrdesktop.coreclient.machine.MachineAuth
import java.io.IOException
import java.net.ConnectException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.connectbot.sshlib.AuthHandler
import org.connectbot.sshlib.AuthPublicKey
import org.connectbot.sshlib.AuthResult
import org.connectbot.sshlib.ConnectResult
import org.connectbot.sshlib.HostKeyVerifier
import org.connectbot.sshlib.KeyboardInteractiveCallback
import org.connectbot.sshlib.PublicKey
import org.connectbot.sshlib.SessionExit
import org.connectbot.sshlib.SshClient
import org.connectbot.sshlib.SshClientConfig
import org.connectbot.sshlib.SshSession

/**
 * [CoreTransport] over ConnectBot sshlib: one SSH connection per machine, host key pinned to the
 * confirmed one, key signatures made by [keys] (the private key never reaches the library),
 * then an `exec` channel running [CORE_CONNECT_COMMAND]. As a [CommandRunner] it runs one-shot
 * commands (installing or updating herdr-core) on a connection set up the same way.
 */
class SshlibTransport(
    private val keys: SshKeyStore,
    private val secrets: SecretCipher,
    private val connectTimeoutMillis: Long = CONNECT_TIMEOUT_MILLIS,
    private val commandTimeoutMillis: Long = COMMAND_TIMEOUT_MILLIS
) : CoreTransport,
    CommandRunner {
    private fun client(machine: Machine, verifier: PinningVerifier) = SshClient(
        SshClientConfig {
            host = machine.host
            port = machine.port
            hostKeyVerifier = verifier
            keepAliveIntervalMs = KEEPALIVE_MILLIS
        }
    )

    override suspend fun open(machine: Machine): OpenResult {
        val verifier = PinningVerifier(machine.hostKey)
        val client = client(machine, verifier)
        val result = try {
            withTimeout(connectTimeoutMillis) { connect(client, machine, verifier) ?: openCore(client) }
        } catch (e: TimeoutCancellationException) {
            OpenResult.Unreachable(e.message ?: "timeout")
        } catch (e: CancellationException) {
            client.disconnect()
            throw e
        }
        if (result !is OpenResult.Opened) client.disconnect()
        return result
    }

    override suspend fun run(machine: Machine, command: String, onOutput: (String) -> Unit): CommandResult {
        val verifier = PinningVerifier(machine.hostKey)
        val client = client(machine, verifier)
        return try {
            val failure = try {
                withTimeout(connectTimeoutMillis) { connect(client, machine, verifier) }
            } catch (e: TimeoutCancellationException) {
                OpenResult.Unreachable(e.message ?: "timeout")
            }
            if (failure != null) CommandResult.NotRun(failure.describe()) else exec(client, command, onOutput)
        } finally {
            client.disconnect()
        }
    }

    private suspend fun exec(client: SshClient, command: String, onOutput: (String) -> Unit): CommandResult {
        val session = client.openSession() ?: return CommandResult.NotRun("cannot open session")
        val result = if (session.requestExec(command)) {
            val output = CommandOutput()
            val status = withTimeoutOrNull(commandTimeoutMillis) { collect(session, output, onOutput) }
            CommandResult.Exited(status, output.toString())
        } else {
            CommandResult.NotRun("exec refused")
        }
        session.close()
        return result
    }

    /** Reads stdout and stderr until both end, then returns the exit status. */
    private suspend fun collect(session: SshSession, output: CommandOutput, onOutput: (String) -> Unit): Int? {
        suspend fun drain(stream: Int, channel: ReceiveChannel<ByteArray>) {
            for (chunk in channel) onOutput(output.add(stream, chunk))
        }
        coroutineScope {
            launch { drain(STDERR, session.stderr) }
            drain(STDOUT, session.stdout)
        }
        return withTimeoutOrNull(EXIT_STATUS_WAIT_MILLIS) {
            (session.exitInfo.await() as? SessionExit.Status)?.code?.toInt()
        }
    }

    /** Connects and authenticates; returns null on success, otherwise why it failed. */
    private suspend fun connect(client: SshClient, machine: Machine, verifier: PinningVerifier): OpenResult? {
        val failure = when (val connected = client.connect()) {
            ConnectResult.Success -> null

            is ConnectResult.HostKeyRejected -> OpenResult.HostKeyRejected(checkNotNull(verifier.rejected))

            is ConnectResult.TransportError -> OpenResult.Unreachable(
                connected.cause.javaClass.simpleName,
                refused = isConnectionRefused(connected.cause)
            )

            is ConnectResult.AlgorithmMismatch -> OpenResult.Unreachable("algorithm mismatch")

            is ConnectResult.ProtocolError -> OpenResult.Unreachable("protocol error")
        }
        return failure ?: authenticate(client, machine)
    }

    private suspend fun authenticate(client: SshClient, machine: Machine): OpenResult? {
        val auth = when (val method = machine.auth) {
            is MachineAuth.Key -> client.authenticate(machine.user, KeystoreAuthHandler(keys, method.alias))

            is MachineAuth.Password -> client.authenticatePassword(
                machine.user,
                secrets.decrypt(method.sealed).decodeToString()
            )
        }
        return when (auth) {
            AuthResult.Success -> null
            is AuthResult.Failure -> OpenResult.AuthFailed
            is AuthResult.Error -> OpenResult.Unreachable("authentication error")
        }
    }

    private suspend fun openCore(client: SshClient): OpenResult {
        val session = client.openSession()
        return when {
            session == null -> OpenResult.Unreachable("cannot open session")

            !session.requestExec(CORE_CONNECT_COMMAND) -> {
                session.close()
                OpenResult.Unreachable("exec refused")
            }

            else -> OpenResult.Opened(SshCoreStream(client, session))
        }
    }

    private class PinningVerifier(private val confirmed: HostKey?) : HostKeyVerifier {
        var rejected: HostKeyDecision? = null

        override suspend fun verify(key: PublicKey): Boolean {
            val decision = HostKeyPolicy.evaluate(confirmed, HostKey(key.type, key.encoded))
            if (decision != HostKeyDecision.Trusted) rejected = decision
            return decision == HostKeyDecision.Trusted
        }
    }

    private class KeystoreAuthHandler(private val keys: SshKeyStore, private val alias: String) : AuthHandler {
        override suspend fun onPublicKeysNeeded(): List<AuthPublicKey> {
            val key = keys.publicKey(alias) ?: return emptyList()
            return listOf(AuthPublicKey(key.algorithm.sshName, key.blob))
        }

        override suspend fun onSignatureRequest(key: AuthPublicKey, dataToSign: ByteArray): ByteArray? =
            keys.sign(alias, dataToSign)

        override suspend fun onKeyboardInteractivePrompt(
            name: String,
            instruction: String,
            prompts: List<KeyboardInteractiveCallback.Prompt>
        ): List<String>? = null

        override suspend fun onPasswordNeeded(): String? = null
    }

    private class SshCoreStream(private val client: SshClient, private val session: SshSession) : CoreStream {
        override suspend fun read(): ByteArray? = io { session.read() }

        override suspend fun write(bytes: ByteArray) = io { session.write(bytes) }

        override suspend fun exitStatus(): Int? = withTimeoutOrNull(EXIT_STATUS_WAIT_MILLIS) {
            (session.exitInfo.await() as? SessionExit.Status)?.code?.toInt()
        }

        override suspend fun close() {
            runCatching { session.close() }
            client.disconnect()
        }

        @Suppress("TooGenericExceptionCaught")
        private suspend fun <T> io(block: suspend () -> T): T = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IOException(e.javaClass.simpleName, e)
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 20_000L
        const val KEEPALIVE_MILLIS = 15_000L
        const val EXIT_STATUS_WAIT_MILLIS = 5_000L
        const val COMMAND_TIMEOUT_MILLIS = 10 * 60_000L
        const val STDOUT = 1
        const val STDERR = 2
    }
}

/**
 * True if [error] says that nothing listens on the port. sshlib 0.6.x reports it as a
 * `TransportException` without cause, whose message lists the socket errors ("Connection refused").
 */
internal fun isConnectionRefused(error: Throwable): Boolean = generateSequence(error) { it.cause }.any {
    it is ConnectException || it.message?.let { m -> REFUSED.any(m::contains) } == true
}

private val REFUSED = listOf("Connection refused", "ECONNREFUSED")

/** Why a connection for a command failed, in the words shown with the failed command. */
private fun OpenResult.describe(): String = when (this) {
    is OpenResult.Unreachable -> "unreachable: $reason"
    is OpenResult.HostKeyRejected -> "host key not confirmed"
    OpenResult.AuthFailed -> "authentication failed"
    is OpenResult.Opened -> "opened"
}
