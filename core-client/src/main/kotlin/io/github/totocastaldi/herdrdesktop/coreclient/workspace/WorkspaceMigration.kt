package io.github.totocastaldi.herdrdesktop.coreclient.workspace

import io.github.totocastaldi.herdrdesktop.coreclient.protocol.RpcException

/*
 * Moves into the app's document ([WorkspaceCodec.DOC_NAME]) the spaces the app left elsewhere (REQ-WS-001):
 * those of its old document ([WorkspaceCodec.LEGACY_DOC_NAME]) and those it wrote in the desktop app's one
 * ([WorkspaceCodec.DESKTOP_DOC_NAME]) while sharing it. No terminal is started or ended.
 */

/**
 * Runs every move, then labels the terminals of the app's spaces with the app's space and tab labels,
 * without the desktop app's ones (so that a run interrupted before the labels is completed by the next).
 *
 * @return true when a space was moved.
 */
suspend fun WorkspaceClient.migrate(): Boolean {
    val legacy = migrateLegacy()
    val desktop = migrateDesktop()
    relabel()
    return legacy || desktop
}

/**
 * Moves the spaces of the app's old document: those whose id the app's document already has are skipped,
 * then the old value is deleted, so that it is never read or written again.
 *
 * @return true when there was something to move.
 */
internal suspend fun WorkspaceClient.migrateLegacy(): Boolean {
    val legacy = CoreDocument(rpc, WorkspaceCodec.LEGACY_DOC_NAME)
    val (snapshot, present) = legacy.read()
    if (!present) return false
    val moved = snapshot.workspace.spaces
    if (moved.isNotEmpty()) {
        document.update { ws -> ws.copy(spaces = ws.spaces + moved.filter { ws.space(it.id) == null }) }
    }
    try {
        legacy.delete(snapshot.version)
    } catch (e: RpcException) {
        // Written meanwhile (an older app on another device): the next session moves it again.
        if (e.error.kind != CoreDocument.CONFLICT) throw e
    }
    return moved.isNotEmpty()
}

/**
 * Moves out of the desktop app's document the spaces whose terminals the app started all (`app=herdr-android`,
 * at least one): they are added to the app's document when their id is missing, then removed from the
 * desktop app's one with compare-and-set on the version read. Spaces with a terminal of the desktop app, or
 * none, stay where they are. On a conflict the spaces just added are taken out again, so that both documents
 * are as they were for the next session.
 *
 * @return true when spaces were moved.
 */
internal suspend fun WorkspaceClient.migrateDesktop(): Boolean {
    val desktop = CoreDocument(rpc, WorkspaceCodec.DESKTOP_DOC_NAME)
    val (snapshot, present) = desktop.read()
    val known = if (present) terminals.list(all = true) else emptyMap()
    val own = snapshot.workspace.spaces.filter { space -> space.startedByApp(known) }
    return own.isNotEmpty() && moveOut(desktop, snapshot, own)
}

/** Adds [own] to the app's document, then removes them from [snapshot] of [desktop] (see [migrateDesktop]). */
private suspend fun WorkspaceClient.moveOut(
    desktop: CoreDocument,
    snapshot: WorkspaceSnapshot,
    own: List<Space>
): Boolean {
    val ids = own.map { it.id }.toSet()
    var added = emptySet<String>()
    document.update { ws ->
        val missing = own.filter { ws.space(it.id) == null }
        added = missing.map { it.id }.toSet()
        ws.copy(spaces = ws.spaces + missing)
    }
    return try {
        desktop.write(snapshot) { ws -> ws.copy(spaces = ws.spaces.filterNot { it.id in ids }) }
        true
    } catch (e: RpcException) {
        // The desktop app wrote meanwhile, or the core refused: undo, the next session tries again.
        if (added.isNotEmpty()) document.update { ws -> ws.copy(spaces = ws.spaces.filterNot { it.id in added }) }
        if (e.error.kind != CoreDocument.CONFLICT) throw e
        false
    }
}

/** True when the space shows at least one terminal and the app started all of them. */
internal fun Space.startedByApp(known: Map<String, TerminalInfo>): Boolean {
    val shown = tabs.flatMap { it.terminals }
    return shown.isNotEmpty() &&
        shown.all { known[it]?.labels?.get(CoreTerminals.APP_LABEL) == CoreTerminals.APP_LABEL_VALUE }
}

/** Gives every terminal of the app's spaces the app's labels of its space and tab, without the desktop app's. */
private suspend fun WorkspaceClient.relabel() {
    val ws = document.load().workspace
    val placed = ws.spaces.flatMap { space ->
        space.tabs.flatMap { tab -> tab.terminals.map { Triple(it, space.id, tab.id) } }
    }
    if (placed.isEmpty()) return
    val known = terminals.list(all = true)
    placed.forEach { (terminal, spaceId, tabId) ->
        val labels = known[terminal]?.labels ?: return@forEach
        if (!CoreTerminals.placedIn(labels, spaceId, tabId)) terminals.place(terminal, spaceId, tabId)
    }
}
