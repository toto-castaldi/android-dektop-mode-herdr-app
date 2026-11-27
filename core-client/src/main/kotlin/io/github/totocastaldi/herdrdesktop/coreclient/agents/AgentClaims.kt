package io.github.totocastaldi.herdrdesktop.coreclient.agents

import io.github.totocastaldi.herdrdesktop.coreclient.connection.CoreRpc
import io.github.totocastaldi.herdrdesktop.coreclient.protocol.RpcException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The claims of the agents' requests on one core (REQ-AGT-003): the document [DOC_NAME] holds, for every
 * terminal, the id of the last request a device of the app took. A device carries out a request only if it
 * writes its id there with compare-and-set, so with several devices on the same core each request runs once.
 */
class AgentClaims(private val rpc: CoreRpc) {
    /**
     * Claims request [id] of terminal [address]: true when this call wrote it, false when it was already
     * claimed (by another device, or by this one before). Entries of terminals not in [known] are dropped
     * while writing. A `conflict` reads again, up to [MAX_ATTEMPTS] times; other errors are thrown.
     */
    suspend fun claim(address: String, id: String, known: Set<String>): Boolean {
        repeat(MAX_ATTEMPTS - 1) {
            try {
                return tryClaim(address, id, known)
            } catch (e: RpcException) {
                if (e.error.kind != CONFLICT) throw e
            }
        }
        return tryClaim(address, id, known)
    }

    private suspend fun tryClaim(address: String, id: String, known: Set<String>): Boolean {
        val read = rpc.call("doc.get", buildJsonObject { put("name", DOC_NAME) }) as JsonObject
        val version = (read["version"] as? JsonPrimitive)?.longOrNull ?: 0L
        val claims = AgentRecord.labelsOf(read["value"])
        if (claims[address] == id) return false
        val value = buildJsonObject {
            claims.filterKeys { it in known && it != address }.forEach { (k, v) -> put(k, v) }
            put(address, id)
        }
        rpc.call(
            "doc.set",
            buildJsonObject {
                put("name", DOC_NAME)
                put("value", value)
                put("version", version)
            }
        )
        return true
    }

    companion object {
        const val DOC_NAME = "herdr-desktop-android.claims"
        const val MAX_ATTEMPTS = 5
        private const val CONFLICT = "conflict"
    }
}
