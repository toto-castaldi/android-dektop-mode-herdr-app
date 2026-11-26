package io.github.totocastaldi.herdrdesktop.agents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.totocastaldi.herdrdesktop.AppContainer
import io.github.totocastaldi.herdrdesktop.R
import io.github.totocastaldi.herdrdesktop.coreclient.agents.AgentState
import io.github.totocastaldi.herdrdesktop.coreclient.agents.AgentView
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.PaneLocation
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.WorkspaceState

/** Request to bring a pane to the front; a new instance every time, so selecting the same agent again works. */
class PaneFocus(val machineId: String, val location: PaneLocation)

/** One row of the sidebar: the agent, its machine's name and its pane, if it has one. */
class AgentRow(val agent: AgentView, val machineName: String, val pane: PaneLocation?)

/** Agents of every connected machine with their state (REQ-AGT-001); selecting one with a pane opens it. */
@Composable
fun AgentSidebar(container: AppContainer, onOpen: (PaneFocus) -> Unit) {
    val agents by container.agents.agents.collectAsState()
    val machines by container.machines.machines.collectAsState()
    val rows = machines.flatMap { machine ->
        val state = key(machine.id) { container.workspaces.state(machine.id).collectAsState().value }
        val workspace = (state as? WorkspaceState.Ready)?.workspace
        agents[machine.id].orEmpty().map { AgentRow(it, machine.name, workspace?.paneOf(it.address)) }
    }
    AgentSidebarContent(rows) { row -> row.pane?.let { onOpen(PaneFocus(row.agent.machineId, it)) } }
}

@Composable
fun AgentSidebarContent(rows: List<AgentRow>, onSelect: (AgentRow) -> Unit) {
    Surface(Modifier.width(260.dp).fillMaxHeight()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(8.dp), Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.agents_title), style = MaterialTheme.typography.titleMedium)
            if (rows.isEmpty()) {
                Text(stringResource(R.string.agents_none), style = MaterialTheme.typography.bodySmall)
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(rows, key = { "${it.agent.machineId}/${it.agent.address}" }) { row -> AgentEntry(row, onSelect) }
            }
        }
    }
}

@Composable
private fun AgentEntry(row: AgentRow, onSelect: (AgentRow) -> Unit) {
    val agent = row.agent
    val indent = if (agent.parent != null) 12.dp else 0.dp
    Column(
        Modifier.fillMaxWidth()
            .clickable(enabled = row.pane != null) { onSelect(row) }
            .padding(start = indent, top = 2.dp, bottom = 2.dp)
            .testTag("agent-${agent.machineId}-${agent.address}")
    ) {
        Text(
            "${stateLabel(agent.state)} · ${agent.name}",
            color = stateColor(agent.state),
            fontWeight = if (agent.state == AgentState.IDLE) FontWeight.Normal else FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        val where = if (row.pane == null) {
            stringResource(R.string.agents_no_pane, row.machineName, agent.address)
        } else {
            stringResource(R.string.agents_where, row.machineName, agent.address)
        }
        Text(where, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (agent.message.isNotBlank()) {
            Text(
                agent.message,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun stateLabel(state: AgentState) = stringResource(
    when (state) {
        AgentState.WORKING -> R.string.agent_working
        AgentState.BLOCKED -> R.string.agent_blocked
        AgentState.DONE -> R.string.agent_done
        AgentState.IDLE -> R.string.agent_idle
    }
)

@Composable
private fun stateColor(state: AgentState): Color = when (state) {
    AgentState.WORKING -> MaterialTheme.colorScheme.primary
    AgentState.BLOCKED -> MaterialTheme.colorScheme.error
    AgentState.DONE -> MaterialTheme.colorScheme.tertiary
    AgentState.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
}
