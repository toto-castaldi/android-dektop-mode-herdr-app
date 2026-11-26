package io.github.totocastaldi.herdrdesktop.coreclient.workspace

import io.github.totocastaldi.herdrdesktop.coreclient.connection.CoreRpc
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** New ids in the style of the desktop app (`sp…`, `tb…`, `p…`). */
fun interface IdGenerator {
    fun next(prefix: String): String

    companion object {
        val Random = IdGenerator { prefix -> prefix + UUID.randomUUID().toString().replace("-", "") }
    }
}

/**
 * Spaces and tabs of one core (REQ-WS-001): changes [document] and starts and ends the terminals of the
 * tabs. Closing a tab or a space ends its terminals, as closing a pane does (`WorkspacePanes.kt`); nothing else does.
 */
class WorkspaceClient(rpc: CoreRpc, internal val ids: IdGenerator = IdGenerator.Random) {
    val document = CoreDocument(rpc)
    val terminals = CoreTerminals(rpc)

    /** New space with one tab running the core's shell; its terminal is ended if the document write fails. */
    suspend fun createSpace(folder: String? = null): WorkspaceSnapshot {
        val terminal = terminals.runShell(folder)
        return endOnFailure(terminal) {
            val space = Space(ids.next(SPACE_PREFIX), tabs = listOf(newTab(terminal.address)))
            document.update { WorkspaceEdits.addSpace(it, space) }
        }
    }

    /** New tab at the end of [spaceId], running the core's shell in [folder] (see [WorkspaceNames.newTabFolder]). */
    suspend fun createTab(spaceId: String, folder: String?): WorkspaceSnapshot {
        val terminal = terminals.runShell(folder)
        return endOnFailure(terminal) {
            val tab = newTab(terminal.address)
            document.update { ws ->
                check(ws.space(spaceId) != null) { "Space $spaceId no longer exists" }
                WorkspaceEdits.addTab(ws, spaceId, tab)
            }
        }
    }

    suspend fun renameSpace(spaceId: String, name: String) =
        document.update { WorkspaceEdits.renameSpace(it, spaceId, name) }

    suspend fun moveSpace(spaceId: String, toIndex: Int) =
        document.update { WorkspaceEdits.moveSpace(it, spaceId, toIndex) }

    suspend fun renameTab(spaceId: String, tabId: String, name: String) =
        document.update { WorkspaceEdits.renameTab(it, spaceId, tabId, name) }

    suspend fun moveTab(spaceId: String, tabId: String, toIndex: Int) =
        document.update { WorkspaceEdits.moveTab(it, spaceId, tabId, toIndex) }

    /** Removes the tab from the document, then ends its terminals on the core. */
    suspend fun closeTab(spaceId: String, tabId: String): WorkspaceSnapshot {
        var ended = emptyList<String>()
        val snapshot = document.update { ws ->
            ended = ws.space(spaceId)?.tab(tabId)?.terminals.orEmpty()
            WorkspaceEdits.removeTab(ws, spaceId, tabId)
        }
        ended.forEach { terminals.end(it) }
        return snapshot
    }

    /** Removes the space and all its tabs from the document, then ends their terminals on the core. */
    suspend fun closeSpace(spaceId: String): WorkspaceSnapshot {
        var ended = emptyList<String>()
        val snapshot = document.update { ws ->
            ended = ws.space(spaceId)?.tabs.orEmpty().flatMap { it.terminals }
            WorkspaceEdits.removeSpace(ws, spaceId)
        }
        ended.forEach { terminals.end(it) }
        return snapshot
    }

    private fun newTab(terminal: String): Tab =
        WorkspaceEdits.singlePaneTab(ids.next(TAB_PREFIX), ids.next(PANE_PREFIX), terminal)

    /** Runs [block]; if it fails, the just started [terminal] is ended so that no terminal is left without a tab. */
    internal suspend fun <T> endOnFailure(terminal: TerminalInfo, block: suspend () -> T): T = try {
        block()
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception
    ) {
        withContext(NonCancellable) { runCatching { terminals.end(terminal.address) } }
        throw e
    }

    internal companion object {
        const val SPACE_PREFIX = "sp"
        const val TAB_PREFIX = "tb"
        const val PANE_PREFIX = "p"
    }
}
