package io.github.totocastaldi.herdrdesktop

import android.app.Application
import android.util.Log
import io.github.totocastaldi.herdrdesktop.agents.AgentWindowRegistry
import io.github.totocastaldi.herdrdesktop.coreclient.agents.AgentCommandLog
import io.github.totocastaldi.herdrdesktop.coreclient.agents.AgentCommands
import io.github.totocastaldi.herdrdesktop.coreclient.agents.AgentsManager
import io.github.totocastaldi.herdrdesktop.coreclient.connection.ConnectionLog
import io.github.totocastaldi.herdrdesktop.coreclient.connection.ConnectionManager
import io.github.totocastaldi.herdrdesktop.coreclient.connection.DeviceAccess
import io.github.totocastaldi.herdrdesktop.coreclient.keys.AndroidKeystoreSecretCipher
import io.github.totocastaldi.herdrdesktop.coreclient.keys.AndroidKeystoreSshKeys
import io.github.totocastaldi.herdrdesktop.coreclient.keys.SecretCipher
import io.github.totocastaldi.herdrdesktop.coreclient.keys.SshKeyStore
import io.github.totocastaldi.herdrdesktop.coreclient.machine.FileMachineStore
import io.github.totocastaldi.herdrdesktop.coreclient.machine.MachineRepository
import io.github.totocastaldi.herdrdesktop.coreclient.ssh.SshlibTransport
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.WorkspaceManager
import io.github.totocastaldi.herdrdesktop.machines.AndroidLocalNetworkAccess
import io.github.totocastaldi.herdrdesktop.machines.LinuxTerminal
import io.github.totocastaldi.herdrdesktop.terminal.FrameObserver
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Application-wide objects: connections outlive activities and windows. */
class HerdrApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.connectConfirmedMachines()
    }
}

class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val keys: SshKeyStore = AndroidKeystoreSshKeys()
    val secrets: SecretCipher = AndroidKeystoreSecretCipher()
    val machines = MachineRepository(FileMachineStore(File(app.filesDir, "machines.json"))).also {
        it.ensureLocal(app.getString(R.string.local_machine_name))
    }
    val linuxTerminal = LinuxTerminal(app)
    val localNetwork = AndroidLocalNetworkAccess(app)
    private val ssh = SshlibTransport(keys, secrets)
    val connections = ConnectionManager(
        machines = machines,
        transport = ssh,
        scope = scope,
        // Status names only: no keys, passwords or terminal content (AGENTS.md §7).
        log = ConnectionLog { machine, status -> Log.i(TAG, "machine ${machine.id}: ${status.javaClass.simpleName}") },
        device = DeviceAccess(localTerminal = linuxTerminal, localNetwork = localNetwork),
        commands = ssh
    )

    /** Spaces and tabs of the connected machines, read from their cores. */
    val workspaces = WorkspaceManager(connections.sessions, scope)

    /** Agents of the connected machines and their states, from the cores' events. */
    val agents = AgentsManager(connections.sessions, scope).also { workspaces.follow(it.placements) }

    /** The app's windows, for the agents' commands that select or show something. */
    val agentWindows = AgentWindowRegistry()

    /** Commands of the agents to the app, through the cores (REQ-AGT-003). */
    val agentCommands = AgentCommands(
        connections.sessions,
        agents.snapshots,
        workspaces,
        agentWindows,
        scope,
        // Action name and outcome only, never the arguments (AGENTS.md §7).
        AgentCommandLog { machine, action, outcome -> Log.i(COMMANDS_TAG, "machine $machine: $action $outcome") }
    )

    /**
     * Told about every frame drawn by a terminal pane; set only by the measurements of REQ-NFR-003
     * (instrumented tests), null otherwise.
     */
    @Volatile
    var frameObserver: FrameObserver? = null

    /** Machines whose host key is already confirmed are connected at start. */
    fun connectConfirmedMachines() {
        machines.machines.value.filter { it.hostKey != null }.forEach { connections.connect(it.id) }
    }

    private companion object {
        const val TAG = "HerdrConnection"
        const val COMMANDS_TAG = "HerdrAgentCommands"
    }
}
