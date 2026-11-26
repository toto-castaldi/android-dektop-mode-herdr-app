package io.github.totocastaldi.herdrdesktop.coreclient.connection

import io.github.totocastaldi.herdrdesktop.coreclient.hostkey.HostKey
import io.github.totocastaldi.herdrdesktop.coreclient.hostkey.HostKeyDecision
import io.github.totocastaldi.herdrdesktop.coreclient.machine.LocalMachine
import io.github.totocastaldi.herdrdesktop.coreclient.machine.Machine
import io.github.totocastaldi.herdrdesktop.coreclient.machine.MachineRepository
import io.github.totocastaldi.herdrdesktop.coreclient.machine.isLocal
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
    val pingTimeoutMillis: Long = 10_000L,
    /** After the user opens the Linux Terminal, how long a refused local connection means "VM starting". */
    val localStartMillis: Long = 60_000L
)

/** Where the user is with the Linux Terminal, from the app's point of view. */
private enum class TerminalPhase { UNKNOWN, STARTING, OPENED }

/** Tells whether the Android Linux Terminal app is installed and enabled. */
fun interface LocalTerminalProbe {
    fun available(): Boolean
}

/** What the device allows: the Linux Terminal for the local machine, the local network for LAN hosts. */
data class DeviceAccess(
    val localTerminal: LocalTerminalProbe = LocalTerminalProbe { true },
    val localNetwork: LocalNetworkAccess = LocalNetworkAccess.Unrestricted
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
    private val log: ConnectionLog = ConnectionLog { _, _ -> },
    private val device: DeviceAccess = DeviceAccess()
) {
    private val state = MutableStateFlow<Map<String, MachineStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, MachineStatus>> = state.asStateFlow()

    private val sessionState = MutableStateFlow<Map<String, CoreRpc>>(emptyMap())

    /** Sessions of the connected machines: a new instance after every reconnection. */
    val sessions: StateFlow<Map<String, CoreRpc>> = sessionState.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()

    @Volatile
    private var terminalPhase = TerminalPhase.UNKNOWN
    private var terminalTimer: Job? = null

    /** Starts (or restarts) connecting to the machine [id]. */
    fun connect(id: String) {
        jobs.stop(id)
        val job = scope.launch { run(id) }
        synchronized(jobs) { jobs[id] = job }
    }

    /** Closes the connection to [id]; terminals keep running on the core. */
    fun disconnect(id: String) {
        jobs.stop(id)
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

    /**
     * The user opened the Linux Terminal from the app: a refused local connection now means the VM is
     * starting, and after [ConnectionPolicy.localStartMillis] that the VM is not configured. Retries at once.
     */
    fun localTerminalOpened() {
        terminalPhase = TerminalPhase.STARTING
        terminalTimer?.cancel()
        terminalTimer = scope.launch {
            delay(policy.localStartMillis)
            terminalPhase = TerminalPhase.OPENED
            // Atomic, so that a connection established meanwhile is not overwritten.
            state.update { all ->
                val starting = all[LocalMachine.ID] as? MachineStatus.LocalTerminalStarting
                if (starting == null) {
                    all
                } else {
                    all + (LocalMachine.ID to MachineStatus.LocalTerminalNotConfigured(false, starting.retryInMillis))
                }
            }
        }
        connect(LocalMachine.ID)
    }

    /**
     * Result of asking the user for the local network permission: if granted, the machines waiting for it
     * connect again; otherwise they show that local network access is not allowed.
     */
    fun localNetworkPermissionResult(granted: Boolean) {
        val waiting = state.value.filterValues {
            it == MachineStatus.LocalNetworkPermissionNeeded || it == MachineStatus.LocalNetworkDenied
        }.keys
        waiting.forEach { id ->
            if (granted) {
                connect(id)
            } else {
                jobs.stop(id)
                set(id, MachineStatus.LocalNetworkDenied)
            }
        }
    }

    /** Forgets the machine's connection state, for example after removing it. */
    fun forget(id: String) {
        jobs.stop(id)
        state.update { it - id }
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
            set(id, outcome.status ?: retryStatus(machine, outcome.reason, outcome.refused, terminalPhase, retry))
            if (outcome.status != null) return
            delay(retry)
            retry = (retry * 2).coerceAtMost(policy.maxRetryMillis)
            machine = machines.get(id)
        }
        set(id, MachineStatus.Disconnected)
    }

    /** End of an attempt: a final [status] that stops retrying, or a [reason] to retry. */
    private class Outcome(
        val status: MachineStatus? = null,
        val reason: String = "",
        val connected: Boolean = false,
        val refused: Boolean = false
    )

    private suspend fun attempt(machine: Machine): Outcome {
        device.blockingStatus(machine)?.let { return Outcome(it) }
        return when (val opened = transport.open(machine)) {
            is OpenResult.Unreachable -> Outcome(reason = opened.reason, refused = opened.refused)

            OpenResult.AuthFailed ->
                Outcome(
                    if (machine.isLocal) {
                        MachineStatus.LocalTerminalNotConfigured(
                            true,
                            null
                        )
                    } else {
                        MachineStatus.AuthFailed
                    }
                )

            is OpenResult.HostKeyRejected -> Outcome(opened.decision.toStatus())

            is OpenResult.Opened -> when (val handshake = CoreSession.handshake(opened.stream, scope)) {
                is HandshakeResult.Ready -> serve(machine, handshake)
                HandshakeResult.CoreMissing -> Outcome(MachineStatus.CoreMissing)
                is HandshakeResult.Incompatible -> Outcome(MachineStatus.CoreIncompatible(handshake.compatibility))
                is HandshakeResult.Failed -> Outcome(reason = handshake.reason)
            }
        }
    }

    /** Keeps a ready session until it closes or stops answering pings. */
    private suspend fun serve(machine: Machine, ready: HandshakeResult.Ready): Outcome {
        val session = ready.session
        if (machine.isLocal) terminalPhase = TerminalPhase.UNKNOWN
        set(machine.id, MachineStatus.Connected(ready.hello.core?.name, ready.hello.version))
        sessionState.update { it + (machine.id to session) }
        try {
            var alive = true
            while (alive) {
                val closed = withTimeoutOrNull(policy.pingIntervalMillis) { session.closed.await() }
                alive = closed == null && session.ping(policy.pingTimeoutMillis)
            }
        } finally {
            sessionState.update { if (it[machine.id] === session) it - machine.id else it }
            withContext(NonCancellable) { session.close() }
        }
        return Outcome(reason = "connection lost", connected = true)
    }

    private fun set(id: String, status: MachineStatus) {
        state.update { it + (id to status) }
        machines.get(id)?.let { log.statusChanged(it, status) }
    }
}

fun ConnectionManager.status(id: String): MachineStatus = statuses.value[id] ?: MachineStatus.Disconnected

/** Status that prevents even trying to connect to [machine], or null. */
private suspend fun DeviceAccess.blockingStatus(machine: Machine): MachineStatus? = when {
    machine.isLocal && !localTerminal.available() -> MachineStatus.LocalTerminalMissing

    // Without the permission Android drops the connection silently: ask instead of hanging.
    !localNetwork.granted() && localNetwork.needsPermission(machine.host) -> MachineStatus.LocalNetworkPermissionNeeded

    else -> null
}

/** Cancels the run of [id]; cancelling it closes its session (see serve). */
private fun MutableMap<String, Job>.stop(id: String) {
    synchronized(this) { remove(id) }?.cancel()
}

/** Status while waiting to retry; refused local connections are explained in terms of the Linux Terminal. */
private fun retryStatus(machine: Machine, reason: String, refused: Boolean, phase: TerminalPhase, retry: Long) = when {
    !machine.isLocal || !refused -> MachineStatus.Unreachable(reason, retry)
    phase == TerminalPhase.STARTING -> MachineStatus.LocalTerminalStarting(retry)
    phase == TerminalPhase.OPENED -> MachineStatus.LocalTerminalNotConfigured(false, retry)
    else -> MachineStatus.LocalTerminalNotRunning(retry)
}

private fun HostKeyDecision.toStatus(): MachineStatus = when (this) {
    is HostKeyDecision.Unconfirmed -> MachineStatus.HostKeyUnconfirmed(presented)
    is HostKeyDecision.Changed -> MachineStatus.HostKeyChanged(confirmed, presented)
    HostKeyDecision.Trusted -> error("A trusted host key is never rejected")
}
