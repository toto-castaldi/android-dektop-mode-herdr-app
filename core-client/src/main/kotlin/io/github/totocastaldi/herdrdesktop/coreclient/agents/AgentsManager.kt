package io.github.totocastaldi.herdrdesktop.coreclient.agents

import io.github.totocastaldi.herdrdesktop.coreclient.connection.CoreRpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** One agent as the sidebar shows it. */
data class AgentView(
    val machineId: String,
    val address: String,
    val name: String,
    val state: AgentState,
    val message: String,
    val parent: String?
)

/**
 * Agents of every connected machine (REQ-AGT-001): for each session it subscribes to the core's events, keeps
 * the snapshot up to date and takes a fresh one at every new session. The shown state is always the core's:
 * looking at an agent writes `herdr.seen` on the core, and the label coming back turns Done into Idle.
 */
class AgentsManager(private val sessions: StateFlow<Map<String, CoreRpc>>, private val scope: CoroutineScope) {
    private val snapshots = MutableStateFlow<Map<String, AgentSnapshot>>(emptyMap())
    private val followers = mutableMapOf<String, Pair<CoreRpc, Job>>()
    private val looking = mutableMapOf<String, Set<String>>()

    /** Per machine and address, the turn already written while the agent stays on screen. */
    private val written = mutableMapOf<String, MutableMap<String, Long>>()
    private val agentsState = MutableStateFlow<Map<String, List<AgentView>>>(emptyMap())

    /** Agents by machine id, in the core's order; a machine without a session has none. */
    val agents: StateFlow<Map<String, List<AgentView>>> = agentsState.asStateFlow()

    init {
        scope.launch { sessions.collect(::onSessions) }
    }

    /**
     * The agents of [machineId] the user really has in front of them (app in the foreground, pane on screen);
     * an empty set when nothing is. Each of them gets `herdr.seen` raised to its turn, once per turn.
     */
    fun setLooking(machineId: String, addresses: Set<String>) {
        synchronized(looking) {
            looking[machineId] = addresses
            written[machineId]?.keys?.retainAll(addresses)
        }
        markSeen(machineId)
    }

    private fun onSessions(current: Map<String, CoreRpc>) {
        synchronized(followers) {
            (followers.keys - current.keys).forEach { id ->
                followers.remove(id)?.second?.cancel()
                publish(id, null)
            }
            current.forEach { (id, rpc) ->
                if (followers[id]?.first !== rpc) {
                    followers[id]?.second?.cancel()
                    followers[id] = rpc to scope.launch { follow(id, rpc) }
                }
            }
        }
    }

    private suspend fun follow(machineId: String, rpc: CoreRpc) {
        try {
            val result = rpc.call("events.subscribe", JsonObject(emptyMap()))
            var snapshot = AgentSnapshot.from(result as JsonObject)
            publish(machineId, snapshot)
            val channel = AgentSnapshot.channelOf(result) ?: return
            rpc.lines(channel).collect { line ->
                val message = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull()
                    ?: return@collect
                snapshot = snapshot.apply(message)
                if (sessions.value[machineId] === rpc) publish(machineId, snapshot)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception
        ) {
            // RPC error or dropped session: the next session subscribes again.
        }
        // Without a subscription the agents of this session are no longer known.
        if (sessions.value[machineId] === rpc) publish(machineId, null)
    }

    private fun publish(machineId: String, snapshot: AgentSnapshot?) {
        snapshots.update { if (snapshot == null) it - machineId else it + (machineId to snapshot) }
        agentsState.update { all ->
            if (snapshot == null) {
                all - machineId
            } else {
                all + (machineId to snapshot.agents.map { it.view(machineId) })
            }
        }
        markSeen(machineId)
    }

    /** Writes `herdr.seen=<turn>` for the agents on screen whose label is missing or lower, once per turn. */
    private fun markSeen(machineId: String) {
        val rpc = sessions.value[machineId] ?: return
        val records = snapshots.value[machineId]?.records ?: return
        val due = synchronized(looking) {
            val done = written.getOrPut(machineId) { mutableMapOf() }
            looking[machineId].orEmpty().mapNotNull { records[it] }.filter { record ->
                val seen = record.seen
                record.isAgent && (seen == null || record.turn > seen) && done[record.address] != record.turn
            }.onEach { done[it.address] = it.turn }
        }
        due.forEach { record -> scope.launch { writeSeen(machineId, rpc, record) } }
    }

    private suspend fun writeSeen(machineId: String, rpc: CoreRpc, record: AgentRecord) {
        val params = buildJsonObject {
            put("terminal", record.address)
            putJsonObject("labels") { put(AgentRecord.SEEN_LABEL, record.turn.toString()) }
        }
        try {
            rpc.call("terminal.label", params)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception
        ) {
            // Not written: allow another try at the next update while it stays on screen.
            synchronized(looking) {
                written[machineId]?.let { if (it[record.address] == record.turn) it.remove(record.address) }
            }
        }
    }

    private fun AgentRecord.view(machineId: String) = AgentView(machineId, address, displayName, state, message, parent)
}
