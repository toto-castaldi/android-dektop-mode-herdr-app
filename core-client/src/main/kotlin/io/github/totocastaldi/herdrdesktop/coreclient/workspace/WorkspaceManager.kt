package io.github.totocastaldi.herdrdesktop.coreclient.workspace

import io.github.totocastaldi.herdrdesktop.coreclient.connection.CoreRpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Spaces and tabs of a machine as the app shows them. */
sealed interface WorkspaceState {
    /** The machine is not connected: nothing is shown, nothing is changed. */
    data object Unavailable : WorkspaceState

    data object Loading : WorkspaceState

    /** Copy of the core's state; [error] is the last failed action, if any. */
    data class Ready(val workspace: Workspace, val terminals: Map<String, TerminalInfo>, val error: String? = null) :
        WorkspaceState {
        fun cwdOf(address: String): String? = terminals[address]?.cwd
    }

    data class Failed(val reason: String) : WorkspaceState
}

/**
 * Keeps, for every connected machine, the spaces and tabs read from its core. Every session (also after
 * a reconnection) loads them again from the core; actions run one at a time per machine and the state
 * shown is the core's answer, never a local guess.
 */
class WorkspaceManager(
    private val sessions: StateFlow<Map<String, CoreRpc>>,
    private val scope: CoroutineScope,
    private val ids: IdGenerator = IdGenerator.Random
) {
    private val states = mutableMapOf<String, MutableStateFlow<WorkspaceState>>()
    private val locks = mutableMapOf<String, Mutex>()
    private val loaded = mutableMapOf<String, CoreRpc>()

    init {
        scope.launch { sessions.collect(::onSessions) }
    }

    fun state(machineId: String): StateFlow<WorkspaceState> = flow(machineId).asStateFlow()

    /** Reads the document and the terminals again, for example to show current folders. */
    fun refresh(machineId: String) = perform(machineId) { document.load() }

    private fun flow(machineId: String) =
        synchronized(states) { states.getOrPut(machineId) { MutableStateFlow(WorkspaceState.Unavailable) } }

    private fun lock(machineId: String) = synchronized(locks) { locks.getOrPut(machineId) { Mutex() } }

    private fun onSessions(current: Map<String, CoreRpc>) {
        val gone = synchronized(loaded) {
            val stale = loaded.keys - current.keys
            stale.forEach { loaded.remove(it) }
            stale
        }
        gone.forEach { flow(it).value = WorkspaceState.Unavailable }
        current.forEach { (id, rpc) ->
            val fresh = synchronized(loaded) { (loaded[id] !== rpc).also { if (it) loaded[id] = rpc } }
            if (fresh) {
                flow(id).value = WorkspaceState.Loading
                refresh(id)
            }
        }
    }

    /**
     * Runs [action] on the machine's core, one at a time per machine, then publishes the document it returns
     * with the current terminals. A failure keeps the shown state and reports the error; a machine that is
     * not connected is left alone.
     */
    fun perform(machineId: String, action: suspend WorkspaceClient.() -> WorkspaceSnapshot) = scope.launch {
        lock(machineId).withLock {
            val rpc = sessions.value[machineId] ?: return@withLock
            val client = WorkspaceClient(rpc, ids)
            val state = flow(machineId)
            // A session replaced or closed meanwhile no longer speaks for the machine.
            val current = { sessions.value[machineId] === rpc }
            try {
                val snapshot = client.action()
                val terminals = client.terminals.list()
                if (current()) state.value = WorkspaceState.Ready(snapshot.workspace, terminals)
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception
            ) {
                // RPC errors, a dropped session (ProtocolException, IOException) or a vanished space.
                if (current()) fail(state, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun fail(state: MutableStateFlow<WorkspaceState>, reason: String) {
        state.update { current ->
            if (current is WorkspaceState.Ready) current.copy(error = reason) else WorkspaceState.Failed(reason)
        }
    }
}
