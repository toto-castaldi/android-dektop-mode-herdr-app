package io.github.totocastaldi.herdrdesktop.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.totocastaldi.herdrdesktop.AppContainer
import io.github.totocastaldi.herdrdesktop.R
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.Space
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.WorkspaceManager
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.WorkspaceNames
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.WorkspaceState
import kotlinx.coroutines.delay

/** How often the shown folders and the document are read again from the core while the screen is open. */
private const val REFRESH_MILLIS = 5_000L

/** Spaces and tabs of a connected machine (REQ-WS-001); every change goes to the core first. */
@Composable
fun WorkspaceScreen(container: AppContainer, machineId: String, onBack: () -> Unit) {
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
    WorkspaceContent(machine?.name.orEmpty(), state, actions, onBack)
}

/** What the user can do on spaces and tabs; each action is sent to the core. */
interface WorkspaceActions {
    fun createSpace()

    fun renameSpace(spaceId: String, name: String)

    fun moveSpace(spaceId: String, toIndex: Int)

    fun closeSpace(spaceId: String)

    fun createTab(spaceId: String, folder: String?)

    fun renameTab(spaceId: String, tabId: String, name: String)

    fun moveTab(spaceId: String, tabId: String, toIndex: Int)

    fun closeTab(spaceId: String, tabId: String)
}

private class ManagerActions(private val m: WorkspaceManager, private val id: String) : WorkspaceActions {
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
}

@Composable
fun WorkspaceContent(machineName: String, state: WorkspaceState, actions: WorkspaceActions, onBack: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.workspace_back)) }
                Text(machineName, style = MaterialTheme.typography.titleLarge)
            }
            HorizontalDivider()
            when (state) {
                WorkspaceState.Unavailable -> Message(stringResource(R.string.workspace_unavailable))
                WorkspaceState.Loading -> Message(stringResource(R.string.workspace_loading))
                is WorkspaceState.Failed -> Message(stringResource(R.string.workspace_failed, state.reason))
                is WorkspaceState.Ready -> ReadyContent(state, actions)
            }
        }
    }
}

@Composable
internal fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp)) { Text(text) }
}

@Composable
private fun ReadyContent(state: WorkspaceState.Ready, actions: WorkspaceActions) {
    val spaces = state.workspace.spaces
    var selectedSpace by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedTabs = remember { mutableStateMapOf<String, String>() }
    var known by remember { mutableStateOf<Set<String>?>(null) }
    var prompt by remember { mutableStateOf<Prompt?>(null) }

    // A space or tab that appears while the screen is open is the one just created: select it.
    LaunchedEffect(state.workspace) {
        known?.let { old ->
            spaces.firstOrNull { it.id !in old }?.let { selectedSpace = it.id }
            spaces.forEach { s -> s.tabs.lastOrNull { it.id !in old }?.let { selectedTabs[s.id] = it.id } }
        }
        known = spaces.flatMap { s -> s.tabs.map { it.id } + s.id }.toSet()
    }

    val space = spaces.firstOrNull { it.id == selectedSpace } ?: spaces.firstOrNull()
    val tab = space?.let { s -> s.tabs.firstOrNull { it.id == selectedTabs[s.id] } ?: s.tabs.firstOrNull() }
    val controls = Controls(actions) { prompt = it }
    val spaceLabel = { index: Int, s: Space -> WorkspaceNames.displayName(s, state::cwdOf) ?: "${index + 1}" }

    Column(Modifier.fillMaxSize()) {
        state.error?.let {
            Text(
                stringResource(R.string.workspace_error, it),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(8.dp)
            )
        }
        Row(Modifier.fillMaxSize()) {
            SpaceList(spaces, space?.id, spaceLabel, controls) { selectedSpace = it }
            VerticalDivider()
            if (space == null) {
                Message(stringResource(R.string.workspace_no_spaces))
            } else {
                Column(Modifier.fillMaxSize()) {
                    TabBar(space, tab?.id, controls, { selectedTabs[space.id] = it }) {
                        actions.createTab(space.id, WorkspaceNames.newTabFolder(space, tab, state::cwdOf))
                    }
                    HorizontalDivider()
                    if (tab == null) Message(stringResource(R.string.workspace_no_tabs)) else TabBody(tab, state)
                }
            }
        }
    }

    prompt?.let { PromptDialog(it, actions) { prompt = null } }
}
