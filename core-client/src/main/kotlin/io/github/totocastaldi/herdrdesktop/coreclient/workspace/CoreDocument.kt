package io.github.totocastaldi.herdrdesktop.coreclient.workspace

import io.github.totocastaldi.herdrdesktop.coreclient.connection.CoreRpc
import io.github.totocastaldi.herdrdesktop.coreclient.protocol.RpcException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** The workspace document as read from the core, with the version to write it back. */
data class WorkspaceSnapshot(val workspace: Workspace, val version: Long)

/** The app's workspace document on a core ([WorkspaceCodec.DOC_NAME]), written with compare-and-set. */
class CoreDocument(private val rpc: CoreRpc) {
    suspend fun load(): WorkspaceSnapshot {
        val result = rpc.call("doc.get", buildJsonObject { put("name", WorkspaceCodec.DOC_NAME) }) as JsonObject
        val version = (result["version"] as? JsonPrimitive)?.longOrNull ?: 0L
        return WorkspaceSnapshot(WorkspaceCodec.decode(result["value"]), version)
    }

    /**
     * Applies [change] to the current document and writes it if nobody wrote in between; on a `conflict`
     * it reads the document again and reapplies [change], up to [MAX_ATTEMPTS] times. An unchanged
     * document is not written.
     */
    suspend fun update(change: (Workspace) -> Workspace): WorkspaceSnapshot {
        repeat(MAX_ATTEMPTS - 1) {
            try {
                return write(load(), change)
            } catch (e: RpcException) {
                if (e.error.kind != CONFLICT) throw e
            }
        }
        return write(load(), change)
    }

    private suspend fun write(current: WorkspaceSnapshot, change: (Workspace) -> Workspace): WorkspaceSnapshot {
        val changed = change(current.workspace)
        if (changed == current.workspace) return current
        val params = buildJsonObject {
            put("name", WorkspaceCodec.DOC_NAME)
            put("value", WorkspaceCodec.encode(changed))
            put("version", current.version)
        }
        val result = rpc.call("doc.set", params) as JsonObject
        val version = (result["version"] as? JsonPrimitive)?.longOrNull ?: (current.version + 1)
        return WorkspaceSnapshot(changed, version)
    }

    companion object {
        const val MAX_ATTEMPTS = 5
        const val CONFLICT = "conflict"
    }
}
