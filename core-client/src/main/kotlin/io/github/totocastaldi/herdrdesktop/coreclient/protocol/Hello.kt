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

    /**
     * The core is too old for the app: it must be updated. [foundVersion] is the version of the core
     * (null when `hello` failed), [requiredVersion] the oldest the app supports, [missing] what the
     * core lacks besides the version.
     */
    data class CoreUpdateNeeded(
        val foundVersion: String?,
        val requiredVersion: String = CoreProtocol.MIN_CORE_VERSION,
        val missing: Missing? = null
    ) : Compatibility

    /** What an otherwise recent enough core lacks. */
    sealed interface Missing {
        /** The core speaks an older protocol generation. */
        data class Generation(val generation: Int) : Missing

        /** The core lacks methods the app calls. */
        data class Methods(val methods: List<String>) : Missing

        /** The core does not offer framed mode. */
        data object Framed : Missing

        /** `hello` failed with this error kind. */
        data class HelloError(val kind: String?) : Missing
    }
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

            compareVersions(hello.version, MIN_CORE_VERSION) < 0 -> Compatibility.CoreUpdateNeeded(hello.version)

            hello.generation < GENERATION ->
                Compatibility.CoreUpdateNeeded(
                    hello.version,
                    missing = Compatibility.Missing.Generation(hello.generation)
                )

            missing.isNotEmpty() ->
                Compatibility.CoreUpdateNeeded(hello.version, missing = Compatibility.Missing.Methods(missing))

            !hello.framed -> Compatibility.CoreUpdateNeeded(hello.version, missing = Compatibility.Missing.Framed)

            else -> Compatibility.Compatible
        }
    }

    /** Compatibility of a failed `hello`: `unsupported_generation` means the generations differ. */
    fun checkError(error: RpcError): Compatibility = if (error.kind == "unsupported_generation") {
        Compatibility.AppUpdateNeeded(null)
    } else {
        Compatibility.CoreUpdateNeeded(null, missing = Compatibility.Missing.HelloError(error.kind))
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
