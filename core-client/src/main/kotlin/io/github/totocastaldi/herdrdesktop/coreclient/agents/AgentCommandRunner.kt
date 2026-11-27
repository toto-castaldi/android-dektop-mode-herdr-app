package io.github.totocastaldi.herdrdesktop.coreclient.agents

import io.github.totocastaldi.herdrdesktop.coreclient.agents.AgentCommandCodec.NOT_FOUND
import io.github.totocastaldi.herdrdesktop.coreclient.agents.AgentCommandCodec.NO_WINDOW
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.Created
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.NewTerminal
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.PaneLayout
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.PaneLocation
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.Workspace
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.WorkspaceClient
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.WorkspaceManager
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.splitPane
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.startSpace
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.startTab
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Carries out one agent's command (REQ-AGT-003) with the operations of the user's commands: the document
 * changes go through [WorkspaceManager.act] (compare-and-set, nothing ended), selection and focus through
 * [windows]. The result is what `herdr-app do` prints: `{space, tab, terminal}`, `{pane, terminal}`, ….
 */
internal class AgentCommandRunner(
    private val snapshots: StateFlow<Map<String, AgentSnapshot>>,
    private val workspaces: WorkspaceManager,
    private val windows: AgentWindows
) {
    /** Carries out [command] of [requester] on [machineId]; returns the result of the answer. */
    suspend fun execute(machineId: String, requester: AgentRecord, command: AgentCommand): JsonObject = when (command) {
        is AgentCommand.SpaceNew -> workspaces.act(machineId) {
            val folder = command.folder ?: cwdOf(requester.address)
            created(startSpace(NewTerminal(folder, command.argv), command.name))
        }

        is AgentCommand.TabNew -> workspaces.act(machineId) {
            val spaceId = tabSpace(document.load().workspace, command.space, requester, machineId)
            val folder = command.folder ?: cwdOf(requester.address)
            created(startTab(spaceId, NewTerminal(folder, command.argv), command.name))
        }

        is AgentCommand.PaneSplit -> workspaces.act(machineId) { split(command, requester) }

        is AgentCommand.SpaceSelect -> select(machineId) { ws -> spaceOf(ws, command) to null }

        is AgentCommand.TabSelect -> select(machineId) { ws -> tabOf(ws, command, machineId) }

        is AgentCommand.AgentFocus -> focus(machineId, command.terminal)
    }

    private suspend fun WorkspaceClient.split(command: AgentCommand.PaneSplit, requester: AgentRecord) =
        document.load().workspace.let { ws ->
            val at = command.pane?.let { paneById(ws, it) ?: throw notFound("pane ${command.pane}") }
                ?: ws.paneOf(requester.address)
                ?: throw notFound("il terminale che chiede non è in un pane: serve pane")
            val folder = ws.terminalOf(at)?.let { cwdOf(it) }
            val made = splitPane(at, command.axis, NewTerminal(folder, command.argv))
            made.snapshot to buildJsonObject {
                made.location?.let { put("pane", it.paneId) }
                put("terminal", made.terminal)
            }
        }

    private fun spaceOf(ws: Workspace, command: AgentCommand.SpaceSelect): String =
        (command.id?.let(ws::space) ?: command.index?.let { ws.spaces.getOrNull(it - 1) })?.id
            ?: throw notFound("spazio")

    /** By id, or by position in the space the window shows (the first space when unknown). */
    private fun tabOf(ws: Workspace, command: AgentCommand.TabSelect, machineId: String): Pair<String, String> {
        val id = command.id
        val space = if (id != null) {
            ws.spaces.firstOrNull { it.tab(id) != null }
        } else {
            windows.selectedSpace(machineId)?.let(ws::space) ?: ws.spaces.firstOrNull()
        }
        val tab = (if (id != null) space?.tab(id) else space?.tabs?.getOrNull((command.index ?: 0) - 1))
        return (space ?: throw notFound("tab")).id to (tab ?: throw notFound("tab")).id
    }

    /** Brings to the front the space and tab [pick] chooses in the core's document. */
    private suspend fun select(machineId: String, pick: (Workspace) -> Pair<String, String?>): JsonObject {
        val ws = workspaces.act(machineId) { document.load().let { it to it.workspace } }
        val (spaceId, tabId) = pick(ws)
        if (!windows.select(machineId, spaceId, tabId)) throw noWindow()
        return buildJsonObject {
            put("space", spaceId)
            tabId?.let { put("tab", it) }
        }
    }

    private suspend fun focus(machineId: String, address: String): JsonObject {
        val record = snapshots.value[machineId]?.records?.get(address)?.takeIf { it.live }
            ?: throw notFound("terminale $address")
        val ws = workspaces.act(machineId) { document.load().let { it to it.workspace } }
        val location = ws.paneOf(record.address)
        if (!windows.focus(machineId, record.address, location)) throw noWindow()
        return buildJsonObject {
            put("terminal", record.address)
            location?.let {
                put("space", it.spaceId)
                put("tab", it.tabId)
                put("pane", it.paneId)
            }
        }
    }

    /**
     * The space of a new tab: [named] if given, else the one showing the [requester], else the one of its
     * app label, else the one the window shows, else the first.
     */
    private fun tabSpace(ws: Workspace, named: String?, requester: AgentRecord, machineId: String): String {
        if (named != null) return ws.space(named)?.id ?: throw notFound("spazio $named")
        return ws.paneOf(requester.address)?.spaceId
            ?: requester.labels[AgentRecord.APP_SPACE_LABEL]?.takeIf { ws.space(it) != null }
            ?: windows.selectedSpace(machineId)?.takeIf { ws.space(it) != null }
            ?: ws.spaces.firstOrNull()?.id
            ?: throw notFound("nessuno spazio: usa space.new")
    }

    private suspend fun WorkspaceClient.cwdOf(address: String): String? =
        terminals.list()[address]?.cwd?.takeIf { it.isNotBlank() }
}

private fun created(made: Created) = made.snapshot to buildJsonObject {
    made.location?.let {
        put("space", it.spaceId)
        put("tab", it.tabId)
    }
    put("terminal", made.terminal)
}

private fun paneById(ws: Workspace, paneId: String): PaneLocation? = ws.spaces.firstNotNullOfOrNull { s ->
    s.tabs.firstNotNullOfOrNull { t ->
        PaneLayout.leaf(t.root, paneId)?.let { PaneLocation(s.id, t.id, it.id) }
    }
}

private fun Workspace.terminalOf(at: PaneLocation): String? =
    space(at.spaceId)?.tab(at.tabId)?.let { PaneLayout.leaf(it.root, at.paneId)?.terminal }

private fun notFound(what: String) = AgentCommandFailure(NOT_FOUND, "non trovato: $what")

private fun noWindow() = AgentCommandFailure(NO_WINDOW, "nessuna finestra dell'app mostra questa macchina")
