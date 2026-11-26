package io.github.totocastaldi.herdrdesktop.coreclient.workspace

import io.github.totocastaldi.herdrdesktop.coreclient.connection.CoreRpc
import io.github.totocastaldi.herdrdesktop.coreclient.protocol.RpcError
import io.github.totocastaldi.herdrdesktop.coreclient.protocol.RpcException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** In-memory core: versioned documents and terminals, with the error kinds of herdr-core. */
class FakeCore : CoreRpc {
    class Term(val address: String, var cwd: String, var live: Boolean = true, val labels: Map<String, String>)

    val docs = mutableMapOf<String, Pair<JsonElement, Long>>()
    val terminals = linkedMapOf<String, Term>()
    val calls = mutableListOf<String>()

    /** Writes another client makes right before the next `doc.set`s, to provoke conflicts. */
    var interleavedWrites = 0

    /** Makes every `doc.set` fail with this kind. */
    var failDocSet: String? = null
    private var next = 1

    override suspend fun call(method: String, params: JsonObject?): JsonElement {
        calls += method
        return when (method) {
            "doc.get" -> docGet(params!!.str("name"))

            "doc.set" -> docSet(params!!)

            "terminal.list" -> buildJsonObject {
                put("terminals", JsonArray(terminals.values.filter { it.live }.map { it.json() }))
            }

            "terminal.run" -> run(params!!)

            "terminal.kill" -> term(params!!).also { it.live = false }.json()

            "terminal.rm" -> rm(params!!)

            else -> throw RpcException(RpcError(-32601, "no method $method", "not_found"))
        }
    }

    fun docValue(name: String = WorkspaceCodec.DOC_NAME): JsonElement? = docs[name]?.first

    private fun docGet(name: String) = buildJsonObject {
        val (value, version) = docs[name] ?: (JsonNull to 0L)
        put("value", value)
        put("version", version)
    }

    private fun docSet(params: JsonObject): JsonElement {
        failDocSet?.let { throw RpcException(RpcError(-32000, "refused", it)) }
        val name = params.str("name")
        if (interleavedWrites > 0) {
            interleavedWrites--
            val (value, version) = docs[name] ?: (JsonNull to 0L)
            docs[name] = value to version + 1
        }
        val current = docs[name]?.second ?: 0L
        val expected = params.getValue("version").jsonPrimitive.long
        if (expected != current) throw RpcException(RpcError(-32000, "at version $current", "conflict"))
        docs[name] = params.getValue("value") to current + 1
        return buildJsonObject { put("version", current + 1) }
    }

    private fun run(params: JsonObject): JsonElement {
        val address = "t${next++}"
        val labels = (params["labels"] as? JsonObject).orEmpty().mapValues { it.value.jsonPrimitive.content }
        val t = Term(address, (params["cwd"] as? JsonPrimitive)?.content ?: HOME, labels = labels)
        terminals[address] = t
        return t.json()
    }

    private fun rm(params: JsonObject): JsonElement {
        val t = term(params)
        if (t.live) throw RpcException(RpcError(-32000, "still running", "live"))
        terminals.remove(t.address)
        return buildJsonObject { put("removed", t.address) }
    }

    private fun term(params: JsonObject): Term =
        terminals[params.str("terminal")] ?: throw RpcException(RpcError(-32000, "no terminal", "not_found"))

    private fun Term.json() = buildJsonObject {
        put("address", address)
        put("cwd", HOME)
        put("state", if (live) "live" else "finished")
        if (live) putJsonObject("foreground") { put("cwd", cwd) }
    }

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    companion object {
        const val HOME = "/home/toto"
    }
}

/** Deterministic ids: `sp1`, `tb2`, `p3`, … */
class CountingIds : IdGenerator {
    private var n = 0

    override fun next(prefix: String) = prefix + ++n
}
