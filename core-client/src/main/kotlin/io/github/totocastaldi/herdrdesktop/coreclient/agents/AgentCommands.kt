package io.github.totocastaldi.herdrdesktop.coreclient.agents

import io.github.totocastaldi.herdrdesktop.coreclient.agents.AgentCommandCodec.CONFLICT
import io.github.totocastaldi.herdrdesktop.coreclient.agents.AgentCommandCodec.FAILED
import io.github.totocastaldi.herdrdesktop.coreclient.agents.AgentCommandCodec.NOT_FOUND
import io.github.totocastaldi.herdrdesktop.coreclient.connection.CoreRpc
import io.github.totocastaldi.herdrdesktop.coreclient.protocol.RpcException
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.NotConnectedException
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.PaneLocation
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.WorkspaceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** The app's windows as the agents' commands see them; implemented by the app (`app` module). */
interface AgentWindows {
    /** Machines open in at least one window of this device: only these take selection and focus commands. */
    val machines: StateFlow<Set<String>>

    /** The space the window that would take a command on [machineId] shows, null when unknown. */
    fun selectedSpace(machineId: String): String?

    /** Brings [spaceId] (and [tabId], when given) to the front in a window of [machineId]; false without one. */
    fun select(machineId: String, spaceId: String, tabId: String?): Boolean

    /** Shows the agent [address] of [machineId] as the sidebar does: its pane at [location], or in peek. */
    fun focus(machineId: String, address: String, location: PaneLocation?): Boolean
}

/** What the log may say about a command: the machine, the action name and the outcome, never the arguments. */
fun interface AgentCommandLog {
    fun log(machineId: String, action: String, outcome: String)
}

/**
 * Carries out the agents' commands (REQ-AGT-003): it watches the labels of the terminals of every connected
 * core ([snapshots], from [AgentsManager]), claims every new request in [AgentClaims] and, only if it won,
 * carries it out with the same operations as the user's commands ([WorkspaceManager.act]) and answers in the
 * terminal's [AgentCommandCodec.RESPONSE_LABEL]. A request found in the snapshot of a new session runs too, if
 * it has no answer: an agent that gave up has removed it. Selection and focus wait for a window of the machine
 * on this device, so that a device without one leaves them to another.
 */
class AgentCommands(
    private val sessions: StateFlow<Map<String, CoreRpc>>,
    private val snapshots: StateFlow<Map<String, AgentSnapshot>>,
    private val workspaces: WorkspaceManager,
    private val windows: AgentWindows,
    private val scope: CoroutineScope,
    private val log: AgentCommandLog = AgentCommandLog { _, _, _ -> }
) {
    private val runner = AgentCommandRunner(snapshots, workspaces, windows)

    /** Requests already taken by this device, as machine, terminal and request id. */
    private val started = mutableSetOf<Triple<String, String, String>>()

    init {
        scope.launch {
            combine(snapshots, windows.machines) { all, open -> all to open }.collect { (all, open) ->
                all.forEach { (machineId, snapshot) -> scan(machineId, snapshot, open) }
            }
        }
    }

    private fun scan(machineId: String, snapshot: AgentSnapshot, open: Set<String>) {
        val due = mutableListOf<Pair<AgentRecord, AgentCommandRequest>>()
        synchronized(started) {
            // Forget requests that are gone: answered, withdrawn or replaced.
            started.removeAll { (m, address, id) ->
                m == machineId && snapshot.records[address]?.labels?.get(AgentCommandCodec.REQUEST_LABEL)
                    ?.let(AgentCommandCodec::request)?.id != id
            }
            snapshot.records.values.filter { it.live }.forEach { record ->
                val request = AgentCommandCodec.request(record.labels[AgentCommandCodec.REQUEST_LABEL])
                    ?: return@forEach
                val answered = AgentCommandCodec.answeredId(record.labels[AgentCommandCodec.RESPONSE_LABEL])
                val key = Triple(machineId, record.address, request.id)
                val waitsForWindow = machineId !in open &&
                    runCatching { AgentCommandCodec.command(request).needsWindow }.getOrDefault(false)
                if (answered != request.id && key !in started && !waitsForWindow) {
                    started += key
                    due += record to request
                }
            }
        }
        val known = snapshot.records.keys
        due.forEach { (record, request) -> scope.launch { handle(machineId, record, request, known) } }
    }

    private suspend fun handle(
        machineId: String,
        record: AgentRecord,
        request: AgentCommandRequest,
        known: Set<String>
    ) {
        val action = AgentCommandCodec.loggable(request.action)
        val rpc = sessions.value[machineId] ?: return
        val outcome = when (claim(rpc, record.address, request.id, known)) {
            null -> "claim_failed"
            false -> "claimed_elsewhere"
            true -> respond(rpc, machineId, record, request)
        }
        log.log(machineId, action, outcome)
    }

    /** True when this device took the request, false when another did, null when the claim failed. */
    private suspend fun claim(rpc: CoreRpc, address: String, id: String, known: Set<String>): Boolean? = try {
        AgentClaims(rpc).claim(address, id, known)
    } catch (e: CancellationException) {
        throw e
    } catch (
        @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception
    ) {
        null
    }

    /** Carries out the request and writes the answer; returns the outcome to log. */
    private suspend fun respond(
        rpc: CoreRpc,
        machineId: String,
        record: AgentRecord,
        request: AgentCommandRequest
    ): String {
        val (answer, outcome) = answer(machineId, record, request)
        return try {
            val params = buildJsonObject {
                put("terminal", record.address)
                putJsonObject("labels") { put(AgentCommandCodec.RESPONSE_LABEL, answer) }
            }
            rpc.call("terminal.label", params)
            outcome
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception
        ) {
            "$outcome, answer_failed"
        }
    }

    /** The answer to write and the outcome to log. */
    private suspend fun answer(
        machineId: String,
        record: AgentRecord,
        request: AgentCommandRequest
    ): Pair<String, String> = try {
        val result = runner.execute(machineId, record, AgentCommandCodec.command(request))
        AgentCommandCodec.ok(request.id, result) to "ok"
    } catch (e: CancellationException) {
        throw e
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception
    ) {
        val (kind, message) = failure(e)
        AgentCommandCodec.error(request.id, kind, message) to kind
    }

    private fun failure(e: Exception): Pair<String, String> = when (e) {
        is AgentCommandFailure -> e.kind to e.message

        is NotConnectedException -> FAILED to "macchina non collegata"

        is RpcException -> (if (e.error.kind == CONFLICT) CONFLICT else FAILED) to (e.message ?: FAILED)

        // `check` of the workspace edits: a space or pane removed meanwhile.
        is IllegalStateException -> NOT_FOUND to (e.message ?: NOT_FOUND)

        else -> FAILED to (e.message ?: e.javaClass.simpleName)
    }
}
