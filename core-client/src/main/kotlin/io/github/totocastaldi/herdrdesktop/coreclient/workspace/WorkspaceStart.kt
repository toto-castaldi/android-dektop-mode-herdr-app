package io.github.totocastaldi.herdrdesktop.coreclient.workspace

/*
 * Spaces and tabs created with a name and a program, as the agents' commands ask them (REQ-AGT-003); the
 * user's commands go through `WorkspaceClient.createSpace` and `createTab`, which use these.
 */

/** What a new terminal runs: [argv] (the core's shell when empty) in [folder] (the core's default when null). */
data class NewTerminal(val folder: String? = null, val argv: List<String> = emptyList())

/** The document after creating something, and the address of the terminal just started for it. */
data class Created(val snapshot: WorkspaceSnapshot, val terminal: String) {
    /** Where the new terminal is shown, null if no pane shows it. */
    val location: PaneLocation? get() = snapshot.workspace.paneOf(terminal)
}

/** New space named [name] (blank: unnamed) whose only tab runs [start]; see [WorkspaceClient.createSpace]. */
suspend fun WorkspaceClient.startSpace(start: NewTerminal, name: String = ""): Created {
    val spaceId = ids.next(WorkspaceClient.SPACE_PREFIX)
    val tabId = ids.next(WorkspaceClient.TAB_PREFIX)
    val terminal = terminals.runShell(start.folder, spaceId, tabId, start.argv)
    val snapshot = endOnFailure(terminal) {
        val space = Space(spaceId, WorkspaceNames.normalize(name), tabs = listOf(newTab(tabId, terminal.address)))
        document.update { if (it.shows(terminal.address)) it else WorkspaceEdits.addSpace(it, space) }
    }
    return Created(snapshot, terminal.address)
}

/** New tab named [name] (blank: unnamed) at the end of [spaceId], running [start]; see [WorkspaceClient.createTab]. */
suspend fun WorkspaceClient.startTab(spaceId: String, start: NewTerminal, name: String = ""): Created {
    val tabId = ids.next(WorkspaceClient.TAB_PREFIX)
    val terminal = terminals.runShell(start.folder, spaceId, tabId, start.argv)
    val snapshot = endOnFailure(terminal) {
        val tab = newTab(tabId, terminal.address).copy(name = WorkspaceNames.normalize(name))
        document.update { ws ->
            check(ws.space(spaceId) != null) { "Space $spaceId no longer exists" }
            if (ws.shows(terminal.address)) ws else WorkspaceEdits.addTab(ws, spaceId, tab)
        }
    }
    return Created(snapshot, terminal.address)
}
