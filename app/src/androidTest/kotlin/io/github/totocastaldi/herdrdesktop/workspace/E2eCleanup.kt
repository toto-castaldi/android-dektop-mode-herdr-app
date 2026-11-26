package io.github.totocastaldi.herdrdesktop.workspace

import io.github.totocastaldi.herdrdesktop.coreclient.connection.CoreRpc
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.WorkspaceClient
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.WorkspaceCodec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * How the end-to-end tests of panes leave a real core as they found it: they run only on a core without
 * spaces of the app, close only the spaces they created (which ends their terminals) and remove the app
 * document only if there was none before. Nothing else of the core is changed.
 */
internal object E2eCleanup {
    /**
     * Fails unless the app document of the core has no spaces; returns true if there is no document at all.
     * Opening the workspace screen would otherwise attach to, and resize, terminals the test does not own.
     */
    fun requireNoSpaces(rpc: CoreRpc): Boolean = runBlocking {
        val result = rpc.call("doc.get", buildJsonObject { put("name", WorkspaceCodec.DOC_NAME) }) as JsonObject
        val spaces = WorkspaceCodec.decode(result["value"]).spaces
        check(spaces.isEmpty()) {
            "the core already has ${spaces.size} spaces of the app: the test does not touch them"
        }
        result["value"] == null || result["value"] == JsonNull
    }

    /** Closes [own] spaces; if the core had no document before and none of its spaces is left, removes it. */
    suspend fun closeOwn(rpc: CoreRpc, own: Set<String>, startedWithoutDocument: Boolean) {
        val client = WorkspaceClient(rpc)
        own.forEach { client.closeSpace(it) }
        val snapshot = client.document.load()
        if (startedWithoutDocument && snapshot.workspace.spaces.isEmpty()) {
            rpc.call(
                "doc.set",
                buildJsonObject {
                    put("name", WorkspaceCodec.DOC_NAME)
                    put("value", JsonNull)
                    put("version", JsonPrimitive(snapshot.version))
                }
            )
        }
    }
}
