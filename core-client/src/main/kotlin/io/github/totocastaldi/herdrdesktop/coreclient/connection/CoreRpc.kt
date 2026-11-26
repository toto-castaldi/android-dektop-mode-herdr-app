package io.github.totocastaldi.herdrdesktop.coreclient.connection

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** JSON-RPC calls to a connected core; implemented by [CoreSession]. */
fun interface CoreRpc {
    /** Calls [method] and returns its `result`; throws `RpcException` on a JSON-RPC error. */
    suspend fun call(method: String, params: JsonObject?): JsonElement
}
