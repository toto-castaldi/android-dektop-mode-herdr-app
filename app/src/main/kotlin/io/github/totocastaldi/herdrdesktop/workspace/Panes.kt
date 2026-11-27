package io.github.totocastaldi.herdrdesktop.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.totocastaldi.herdrdesktop.R
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.Axis
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.PaneLayout
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.PaneNode
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.Tab
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.WorkspaceState
import io.github.totocastaldi.herdrdesktop.terminal.TerminalView
import kotlinx.coroutines.launch

/** Thickness of the draggable divider between panes. */
private val DIVIDER = 6.dp

/**
 * The pane tree of [tab] (REQ-WS-002): splits with draggable dividers, every leaf a terminal of the core.
 * The focused pane gets the keyboard; splitting, resizing and closing go to the core first.
 */
@Composable
@Suppress("LongParameterList")
internal fun TabPanes(
    spaceId: String,
    tab: Tab,
    state: WorkspaceState.Ready,
    controls: Controls,
    host: PaneHost?,
    focusRequest: PaneFocusRequest = PaneFocusRequest()
) {
    val focusPane = focusRequest.pane
    val leaves = PaneLayout.leaves(tab.root)
    var focused by remember(tab.id) { mutableStateOf(leaves.firstOrNull()?.id) }
    if (leaves.none { it.id == focused }) focused = leaves.lastOrNull()?.id
    // Plain maps: they are filled while composing and never drive recomposition.
    val terminals = remember(tab.id, host) { mutableMapOf<String, PaneTerminal>() }
    val requesters = remember(tab.id) { mutableMapOf<String, FocusRequester>() }

    // Terminals of panes no longer in the tree are detached; leaving the tab detaches all of them.
    val addresses = leaves.mapNotNull { it.terminal }.toSet()
    LaunchedEffect(addresses) {
        (terminals.keys - addresses).forEach { address ->
            terminals.remove(address)?.let { t -> host?.scope?.launch { t.close() } }
        }
    }
    DisposableEffect(tab.id, host) {
        onDispose {
            terminals.values.forEach { t -> host?.scope?.launch { t.close() } }
            terminals.clear()
        }
    }
    // A pane that appears is the one just split off: it takes the focus.
    val ids = leaves.map { it.id }.toSet()
    var known by remember(tab.id) { mutableStateOf(ids) }
    LaunchedEffect(ids) {
        (ids - known).firstOrNull()?.let { focused = it }
        known = ids
    }
    // A pane chosen in the agents sidebar takes the focus.
    LaunchedEffect(focusPane) { focusPane?.takeIf { id -> leaves.any { it.id == id } }?.let { focused = it } }
    // Also when a peek over the tab closes: the keyboard goes back to the focused pane.
    LaunchedEffect(focused, focusRequest.active) {
        if (focusRequest.active) focused?.let { requesters[it] }?.let { runCatching { it.requestFocus() } }
    }

    val focus = PaneFocusState(focused, requesters) { focused = it }
    val scope = PaneScope(TabRef(spaceId, tab, state), controls, host, terminals, focus)
    Box(Modifier.fillMaxSize()) { scope.Node(tab.root, Modifier.fillMaxSize()) }
}

/**
 * The pane chosen in the agents sidebar ([pane], none when null) and whether the tab may take the keyboard
 * ([active] is false while a peek is open over it).
 */
internal class PaneFocusRequest(val pane: String? = null, val active: Boolean = true)

/** The message shown over a terminal whose program ended or that could not be attached; null while live. */
@Composable
internal fun terminalMessage(status: PaneTerminalState): String? = when (status) {
    PaneTerminalState.Attaching, PaneTerminalState.Live -> null

    is PaneTerminalState.Ended -> status.exitCode?.let { stringResource(R.string.pane_ended_code, it) }
        ?: stringResource(R.string.pane_ended, status.reason.orEmpty())

    is PaneTerminalState.Failed -> stringResource(R.string.pane_failed, status.reason)
}

/** The tab being drawn, with the core state its panes show. */
private class TabRef(val spaceId: String, val tab: Tab, val state: WorkspaceState.Ready)

/** The focused pane, the focus requester of every pane and how a pane takes the focus. */
private class PaneFocusState(
    val focused: String?,
    val requesters: MutableMap<String, FocusRequester>,
    val onFocus: (String) -> Unit
)

/** What every node of a tab's tree needs while it is drawn. */
private class PaneScope(
    ref: TabRef,
    val controls: Controls,
    val host: PaneHost?,
    val terminals: MutableMap<String, PaneTerminal>,
    focus: PaneFocusState
) {
    val spaceId = ref.spaceId
    val tab = ref.tab
    val state = ref.state
    val focused = focus.focused
    val requesters = focus.requesters
    val onFocus = focus.onFocus

    @Composable
    fun Node(node: PaneNode, modifier: Modifier) {
        when (node) {
            is PaneNode.Leaf -> Leaf(node, modifier)
            is PaneNode.Split -> Split(node, modifier)
        }
    }

    @Composable
    private fun Split(split: PaneNode.Split, modifier: Modifier) {
        if (split.children.isEmpty()) return
        val stored = split.children.map(PaneLayout::weightOf)
        // While a divider is dragged the weights are local; they are written to the core when the drag ends.
        var weights by remember(split.id, stored) { mutableStateOf(stored) }
        var lengthPx by remember { mutableStateOf(1) }
        val horizontal = split.axis == Axis.ROW
        val sizeModifier = modifier.onSizeChanged {
            lengthPx =
                (if (horizontal) it.width else it.height).coerceAtLeast(1)
        }
        val content: @Composable (Int, PaneNode, Modifier) -> Unit = { index, child, childModifier ->
            if (index > 0) {
                Divider(horizontal, Modifier.testTag("divider-${split.id}-${index - 1}"), { deltaPx ->
                    weights = PaneLayout.dragDivider(weights, index - 1, deltaPx.toDouble() / lengthPx)
                }) { controls.actions.resizeSplit(spaceId, tab.id, split.id, weights) }
            }
            Node(child, childModifier)
        }
        if (horizontal) {
            Row(sizeModifier) {
                split.children.forEachIndexed { i, c -> content(i, c, weighted(weights[i]).fillMaxHeight()) }
            }
        } else {
            Column(sizeModifier) {
                split.children.forEachIndexed { i, c -> content(i, c, weighted(weights[i]).fillMaxWidth()) }
            }
        }
    }

    @Composable
    private fun RowScope.weighted(weight: Double) = Modifier.weight(weight.toFloat().coerceAtLeast(MIN_WEIGHT))

    @Composable
    private fun ColumnScope.weighted(weight: Double) = Modifier.weight(weight.toFloat().coerceAtLeast(MIN_WEIGHT))

    @Composable
    private fun Divider(horizontal: Boolean, modifier: Modifier, onDrag: (Float) -> Unit, onDragEnd: () -> Unit) {
        val drag by rememberUpdatedState(onDrag)
        val end by rememberUpdatedState(onDragEnd)
        val draggable = rememberDraggableState { drag(it) }
        Box(
            modifier
                .then(
                    if (horizontal) Modifier.width(DIVIDER).fillMaxHeight() else Modifier.height(DIVIDER).fillMaxWidth()
                )
                .background(MaterialTheme.colorScheme.outlineVariant)
                .draggable(
                    draggable,
                    if (horizontal) Orientation.Horizontal else Orientation.Vertical,
                    onDragStopped = { end() }
                )
        )
    }

    @Composable
    private fun Leaf(leaf: PaneNode.Leaf, modifier: Modifier) {
        val isFocused = leaf.id == focused
        val colors = MaterialTheme.colorScheme
        val border = if (isFocused) colors.primary else colors.outlineVariant
        val address = leaf.terminal
        Column(modifier.border(1.dp, border).testTag("pane-${leaf.id}")) {
            PaneHeader(leaf, address)
            val terminal = address?.let { a ->
                host?.let { h -> terminals.getOrPut(a) { PaneTerminal(h.rpc, a, h.scope) } }
            }
            if (terminal == null) {
                Box(Modifier.weight(1f).fillMaxWidth())
            } else {
                PaneBody(leaf, terminal, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }

    @Composable
    private fun PaneHeader(leaf: PaneNode.Leaf, address: String?) {
        val info = address?.let { state.terminals[it] }
        val title = when {
            address == null -> stringResource(R.string.pane_empty)
            info == null -> stringResource(R.string.workspace_terminal_gone, address)
            else -> stringResource(R.string.workspace_terminal, address, info.cwd)
        }
        val folder = address?.let(state::cwdOf)?.takeIf { it.isNotBlank() }
        val actions = controls.actions
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = { actions.splitPane(spaceId, tab.id, leaf.id, Axis.ROW, folder) },
                modifier = Modifier.testTag("split-right-${leaf.id}")
            ) { Text(stringResource(R.string.pane_split_right)) }
            TextButton(
                onClick = { actions.splitPane(spaceId, tab.id, leaf.id, Axis.COLUMN, folder) },
                modifier = Modifier.testTag("split-down-${leaf.id}")
            ) { Text(stringResource(R.string.pane_split_down)) }
            TextButton(
                onClick = { controls.prompt(Prompt.ClosePane(spaceId, tab.id, leaf.id, address ?: leaf.id)) },
                modifier = Modifier.testTag("close-${leaf.id}")
            ) { Text(stringResource(R.string.pane_close)) }
        }
    }

    @Composable
    private fun PaneBody(leaf: PaneNode.Leaf, terminal: PaneTerminal, modifier: Modifier) {
        val status by terminal.state.collectAsState()
        val requester = requesters.getOrPut(leaf.id) { FocusRequester() }
        Box(modifier) {
            TerminalView(
                terminal.session,
                Modifier.fillMaxSize().testTag("terminal-${leaf.id}"),
                focusRequester = requester,
                onFocusChanged = { gained ->
                    if (gained) onFocus(leaf.id)
                    terminal.focus(gained)
                },
                frameObserver = host?.frameObserver
            )
            terminalMessage(status)?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.align(Alignment.BottomStart)
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(8.dp)
                )
            }
        }
    }

    private companion object {
        const val MIN_WEIGHT = 0.01f
    }
}
