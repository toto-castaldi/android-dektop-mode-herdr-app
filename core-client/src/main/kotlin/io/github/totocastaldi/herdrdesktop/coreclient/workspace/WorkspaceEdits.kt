package io.github.totocastaldi.herdrdesktop.coreclient.workspace

/**
 * The edits the app makes to the workspace document. An edit of an id that no longer exists (changed
 * meanwhile by another client) leaves the workspace unchanged.
 */
object WorkspaceEdits {
    fun addSpace(ws: Workspace, space: Space): Workspace = ws.copy(spaces = ws.spaces + space)

    fun renameSpace(ws: Workspace, spaceId: String, name: String): Workspace =
        ws.mapSpace(spaceId) { it.copy(name = WorkspaceNames.normalize(name)) }

    /** Moves the space to [toIndex], clamped to the list. */
    fun moveSpace(ws: Workspace, spaceId: String, toIndex: Int): Workspace =
        ws.copy(spaces = ws.spaces.moved({ it.id == spaceId }, toIndex))

    fun removeSpace(ws: Workspace, spaceId: String): Workspace =
        ws.copy(spaces = ws.spaces.filterNot { it.id == spaceId })

    fun addTab(ws: Workspace, spaceId: String, tab: Tab): Workspace =
        ws.mapSpace(spaceId) { it.copy(tabs = it.tabs + tab) }

    fun renameTab(ws: Workspace, spaceId: String, tabId: String, name: String): Workspace = ws.mapSpace(spaceId) { s ->
        s.copy(tabs = s.tabs.map { if (it.id == tabId) it.copy(name = WorkspaceNames.normalize(name)) else it })
    }

    /** Moves the tab to [toIndex] inside its space, clamped to the list. */
    fun moveTab(ws: Workspace, spaceId: String, tabId: String, toIndex: Int): Workspace =
        ws.mapSpace(spaceId) { s -> s.copy(tabs = s.tabs.moved({ it.id == tabId }, toIndex)) }

    fun removeTab(ws: Workspace, spaceId: String, tabId: String): Workspace =
        ws.mapSpace(spaceId) { s -> s.copy(tabs = s.tabs.filterNot { it.id == tabId }) }

    /** A tab with a single pane showing [terminal], in the desktop app's shape. */
    fun singlePaneTab(tabId: String, paneId: String, terminal: String): Tab =
        Tab(tabId, root = PaneNode.Split(tabId, Axis.ROW, listOf(PaneNode.Leaf(paneId, terminal, weight = 1.0))))

    private fun Workspace.mapSpace(spaceId: String, change: (Space) -> Space) =
        copy(spaces = spaces.map { if (it.id == spaceId) change(it) else it })
}

private fun <T> List<T>.moved(match: (T) -> Boolean, toIndex: Int): List<T> {
    val from = indexOfFirst(match)
    if (from < 0) return this
    val rest = toMutableList()
    val item = rest.removeAt(from)
    rest.add(toIndex.coerceIn(0, rest.size), item)
    return rest
}
