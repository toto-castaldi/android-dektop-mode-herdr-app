package io.github.totocastaldi.herdrdesktop.coreclient.workspace

/*
 * Terminals started by agents in the app's spaces and tabs (REQ-WS-003): the app's space and tab labels of
 * the live terminals decide which pane shows them. Nothing is started or ended here.
 */

/** A terminal adopted into tab [tabId] of space [spaceId]; its labels are written after the document. */
data class Adoption(val address: String, val spaceId: String, val tabId: String)

/** The document after [WorkspaceAdoption.reconcile] and the terminals it adopted. */
data class Reconciled(val workspace: Workspace, val adopted: List<Adoption>)

object WorkspaceAdoption {
    /**
     * Applies the labels of the [live] terminals to [ws]:
     * - a live terminal with `herdr-desktop-android.space` of a space of the document and no pane is added
     *   as a new pane of the tab named by `herdr-desktop-android.tab` (next to its last pane); without that
     *   label, or with a tab the space does not have, it gets a new tab at the end of the space, whose id is
     *   the one of the label when the document does not use it yet. A space the document does not have, and
     *   the desktop app's `herdr.space`/`herdr.tab`, are ignored;
     * - a pane whose terminal is live but has no `herdr-desktop-android.space` any more is removed from the
     *   document (closing its tab when it was the last pane); a terminal that is not live keeps its pane.
     */
    fun reconcile(ws: Workspace, live: Map<String, TerminalInfo>, ids: IdGenerator): Reconciled {
        var current = removeUnlabelled(ws, live)
        val adopted = mutableListOf<Adoption>()
        live.values.forEach { terminal ->
            val spaceId = terminal.labels[CoreTerminals.SPACE_LABEL]?.takeIf { it.isNotEmpty() } ?: return@forEach
            val space = current.space(spaceId) ?: return@forEach
            if (current.shows(terminal.address)) return@forEach
            val wanted = terminal.labels[CoreTerminals.TAB_LABEL]?.takeIf { it.isNotEmpty() }
            val tab = wanted?.let { space.tab(it) }
            current = if (tab != null) {
                adopted += Adoption(terminal.address, spaceId, tab.id)
                addPane(
                    current,
                    spaceId,
                    tab,
                    PaneNode.Leaf(ids.next(WorkspaceClient.PANE_PREFIX), terminal.address),
                    ids
                )
            } else {
                val tabId = wanted?.takeUnless { current.usesTabId(it) } ?: ids.next(WorkspaceClient.TAB_PREFIX)
                adopted += Adoption(terminal.address, spaceId, tabId)
                val newTab = WorkspaceEdits.singlePaneTab(
                    tabId,
                    ids.next(WorkspaceClient.PANE_PREFIX),
                    terminal.address
                )
                WorkspaceEdits.addTab(current, spaceId, newTab)
            }
        }
        return Reconciled(current, adopted)
    }

    private fun removeUnlabelled(ws: Workspace, live: Map<String, TerminalInfo>): Workspace {
        var current = ws
        ws.spaces.forEach { space ->
            space.tabs.forEach { tab ->
                PaneLayout.leaves(tab.root).filter { leaf ->
                    val info = leaf.terminal?.let { live[it] }
                    info != null && info.labels[CoreTerminals.SPACE_LABEL].isNullOrEmpty()
                }.forEach { leaf -> current = removePane(current, space.id, tab.id, leaf.id) }
            }
        }
        return current
    }

    private fun removePane(ws: Workspace, spaceId: String, tabId: String, paneId: String): Workspace {
        val root = ws.space(spaceId)?.tab(tabId)?.root ?: return ws
        return if (PaneLayout.leaves(root).size <= 1) {
            WorkspaceEdits.removeTab(ws, spaceId, tabId)
        } else {
            ws.withRoot(spaceId, tabId, PaneLayout.close(root, paneId))
        }
    }

    /** Puts [leaf] after the last pane of [tab], along the axis of its root. */
    private fun addPane(ws: Workspace, spaceId: String, tab: Tab, leaf: PaneNode.Leaf, ids: IdGenerator): Workspace {
        val root = tab.root
        val last = PaneLayout.leaves(root).lastOrNull()
        val changed = when {
            last != null -> {
                val axis = (root as? PaneNode.Split)?.axis ?: Axis.ROW
                PaneLayout.split(root, last.id, axis, leaf, ids.next(WorkspaceClient.PANE_PREFIX))
            }

            root is PaneNode.Split -> root.copy(children = listOf(leaf.copy(weight = 1.0)))

            else -> root
        }
        return ws.withRoot(spaceId, tab.id, changed)
    }

    private fun Workspace.usesTabId(tabId: String) = spaces.any { s -> s.tab(tabId) != null }

    private fun Workspace.withRoot(spaceId: String, tabId: String, root: PaneNode) = copy(
        spaces = spaces.map { s ->
            if (s.id != spaceId) s else s.copy(tabs = s.tabs.map { if (it.id == tabId) it.copy(root = root) else it })
        }
    )
}

/**
 * Brings the document in line with the labels of the [live] terminals ([WorkspaceAdoption.reconcile]), starting
 * from [current]: written with compare-and-set only when something changes, then every adopted terminal gets
 * the app's labels of the space and tab that show it, without the desktop app's ones (as "keep as tab" does).
 *
 * @return the document and whether terminal labels were written.
 */
suspend fun WorkspaceClient.adopt(
    current: WorkspaceSnapshot,
    live: Map<String, TerminalInfo>
): Pair<WorkspaceSnapshot, Boolean> {
    if (WorkspaceAdoption.reconcile(current.workspace, live, ProbeIds).workspace == current.workspace) {
        return current to false
    }
    var adopted = emptyList<Adoption>()
    val snapshot = document.update { ws ->
        WorkspaceAdoption.reconcile(ws, live, ids).also { adopted = it.adopted }.workspace
    }
    var labelled = false
    adopted.forEach { (address, spaceId, tabId) ->
        val shown = snapshot.workspace.paneOf(address)?.let { it.spaceId == spaceId && it.tabId == tabId } == true
        if (shown && !CoreTerminals.placedIn(live[address]?.labels.orEmpty(), spaceId, tabId)) {
            terminals.place(address, spaceId, tabId)
            labelled = true
        }
    }
    return snapshot to labelled
}

/** Ids for a dry run, so that checking whether anything changes uses none of the real ones. */
private object ProbeIds : IdGenerator {
    override fun next(prefix: String) = prefix
}
