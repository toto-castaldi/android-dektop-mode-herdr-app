package io.github.totocastaldi.herdrdesktop.coreclient.connection

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** JSON-RPC calls to a connected core; implemented by [CoreSession]. */
fun interface CoreRpc {
    /** Calls [method] and returns its `result`; throws `RpcException` on a JSON-RPC error. */
    suspend fun call(method: String, params: JsonObject?): JsonElement

    /**
     * JSON lines arriving on a notification [channel] (for example the one returned by `events.subscribe`),
     * from the first frame on; the flow ends when the core closes the channel or the session ends.
     */
    fun lines(channel: Int): Flow<String> = emptyFlow()
}
