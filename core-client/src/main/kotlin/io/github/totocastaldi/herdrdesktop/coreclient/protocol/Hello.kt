package io.github.totocastaldi.herdrdesktop.coreclient.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The `hello` result of herdr-core, as far as the app uses it. */
@Serializable
data class HelloResult(
    val generation: Int,
    val version: String,
    val framed: Boolean = false,
    val methods: List<String> = emptyList(),
    val features: List<String> = emptyList(),
    val core: CoreIdentity? = null
)

@Serializable
data class CoreIdentity(val id: String? = null, val name: String? = null)

/** Whether the app can use a core (docs/design/integrations.md, Compatibility). */
sealed interface Compatibility {
    data object Compatible : Compatibility

    /** The core speaks a newer protocol generation: the app must be updated. */
    data class AppUpdateNeeded(val coreGeneration: Int?) : Compatibility

    /** The core is too old for the app: it must be updated. */
    data class CoreUpdateNeeded(val reason: String) : Compatibility
}

object CoreProtocol {
    const val GENERATION = 1

    /** Oldest core version the app supports, as the desktop app (`distribution/core-version`). */
    const val MIN_CORE_VERSION = "0.1.11"

    /** Methods the app calls; a core without one of them cannot be used. */
    val REQUIRED_METHODS = listOf("hello", "server.status")

    fun helloRequest(id: Long): String =
        """{"jsonrpc":"2.0","id":$id,"method":"hello","params":{"generation":$GENERATION,"framed":true}}"""

    internal val json = Json { ignoreUnknownKeys = true }

    fun parseHello(result: JsonElement): HelloResult = json.decodeFromJsonElement(HelloResult.serializer(), result)

    fun check(hello: HelloResult): Compatibility {
        val missing = REQUIRED_METHODS.filterNot { it in hello.methods }
        return when {
            hello.generation > GENERATION -> Compatibility.AppUpdateNeeded(hello.generation)

            hello.generation < GENERATION -> Compatibility.CoreUpdateNeeded("generation ${hello.generation}")

            compareVersions(
                hello.version,
                MIN_CORE_VERSION
            ) < 0 -> Compatibility.CoreUpdateNeeded("version ${hello.version}")

            missing.isNotEmpty() -> Compatibility.CoreUpdateNeeded("missing ${missing.joinToString()}")

            !hello.framed -> Compatibility.CoreUpdateNeeded("framed mode not available")

            else -> Compatibility.Compatible
        }
    }

    /** Compatibility of a failed `hello`: `unsupported_generation` means the generations differ. */
    fun checkError(error: RpcError): Compatibility = if (error.kind == "unsupported_generation") {
        Compatibility.AppUpdateNeeded(null)
    } else {
        Compatibility.CoreUpdateNeeded(error.kind ?: "hello failed")
    }

    /** Compares dotted numeric versions; a pre-release suffix (`-rc1`) is ignored. */
    fun compareVersions(a: String, b: String): Int {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val diff = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }
}

/** A JSON-RPC error; [kind] is the stable `data.kind` the app decides on. */
data class RpcError(val code: Int, val message: String, val kind: String?) {
    companion object {
        fun from(error: JsonObject): RpcError {
            val code = (error["code"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0
            val message = (error["message"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            val kind = (
                (error["data"] as? JsonObject)?.get(
                    "kind"
                ) as? kotlinx.serialization.json.JsonPrimitive
                )?.content
            return RpcError(code, message, kind)
        }
    }
}

class RpcException(val error: RpcError) : Exception("${error.kind ?: error.code}: ${error.message}")
