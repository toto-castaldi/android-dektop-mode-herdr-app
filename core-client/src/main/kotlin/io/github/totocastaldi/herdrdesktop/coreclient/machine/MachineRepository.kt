package io.github.totocastaldi.herdrdesktop.coreclient.machine

import io.github.totocastaldi.herdrdesktop.coreclient.hostkey.HostKey
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The saved machines, kept in memory and written to [store] on every change. */
class MachineRepository(
    private val store: MachineStore,
    private val newId: () -> String = { UUID.randomUUID().toString() }
) {
    private val state = MutableStateFlow(store.load())
    val machines: StateFlow<List<Machine>> = state.asStateFlow()

    fun get(id: String): Machine? = state.value.firstOrNull { it.id == id }

    fun add(name: String, host: String, port: Int, user: String, auth: MachineAuth): Machine {
        val machine = Machine(newId(), name.trim(), host.trim(), port, user.trim(), auth)
        change { it + machine }
        return machine
    }

    /**
     * Replaces the machine with the same id by [changed], trimming its fields. The confirmed host
     * key is kept only if host and port are unchanged; it is never set through this method.
     */
    fun update(changed: Machine) {
        require(!changed.isLocal) { "The local machine is not editable" }
        change { list ->
            list.map { old ->
                if (old.id != changed.id) {
                    old
                } else {
                    val updated = changed.copy(
                        name = changed.name.trim(),
                        host = changed.host.trim(),
                        user = changed.user.trim()
                    )
                    val sameEndpoint = old.host == updated.host && old.port == updated.port
                    updated.copy(hostKey = old.hostKey.takeIf { sameEndpoint })
                }
            }
        }
    }

    fun confirmHostKey(id: String, key: HostKey) = change { list ->
        list.map { if (it.id == id) it.copy(hostKey = key) else it }
    }

    /** Adds the predefined local machine ([LocalMachine]) at the top of the list if it is missing. */
    fun ensureLocal(name: String) {
        if (get(LocalMachine.ID) == null) change { listOf(LocalMachine.create(name)) + it }
    }

    /** Forgets the confirmed host key of the local machine, after the user reset the Linux Terminal VM. */
    fun resetLocalHostKey() = change { list -> list.map { if (it.isLocal) it.copy(hostKey = null) else it } }

    /** Removes a machine; the predefined local machine cannot be removed. */
    fun remove(id: String) {
        require(id != LocalMachine.ID) { "The local machine cannot be removed" }
        change { list -> list.filterNot { it.id == id } }
    }

    private fun change(transform: (List<Machine>) -> List<Machine>) {
        state.update { old -> transform(old).also { store.save(it) } }
    }
}
