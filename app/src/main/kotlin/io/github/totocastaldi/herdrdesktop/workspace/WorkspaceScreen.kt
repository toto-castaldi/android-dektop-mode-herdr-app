package io.github.totocastaldi.herdrdesktop.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.totocastaldi.herdrdesktop.AppContainer
import io.github.totocastaldi.herdrdesktop.R
import io.github.totocastaldi.herdrdesktop.WindowTarget
import io.github.totocastaldi.herdrdesktop.agents.PaneFocus
import io.github.totocastaldi.herdrdesktop.agents.PeekRequest
import io.github.totocastaldi.herdrdesktop.agents.WindowSelect
import io.github.totocastaldi.herdrdesktop.coreclient.agents.AgentsManager
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.Axis
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.Space
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.Tab
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.WorkspaceManager
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.WorkspaceNames
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.WorkspaceState
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.closePane
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.keepAsTab
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.resizeSplit
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.splitPane
import io.github.totocastaldi.herdrdesktop.keyboard.KeyboardLink
import io.github.totocastaldi.herdrdesktop.keyboard.Shortcut
import io.github.totocastaldi.herdrdesktop.keyboard.cycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

/** How often the shown folders and the document are read again from the core while the screen is open. */
private const val REFRESH_MILLIS = 5_000L

/** Spaces and tabs of a connected machine (REQ-WS-001); every change goes to the core first. */
@Composable
fun WorkspaceScreen(
    container: AppContainer,
    machineId: String,
    requests: AgentRequests,
    window: WindowOptions,
    onBack: () -> Unit
) {
    val manager = container.workspaces
    val state by manager.state(machineId).collectAsState()
    val machine = container.machines.machines.collectAsState().value.firstOrNull { it.id == machineId }
    LaunchedEffect(machineId) {
        while (true) {
            delay(REFRESH_MILLIS)
            manager.refresh(machineId)
        }
    }
    val actions = remember(manager, machineId) { ManagerActions(manager, machineId) }
    val rpc = container.connections.sessions.collectAsState().value[machineId]
    val host = remember(rpc) { rpc?.let { PaneHost(it, container.scope, container.frameObserver) } }
    val shown = rememberShownAgents(container.agents, machineId)
    val agents = AgentLink(
        requests.focus?.takeIf { it.machineId == machineId },
        shown,
        AgentPeek(requests.peek?.takeIf { it.machineId == machineId }, requests.onClosePeek),
        requests.select?.takeIf { it.machineId == machineId },
        requests.onSelected,
        requests.onSelectDone
    )
    WorkspaceContent(machine?.name.orEmpty(), state, actions, onBack, WorkspaceLinks(agents, window, host))
}

/** What the user can do on the panes of a tab; each action is sent to the core. */
interface PaneActions {
    /** Splits pane [paneId] along [axis] with a new shell started in [folder] (the core's default when null). */
    fun splitPane(spaceId: String, tabId: String, paneId: String, axis: Axis, folder: String?)

    /** Closes pane [paneId] and ends its terminal; the last pane closes the tab. */
    fun closePane(spaceId: String, tabId: String, paneId: String)

    fun resizeSplit(spaceId: String, tabId: String, splitId: String, weights: List<Double>)
}

/**
 * Tells [agents] which terminals of [machineId] are on screen while the app is in the foreground (resumed), so
 * that Done agents the user looks at become Idle; nothing is on screen once the screen leaves. Each window reports
 * as its own viewer.
 */
@Composable
private fun rememberShownAgents(agents: AgentsManager, machineId: String): (Set<String>) -> Unit {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val resumed = lifecycle.currentStateFlow.collectAsState().value.isAtLeast(Lifecycle.State.RESUMED)
    var shown by remember(machineId) { mutableStateOf(emptySet<String>()) }
    // One viewer per window, so that two windows on the same machine do not overwrite each other (BUG-002).
    val viewer = remember { Any() }
    LaunchedEffect(machineId, shown, resumed) {
        agents.setLooking(machineId, if (resumed) shown else emptySet(), viewer)
    }
    DisposableEffect(machineId) { onDispose { agents.setLooking(machineId, emptySet(), viewer) } }
    return { shown = it }
}

/**
 * What the agents sidebar and the agents' commands ask of a window: the pane to bring to the front, the agent to
 * peek at, the space and tab to select; [onSelected] is told the space the window shows.
 */
class AgentRequests(
    val focus: PaneFocus?,
    val peek: PeekRequest?,
    val select: WindowSelect? = null,
    val onSelected: (String?) -> Unit = {},
    val onSelectDone: () -> Unit = {},
    val onClosePeek: () -> Unit
)

/** The agent shown in peek over the current tab (none when [request] is null) and how the peek closes. */
class AgentPeek(val request: PeekRequest? = null, val onClose: () -> Unit = {})

/**
 * Link with the agents sidebar: the pane to bring to the front, where to report the terminals on screen and
 * the agent in peek.
 */
class AgentLink(
    val focus: PaneFocus? = null,
    val onShown: (Set<String>) -> Unit = {},
    val peek: AgentPeek = AgentPeek(),
    val select: WindowSelect? = null,
    val onSelected: (String?) -> Unit = {},
    val onSelectDone: () -> Unit = {}
)

/** What the user can do on spaces and tabs; each action is sent to the core. */
interface WorkspaceActions : PaneActions {
    fun createSpace()

    fun renameSpace(spaceId: String, name: String)

    fun moveSpace(spaceId: String, toIndex: Int)

    fun closeSpace(spaceId: String)

    fun createTab(spaceId: String, folder: String?)

    fun renameTab(spaceId: String, tabId: String, name: String)

    fun moveTab(spaceId: String, tabId: String, toIndex: Int)

    fun closeTab(spaceId: String, tabId: String)

    /** Keeps the running terminal [address] as a new tab at the end of [spaceId] (peek, REQ-AGT-002). */
    fun keepAsTab(spaceId: String, address: String)
}

private class ManagerActions(private val m: WorkspaceManager, private val id: String) :
    WorkspaceActions,
    PaneActions by ManagerPaneActions(m, id) {
    override fun createSpace() {
        m.perform(id) { createSpace() }
    }

    override fun renameSpace(spaceId: String, name: String) {
        m.perform(id) { renameSpace(spaceId, name) }
    }

    override fun moveSpace(spaceId: String, toIndex: Int) {
        m.perform(id) { moveSpace(spaceId, toIndex) }
    }

    override fun closeSpace(spaceId: String) {
        m.perform(id) { closeSpace(spaceId) }
    }

    override fun createTab(spaceId: String, folder: String?) {
        m.perform(id) { createTab(spaceId, folder) }
    }

    override fun renameTab(spaceId: String, tabId: String, name: String) {
        m.perform(id) { renameTab(spaceId, tabId, name) }
    }

    override fun moveTab(spaceId: String, tabId: String, toIndex: Int) {
        m.perform(id) { moveTab(spaceId, tabId, toIndex) }
    }

    override fun closeTab(spaceId: String, tabId: String) {
        m.perform(id) { closeTab(spaceId, tabId) }
    }

    override fun keepAsTab(spaceId: String, address: String) {
        m.perform(id) { keepAsTab(spaceId, address) }
    }
}

private class ManagerPaneActions(private val m: WorkspaceManager, private val id: String) : PaneActions {
    override fun splitPane(spaceId: String, tabId: String, paneId: String, axis: Axis, folder: String?) {
        m.perform(id) { splitPane(spaceId, tabId, paneId, axis, folder) }
    }

    override fun closePane(spaceId: String, tabId: String, paneId: String) {
        m.perform(id) { closePane(spaceId, tabId, paneId) }
    }

    override fun resizeSplit(spaceId: String, tabId: String, splitId: String, weights: List<Double>) {
        m.perform(id) { resizeSplit(spaceId, tabId, splitId, weights) }
    }
}

/** The space a window opens on, how it opens another window (none when null) and its keyboard shortcuts. */
class WindowOptions(
    val initialSpace: String? = null,
    val onNewWindow: ((spaceId: String?) -> Unit)? = null,
    val keyboard: KeyboardLink = KeyboardLink()
)

/**
 * What the screen shares with the rest of its window: the agents sidebar, the window itself and how its panes
 * reach their terminals ([host], null when the screen only shows the layout).
 */
class WorkspaceLinks(
    val agents: AgentLink = AgentLink(),
    val window: WindowOptions = WindowOptions(),
    val host: PaneHost? = null
)

/**
 * Spaces and tabs of one window. The selection belongs to the window and survives resizing, display and
 * density changes; [WindowOptions.onNewWindow] opens another window on another space ([WindowTarget.otherSpace]).
 */
@Composable
fun WorkspaceContent(
    machineName: String,
    state: WorkspaceState,
    actions: WorkspaceActions,
    onBack: () -> Unit,
    links: WorkspaceLinks = WorkspaceLinks()
) {
    var selectedSpace by rememberSaveable { mutableStateOf(links.window.initialSpace) }
    val onNewWindow = links.window.onNewWindow
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.workspace_back)) }
                Text(machineName, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                links.window.keyboard.onHelp?.let { onHelp ->
                    TextButton(onClick = onHelp, Modifier.testTag("shortcut-help-button")) {
                        Text(stringResource(R.string.shortcut_help_button))
                    }
                }
                if (onNewWindow != null) {
                    TextButton(onClick = {
                        val ids = (state as? WorkspaceState.Ready)?.workspace?.spaces.orEmpty().map { it.id }
                        val current = selectedSpace?.takeIf { it in ids } ?: ids.firstOrNull()
                        onNewWindow(WindowTarget.otherSpace(ids, current))
                    }) { Text(stringResource(R.string.workspace_new_window)) }
                }
            }
            HorizontalDivider()
            when (state) {
                WorkspaceState.Unavailable -> Message(stringResource(R.string.workspace_unavailable))

                WorkspaceState.Loading -> Message(stringResource(R.string.workspace_loading))

                is WorkspaceState.Failed -> Message(stringResource(R.string.workspace_failed, state.reason))

                is WorkspaceState.Ready ->
                    ReadyContent(state, actions, links, selectedSpace) { selectedSpace = it }
            }
        }
    }
}

@Composable
internal fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp)) { Text(text) }
}

@Composable
private fun ReadyContent(
    state: WorkspaceState.Ready,
    actions: WorkspaceActions,
    links: WorkspaceLinks,
    selectedSpace: String?,
    onSelectSpace: (String) -> Unit
) {
    val agents = links.agents
    val spaces = state.workspace.spaces
    val selectedTabs = rememberSaveable(saver = TabSelectionSaver) { mutableStateMapOf() }
    var known by remember { mutableStateOf<Set<String>?>(null) }
    var prompt by remember { mutableStateOf<Prompt?>(null) }

    // A space or tab that appears while the screen is open is the one just created: select it.
    LaunchedEffect(state.workspace) {
        known?.let { old ->
            spaces.firstOrNull { it.id !in old }?.let { onSelectSpace(it.id) }
            spaces.forEach { s -> s.tabs.lastOrNull { it.id !in old }?.let { selectedTabs[s.id] = it.id } }
        }
        known = spaces.flatMap { s -> s.tabs.map { it.id } + s.id }.toSet()
    }

    // An agent selected in the sidebar brings its space and tab to the front.
    LaunchedEffect(agents.focus) {
        agents.focus?.location?.let {
            onSelectSpace(it.spaceId)
            selectedTabs[it.spaceId] = it.tabId
        }
    }

    // An agent's command (space.select, tab.select) brings a space and maybe a tab to the front.
    LaunchedEffect(agents.select) {
        agents.select?.let { request ->
            onSelectSpace(request.spaceId)
            request.tabId?.let { selectedTabs[request.spaceId] = it }
            // Applied once: a screen composed again later (reconnection) does not select it again.
            agents.onSelectDone()
        }
    }

    val space = spaces.firstOrNull { it.id == selectedSpace } ?: spaces.firstOrNull()
    LaunchedEffect(space?.id) { agents.onSelected(space?.id) }
    val tab = space?.let { s -> s.tabs.firstOrNull { it.id == selectedTabs[s.id] } ?: s.tabs.firstOrNull() }
    val peek = agents.peek.request
    // A terminal in peek is on screen too: looking at it there marks it as seen.
    val onScreen = tab?.terminals.orEmpty().toSet() + listOfNotNull(peek?.address)
    LaunchedEffect(onScreen) { agents.onShown(onScreen) }
    val controls = Controls(actions) { prompt = it }
    val spaceLabel = { index: Int, s: Space -> WorkspaceNames.displayName(s, state::cwdOf) ?: "${index + 1}" }
    val current = SpaceRef(space, tab, state)
    val keys = KeyTarget(current, controls, spaceLabel, onSelectSpace) { s, t -> selectedTabs[s] = t }
    WorkspaceShortcuts(links.window.keyboard.shortcuts, keys)

    Column(Modifier.fillMaxSize()) {
        state.error?.let {
            Text(
                stringResource(R.string.workspace_error, it),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(8.dp)
            )
        }
        Row(Modifier.fillMaxSize()) {
            SpaceList(spaces, space?.id, spaceLabel, controls, onSelectSpace)
            VerticalDivider()
            Box(Modifier.fillMaxSize()) {
                SpaceContent(current, controls, links, peek == null) {
                    selectedTabs[it.first] = it.second
                }
                PeekOver(SpaceRef(space, tab, state), actions, links)
            }
        }
    }

    prompt?.let { PromptDialog(it, actions) { prompt = null } }
}

/** What the space and tab shortcuts act on: the current selection and how it changes. */
private class KeyTarget(
    val ref: SpaceRef,
    val controls: Controls,
    val spaceLabel: (Int, Space) -> String,
    val onSelectSpace: (String) -> Unit,
    val onSelectTab: (String, String) -> Unit
)

/** Carries out the space and tab shortcuts on the current selection; pane shortcuts are the tab's. */
@Composable
private fun WorkspaceShortcuts(shortcuts: Flow<Shortcut>, target: KeyTarget) {
    val current by rememberUpdatedState(target)
    LaunchedEffect(shortcuts) {
        shortcuts.collect { shortcut ->
            spaceShortcut(shortcut, current)
            tabShortcut(shortcut, current)
        }
    }
}

private fun spaceShortcut(shortcut: Shortcut, t: KeyTarget) {
    val spaces = t.ref.state.workspace.spaces
    val space = t.ref.space
    when (shortcut) {
        Shortcut.NEW_SPACE -> t.controls.actions.createSpace()

        Shortcut.CLOSE_SPACE -> space?.let {
            t.controls.prompt(Prompt.CloseSpace(it, t.spaceLabel(spaces.indexOf(it), it)))
        }

        Shortcut.NEXT_SPACE -> cycle(spaces.map { it.id }, space?.id, 1)?.let(t.onSelectSpace)

        Shortcut.PREVIOUS_SPACE -> cycle(spaces.map { it.id }, space?.id, -1)?.let(t.onSelectSpace)

        else -> Unit
    }
}

private fun tabShortcut(shortcut: Shortcut, t: KeyTarget) {
    val space = t.ref.space ?: return
    val tab = t.ref.tab
    val step = when (shortcut) {
        Shortcut.NEXT_TAB -> 1
        Shortcut.PREVIOUS_TAB -> -1
        else -> 0
    }
    when {
        shortcut == Shortcut.NEW_TAB ->
            t.controls.actions.createTab(space.id, WorkspaceNames.newTabFolder(space, tab, t.ref.state::cwdOf))

        shortcut == Shortcut.CLOSE_TAB && tab != null ->
            t.controls.prompt(Prompt.CloseTab(space.id, tab, WorkspaceNames.tabLabel(tab, space.tabs.indexOf(tab))))

        step != 0 -> cycle(space.tabs.map { it.id }, tab?.id, step)?.let { t.onSelectTab(space.id, it) }
    }
}

/** The selected space and tab (none when null), with the core state they show. */
private class SpaceRef(val space: Space?, val tab: Tab?, val state: WorkspaceState.Ready)

/** Tabs and panes of the selected space; [active] is false while a peek over them has the keyboard. */
@Composable
private fun SpaceContent(
    ref: SpaceRef,
    controls: Controls,
    links: WorkspaceLinks,
    active: Boolean,
    onSelectTab: (Pair<String, String>) -> Unit
) {
    val space = ref.space
    val tab = ref.tab
    val state = ref.state
    if (space == null) {
        Message(stringResource(R.string.workspace_no_spaces))
        return
    }
    Column(Modifier.fillMaxSize()) {
        TabBar(space, tab?.id, controls, { onSelectTab(space.id to it) }) {
            controls.actions.createTab(space.id, WorkspaceNames.newTabFolder(space, tab, state::cwdOf))
        }
        HorizontalDivider()
        if (tab == null) {
            Message(stringResource(R.string.workspace_no_tabs))
        } else {
            val focusPane = links.agents.focus?.location?.takeIf { it.tabId == tab.id }?.paneId
            val request = PaneFocusRequest(focusPane, active, links.agents.focus, links.window.keyboard.shortcuts)
            TabPanes(space.id, tab, state, controls, links.host, request)
        }
    }
}

/**
 * The agent in peek over the selected tab, if any; once a pane shows its terminal (kept as tab, for one) the
 * peek closes.
 */
@Composable
private fun PeekOver(ref: SpaceRef, actions: WorkspaceActions, links: WorkspaceLinks) {
    val link = links.agents.peek
    val peek = link.request ?: return
    val shown = ref.state.workspace.shows(peek.address)
    LaunchedEffect(peek, shown) { if (shown) link.onClose() }
    if (shown) return
    val keep = ref.space?.let { s -> { actions.keepAsTab(s.id, peek.address) } }
    Peek(peek, links.host, keep, link.onClose)
}

/** The selected tab of each space (space id to tab id), kept with the window's saved state. */
private val TabSelectionSaver = Saver<SnapshotStateMap<String, String>, ArrayList<String>>(
    save = { map -> ArrayList(map.flatMap { (space, tab) -> listOf(space, tab) }) },
    restore = { list -> mutableStateMapOf<String, String>().apply { list.chunked(2).forEach { put(it[0], it[1]) } } }
)
