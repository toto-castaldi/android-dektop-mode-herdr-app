package io.github.totocastaldi.herdrdesktop.coreclient.workspace

import io.github.totocastaldi.herdrdesktop.coreclient.protocol.RpcException

/**
 * Moves the spaces the app saved in its own document ([WorkspaceCodec.LEGACY_DOC_NAME]) before sharing the
 * desktop app's one (REQ-WS-001). Spaces whose id the shared document already has are skipped, so that a
 * second run (two devices, or a run interrupted before the end) adds nothing twice; their terminals are
 * labelled with the ids of their space and tab, as the desktop app labels its own; then the legacy value
 * is deleted, so that it is never read or written again. No terminal is started or ended.
 *
 * @return true when there was something to move.
 */
suspend fun WorkspaceClient.migrateLegacy(): Boolean {
    val legacy = CoreDocument(rpc, WorkspaceCodec.LEGACY_DOC_NAME)
    val (snapshot, present) = legacy.read()
    if (!present) return false
    val moved = snapshot.workspace.spaces
    if (moved.isNotEmpty()) {
        document.update { ws -> ws.copy(spaces = ws.spaces + moved.filter { ws.space(it.id) == null }) }
        val placed = moved.flatMap { space ->
            space.tabs.flatMap { tab -> tab.terminals.map { Triple(it, space.id, tab.id) } }
        }
        placed.forEach { (terminal, spaceId, tabId) -> terminals.place(terminal, spaceId, tabId) }
    }
    try {
        legacy.delete(snapshot.version)
    } catch (e: RpcException) {
        // Written meanwhile (an older app on another device): the next session moves it again.
        if (e.error.kind != CoreDocument.CONFLICT) throw e
    }
    return moved.isNotEmpty()
}
