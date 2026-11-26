package io.github.totocastaldi.herdrdesktop.coreclient.workspace

/*
 * Panes of a tab (REQ-WS-002): splitting starts a shell, closing ends the pane's terminal, resizing only
 * writes the weights. Every change goes through the core document, as for spaces and tabs.
 */

/**
 * Splits pane [paneId] along [axis] with a new pane running the core's shell in [folder]; the new
 * terminal is ended if the document write fails or the pane no longer exists. The terminal carries the
 * labels of the space and the tab, so that the desktop app knows where it belongs.
 */
suspend fun WorkspaceClient.splitPane(
    spaceId: String,
    tabId: String,
    paneId: String,
    axis: Axis,
    folder: String?
): WorkspaceSnapshot {
    val terminal = terminals.runShell(folder, spaceId, tabId)
    return endOnFailure(terminal) {
        val leaf = PaneNode.Leaf(ids.next(WorkspaceClient.PANE_PREFIX), terminal.address)
        val splitId = ids.next(WorkspaceClient.PANE_PREFIX)
        document.update { ws ->
            val tab = ws.space(spaceId)?.tab(tabId)
            checkNotNull(tab?.let { PaneLayout.leaf(it.root, paneId) }) { "Pane $paneId no longer exists" }
            // Already placed by the desktop app, which adopts labelled terminals without a pane.
            if (ws.shows(terminal.address)) return@update ws
            ws.changePanes(spaceId, tabId) { PaneLayout.split(it, paneId, axis, leaf, splitId) }
        }
    }
}

/**
 * Removes pane [paneId] from the document, then ends its terminal on the core. Closing the last pane of
 * a tab closes the tab.
 */
suspend fun WorkspaceClient.closePane(spaceId: String, tabId: String, paneId: String): WorkspaceSnapshot {
    var ended: String? = null
    val snapshot = document.update { ws ->
        val root = ws.space(spaceId)?.tab(tabId)?.root
        val leaf = root?.let { PaneLayout.leaf(it, paneId) }
        ended = leaf?.terminal
        when {
            root == null || leaf == null -> ws
            PaneLayout.leaves(root).size == 1 -> WorkspaceEdits.removeTab(ws, spaceId, tabId)
            else -> ws.changePanes(spaceId, tabId) { PaneLayout.close(it, paneId) }
        }
    }
    ended?.let { terminals.end(it) }
    return snapshot
}

/** Sets the weights of the children of split [splitId], after the user dragged one of its dividers. */
suspend fun WorkspaceClient.resizeSplit(spaceId: String, tabId: String, splitId: String, weights: List<Double>) =
    document.update { ws -> ws.changePanes(spaceId, tabId) { PaneLayout.setWeights(it, splitId, weights) } }

/** Replaces the pane tree of the tab with [change] of it. */
private fun Workspace.changePanes(spaceId: String, tabId: String, change: (PaneNode) -> PaneNode): Workspace = copy(
    spaces = spaces.map { s ->
        if (s.id !=
            spaceId
        ) {
            s
        } else {
            s.copy(tabs = s.tabs.map { if (it.id == tabId) it.copy(root = change(it.root)) else it })
        }
    }
)
