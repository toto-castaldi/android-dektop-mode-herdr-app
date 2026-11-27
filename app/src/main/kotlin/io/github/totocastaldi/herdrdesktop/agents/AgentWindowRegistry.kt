package io.github.totocastaldi.herdrdesktop.agents

import android.os.Handler
import android.os.Looper
import io.github.totocastaldi.herdrdesktop.coreclient.agents.AgentWindows
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.PaneLocation
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Request to bring a space (and a tab) to the front; a new instance every time, as for [PaneFocus]. */
class WindowSelect(val machineId: String, val spaceId: String, val tabId: String?)

/** What a window of the app does for an agent's command; set by the window, called on the main thread. */
interface AgentWindowActions {
    fun select(request: WindowSelect)

    fun focus(request: PaneFocus)

    fun peek(machineId: String, address: String)
}

/** One window as the agents' commands see it: the machine it shows and the space it has selected. */
class AgentWindow internal constructor(private val registry: AgentWindowRegistry) {
    @Volatile
    var machineId: String? = null
        set(value) {
            field = value
            registry.changed()
        }

    @Volatile
    var selectedSpace: String? = null

    /** When the window last came to the foreground; the most recent one takes the commands. */
    @Volatile
    var resumedAt: Long = 0

    @Volatile
    var actions: AgentWindowActions? = null
}

/**
 * The app's windows for the agents' commands (REQ-AGT-003): a command on a machine goes to the window showing
 * it that was in the foreground last. Window actions run through [post], by default on the main thread.
 */
class AgentWindowRegistry(private val post: (Runnable) -> Unit = mainThread()) : AgentWindows {
    private val windows = CopyOnWriteArrayList<AgentWindow>()
    private val machinesState = MutableStateFlow<Set<String>>(emptySet())

    override val machines: StateFlow<Set<String>> = machinesState.asStateFlow()

    fun register(): AgentWindow = AgentWindow(this).also { windows += it }

    fun unregister(window: AgentWindow) {
        windows -= window
        changed()
    }

    internal fun changed() {
        machinesState.value = windows.mapNotNull { it.machineId }.toSet()
    }

    /** The window that takes the commands on [machineId], null when no window shows it. */
    fun target(machineId: String): AgentWindow? =
        windows.filter { it.machineId == machineId && it.actions != null }.maxByOrNull { it.resumedAt }

    override fun selectedSpace(machineId: String): String? = target(machineId)?.selectedSpace

    override fun select(machineId: String, spaceId: String, tabId: String?): Boolean =
        run(machineId) { it.select(WindowSelect(machineId, spaceId, tabId)) }

    override fun focus(machineId: String, address: String, location: PaneLocation?): Boolean = run(machineId) {
        if (location != null) it.focus(PaneFocus(machineId, location)) else it.peek(machineId, address)
    }

    private fun run(machineId: String, action: (AgentWindowActions) -> Unit): Boolean {
        val actions = target(machineId)?.actions ?: return false
        post(Runnable { action(actions) })
        return true
    }

    private companion object {
        fun mainThread(): (Runnable) -> Unit {
            val handler by lazy { Handler(Looper.getMainLooper()) }
            return { handler.post(it) }
        }
    }
}
