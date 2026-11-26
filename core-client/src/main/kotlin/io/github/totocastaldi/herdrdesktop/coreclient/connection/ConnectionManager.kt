package io.github.totocastaldi.herdrdesktop.coreclient.connection

import io.github.totocastaldi.herdrdesktop.coreclient.hostkey.HostKey
import io.github.totocastaldi.herdrdesktop.coreclient.hostkey.HostKeyDecision
import io.github.totocastaldi.herdrdesktop.coreclient.machine.Machine
import io.github.totocastaldi.herdrdesktop.coreclient.machine.MachineRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Observer of status transitions, for logging; never receives secrets. */
fun interface ConnectionLog {
    fun statusChanged(machine: Machine, status: MachineStatus)
}

/** Timings of [ConnectionManager]; overridable in tests. */
data class ConnectionPolicy(
    val initialRetryMillis: Long = 1_000L,
    val maxRetryMillis: Long = 30_000L,
    val pingIntervalMillis: Long = 30_000L,
    val pingTimeoutMillis: Long = 10_000L
)

/**
 * Keeps one connection per machine the user connected, and its [MachineStatus]. A dropped or
 * unreachable connection is retried with exponential backoff; authentication, host key and core
 * problems stop the retries until the user acts. Closing the app does not touch the core.
 */
class ConnectionManager(
    private val machines: MachineRepository,
    private val transport: CoreTransport,
    private val scope: CoroutineScope,
    private val policy: ConnectionPolicy = ConnectionPolicy(),
    private val log: ConnectionLog = ConnectionLog { _, _ -> }
) {
    private val state = MutableStateFlow<Map<String, MachineStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, MachineStatus>> = state.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()

    fun status(id: String): MachineStatus = state.value[id] ?: MachineStatus.Disconnected

    /** Starts (or restarts) connecting to the machine [id]. */
    fun connect(id: String) {
        stop(id)
        val job = scope.launch { run(id) }
        synchronized(jobs) { jobs[id] = job }
    }

    /** Closes the connection to [id]; terminals keep running on the core. */
    fun disconnect(id: String) {
        stop(id)
        set(id, MachineStatus.Disconnected)
    }

    /** Saves the presented host key as confirmed and connects again (first connection only). */
    fun confirmHostKey(id: String, key: HostKey) {
        val current = status(id)
        require(current is MachineStatus.HostKeyUnconfirmed && current.presented == key) {
            "Only an unconfirmed host key presented by the server can be confirmed"
        }
        machines.confirmHostKey(id, key)
        connect(id)
    }

    /** Forgets the machine's connection state, for example after removing it. */
    fun forget(id: String) {
        stop(id)
        state.update { it - id }
    }

    private fun stop(id: String) {
        // Cancelling the run closes its session (see serve).
        synchronized(jobs) { jobs.remove(id) }?.cancel()
    }

    private suspend fun run(id: String) {
        var retry = policy.initialRetryMillis
        var machine = machines.get(id)
        while (machine != null) {
            set(id, MachineStatus.Connecting)
            val outcome = attempt(machine)
            // A disconnect may have cancelled this run while the attempt was ending.
            currentCoroutineContext().ensureActive()
            if (outcome.connected) retry = policy.initialRetryMillis
            set(id, outcome.status ?: MachineStatus.Unreachable(outcome.reason, retry))
            if (outcome.status != null) return
            delay(retry)
            retry = (retry * 2).coerceAtMost(policy.maxRetryMillis)
            machine = machines.get(id)
        }
        set(id, MachineStatus.Disconnected)
    }

    /** End of an attempt: a final [status] that stops retrying, or a [reason] to retry. */
    private class Outcome(val status: MachineStatus? = null, val reason: String = "", val connected: Boolean = false)

    private suspend fun attempt(machine: Machine): Outcome = when (val opened = transport.open(machine)) {
        is OpenResult.Unreachable -> Outcome(reason = opened.reason)

        OpenResult.AuthFailed -> Outcome(MachineStatus.AuthFailed)

        is OpenResult.HostKeyRejected -> Outcome(opened.decision.toStatus())

        is OpenResult.Opened -> when (val handshake = CoreSession.handshake(opened.stream, scope)) {
            is HandshakeResult.Ready -> serve(machine, handshake)
            HandshakeResult.CoreMissing -> Outcome(MachineStatus.CoreMissing)
            is HandshakeResult.Incompatible -> Outcome(MachineStatus.CoreIncompatible(handshake.compatibility))
            is HandshakeResult.Failed -> Outcome(reason = handshake.reason)
        }
    }

    /** Keeps a ready session until it closes or stops answering pings. */
    private suspend fun serve(machine: Machine, ready: HandshakeResult.Ready): Outcome {
        val session = ready.session
        set(machine.id, MachineStatus.Connected(ready.hello.core?.name, ready.hello.version))
        try {
            var alive = true
            while (alive) {
                val closed = withTimeoutOrNull(policy.pingIntervalMillis) { session.closed.await() }
                alive = closed == null && session.ping(policy.pingTimeoutMillis)
            }
        } finally {
            withContext(NonCancellable) { session.close() }
        }
        return Outcome(reason = "connection lost", connected = true)
    }

    private fun set(id: String, status: MachineStatus) {
        state.update { it + (id to status) }
        machines.get(id)?.let { log.statusChanged(it, status) }
    }
}

private fun HostKeyDecision.toStatus(): MachineStatus = when (this) {
    is HostKeyDecision.Unconfirmed -> MachineStatus.HostKeyUnconfirmed(presented)
    is HostKeyDecision.Changed -> MachineStatus.HostKeyChanged(confirmed, presented)
    HostKeyDecision.Trusted -> error("A trusted host key is never rejected")
}
