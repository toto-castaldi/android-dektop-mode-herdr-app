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
 * How the end-to-end tests leave a real core as they found it. The app's spaces document
 * (`herdr-desktop-android`) may hold the user's spaces: a test closes only the spaces it created (which ends
 * their terminals), never the others, and removes the document only if there was none before. Nothing else
 * of the core is changed.
 */
internal object E2eCleanup {
    /**
     * Fails unless the spaces document of the core has no spaces; returns true if there is no document at all.
     * Opening the workspace screen would otherwise attach to, and resize, terminals the test does not own.
     */
    fun requireNoSpaces(rpc: CoreRpc): Boolean = runBlocking {
        val result = rpc.call("doc.get", buildJsonObject { put("name", WorkspaceCodec.DOC_NAME) }) as JsonObject
        val spaces = WorkspaceCodec.decode(result["value"]).spaces
        check(spaces.isEmpty()) {
            "the core already has ${spaces.size} spaces: the test does not touch them"
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

/**
 * The spaces a test found on the core ([record], right after connecting) and those it knows it owns ([own]):
 * [cleanUp] closes only the spaces that were not there before, plus the owned ones. Without a record and
 * without owned spaces it changes nothing, also when the test failed before recording.
 */
internal class E2eSpaces {
    private var before: Set<String>? = null
    private var hadDocument = true
    private val owned = mutableSetOf<String>()

    fun record(rpc: CoreRpc) = runBlocking {
        val result = rpc.call("doc.get", buildJsonObject { put("name", WorkspaceCodec.DOC_NAME) }) as JsonObject
        before = WorkspaceCodec.decode(result["value"]).spaces.map { it.id }.toSet()
        hadDocument = result["value"] != null && result["value"] != JsonNull
    }

    fun own(spaceId: String) {
        owned += spaceId
    }

    suspend fun cleanUp(rpc: CoreRpc) {
        val known = before
        if (known == null && owned.isEmpty()) return
        val current = WorkspaceClient(rpc).document.load().workspace.spaces.map { it.id }
        val created = if (known == null) emptySet() else current.toSet() - known
        E2eCleanup.closeOwn(rpc, created + owned, startedWithoutDocument = !hadDocument && known != null)
    }
}
