package io.github.totocastaldi.herdrdesktop.coreclient.workspace

import io.github.totocastaldi.herdrdesktop.coreclient.connection.CoreRpc
import io.github.totocastaldi.herdrdesktop.coreclient.protocol.RpcException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** The workspace document as read from the core, with the version to write it back. */
data class WorkspaceSnapshot(val workspace: Workspace, val version: Long)

/**
 * A workspace document on a core, by default the app's own ([WorkspaceCodec.DOC_NAME]), written with
 * compare-and-set.
 */
class CoreDocument(private val rpc: CoreRpc, private val name: String = WorkspaceCodec.DOC_NAME) {
    suspend fun load(): WorkspaceSnapshot = read().first

    /** The document and whether it has a value (a document never written, or deleted, has none). */
    internal suspend fun read(): Pair<WorkspaceSnapshot, Boolean> {
        val result = rpc.call("doc.get", buildJsonObject { put("name", name) }) as JsonObject
        val version = (result["version"] as? JsonPrimitive)?.longOrNull ?: 0L
        val value = result["value"]
        return WorkspaceSnapshot(WorkspaceCodec.decode(value), version) to (value != null && value !is JsonNull)
    }

    /** Deletes the value if nobody wrote since [version]; the core keeps the version. */
    internal suspend fun delete(version: Long) {
        val params = buildJsonObject {
            put("name", name)
            put("value", JsonNull)
            put("version", version)
        }
        rpc.call("doc.set", params)
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

    /** Writes [change] of [current] once, only if nobody wrote since it was read: a `conflict` is thrown. */
    internal suspend fun write(current: WorkspaceSnapshot, change: (Workspace) -> Workspace): WorkspaceSnapshot {
        val changed = change(current.workspace)
        if (changed == current.workspace) return current
        val params = buildJsonObject {
            put("name", name)
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
