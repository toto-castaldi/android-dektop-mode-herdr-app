package io.github.totocastaldi.herdrdesktop.coreclient.agents

import io.github.totocastaldi.herdrdesktop.coreclient.workspace.Axis
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * A request an agent wrote in the label [AgentCommandCodec.REQUEST_LABEL] of its own terminal (REQ-AGT-003):
 * `{"id": "<unique>", "action": "<action>", "args": {…}}`, with the action names and arguments of `herdr-app do`.
 */
data class AgentCommandRequest(val id: String, val action: String?, val args: JsonElement?)

/** The actions of `herdr-app do` the app carries out; none of them closes anything or ends a terminal. */
sealed interface AgentCommand {
    /** True when the command acts on a window of the app (selection, focus), not only on the core's document. */
    val needsWindow: Boolean get() = false

    data class SpaceNew(val name: String, val folder: String?, val argv: List<String>) : AgentCommand

    data class TabNew(val name: String, val space: String?, val folder: String?, val argv: List<String>) :
        AgentCommand

    data class PaneSplit(val axis: Axis, val pane: String?, val argv: List<String>) : AgentCommand

    /** By [id] or by 1-based [index]. */
    data class SpaceSelect(val id: String?, val index: Int?) : AgentCommand {
        override val needsWindow: Boolean get() = true
    }

    /** By [id], or by 1-based [index] in the space the window shows. */
    data class TabSelect(val id: String?, val index: Int?) : AgentCommand {
        override val needsWindow: Boolean get() = true
    }

    data class AgentFocus(val terminal: String) : AgentCommand {
        override val needsWindow: Boolean get() = true
    }
}

/** A request that cannot be carried out: [kind] is stable (see [AgentCommandCodec]), [message] says why. */
class AgentCommandFailure(val kind: String, override val message: String) : Exception(message)

/**
 * Labels, parsing and answers of the agents' commands (REQ-AGT-003, `docs/design/integrations.md`).
 * The answer goes in [RESPONSE_LABEL] of the same terminal, with the request's id first so that the agent's
 * recipe recognises it: `{"id", "ok": true, "result": {…}}` or `{"id", "ok": false, "error", "message"}`.
 */
object AgentCommandCodec {
    const val REQUEST_LABEL = "herdr-desktop-android.do"
    const val RESPONSE_LABEL = "herdr-desktop-android.re"

    const val UNSUPPORTED = "unsupported"
    const val INVALID_ARGS = "invalid_args"
    const val NOT_FOUND = "not_found"
    const val NO_WINDOW = "no_window"
    const val CONFLICT = "conflict"
    const val FAILED = "failed"

    /** The actions the app carries out; every other action answers [UNSUPPORTED]. */
    val ACTIONS = listOf("space.new", "tab.new", "pane.split", "space.select", "tab.select", "agent.focus")

    private val ARGS = mapOf(
        "space.new" to setOf("name", "folder", "argv"),
        "tab.new" to setOf("name", "space", "folder", "argv"),
        "pane.split" to setOf("direction", "pane", "argv"),
        "space.select" to setOf("id", "index"),
        "tab.select" to setOf("id", "index"),
        "agent.focus" to setOf("terminal")
    )

    /** The request in a [REQUEST_LABEL] value; null when it is empty or has no string `id` to answer to. */
    fun request(value: String?): AgentCommandRequest? {
        val o = value?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        val id = o?.string("id")?.takeIf { it.isNotBlank() }
        return if (o == null || id == null) null else AgentCommandRequest(id, o.string("action"), o["args"])
    }

    /** The id a [RESPONSE_LABEL] value answers, null when there is none. */
    fun answeredId(value: String?): String? = value?.let {
        runCatching { (Json.parseToJsonElement(it) as? JsonObject)?.string("id") }.getOrNull()
    }

    /** The action name as it may be logged: one of [ACTIONS], otherwise a fixed word, never the agent's text. */
    fun loggable(action: String?): String = action?.takeIf { it in ACTIONS } ?: "other"

    /** The command of [request]; throws [AgentCommandFailure] for an unsupported action or wrong arguments. */
    fun command(request: AgentCommandRequest): AgentCommand {
        val action = request.action ?: invalid("azione mancante")
        val allowed = ARGS[action] ?: throw AgentCommandFailure(UNSUPPORTED, "azione non supportata: $action")
        val args = when (val a = request.args) {
            null, is JsonNull -> JsonObject(emptyMap())
            is JsonObject -> JsonObject(a.filterValues { it !is JsonNull })
            else -> invalid("args deve essere un oggetto JSON")
        }
        val unknown = args.keys - allowed
        if (unknown.isNotEmpty()) {
            invalid(
                "argomenti non ammessi: ${unknown.sorted().joinToString()}; ammessi: ${allowed.sorted().joinToString()}"
            )
        }
        return Args(args).command(action)
    }

    private fun Args.command(action: String): AgentCommand = when (action) {
        "space.new" -> AgentCommand.SpaceNew(text("name").orEmpty(), text("folder"), argv())

        "tab.new" -> AgentCommand.TabNew(text("name").orEmpty(), text("space"), text("folder"), argv())

        "pane.split" -> AgentCommand.PaneSplit(direction(), text("pane"), argv())

        "space.select" -> selection().let { (id, index) -> AgentCommand.SpaceSelect(id, index) }

        "tab.select" -> selection().let { (id, index) -> AgentCommand.TabSelect(id, index) }

        else -> AgentCommand.AgentFocus(
            text("terminal") ?: invalid("terminal è obbligatorio")
        )
    }

    private fun invalid(message: String): Nothing = throw AgentCommandFailure(INVALID_ARGS, message)

    fun ok(id: String, result: JsonObject): String = buildJsonObject {
        put("id", id)
        put("ok", true)
        put("result", result)
    }.toString()

    fun error(id: String, kind: String, message: String): String = buildJsonObject {
        put("id", id)
        put("ok", false)
        put("error", kind)
        put("message", message)
    }.toString()

    /** The arguments of one request, checked as `herdr-app do` does: ids are strings, null is omitted. */
    private class Args(private val o: JsonObject) {
        fun text(key: String): String? {
            val v = o[key] ?: return null
            if (v !is JsonPrimitive || !v.isString) invalid("$key deve essere una stringa")
            return v.content
        }

        /** A JSON array of strings, or a string holding one (as `--argv '["claude"]'` passes it). */
        fun argv(): List<String> {
            val v = o["argv"] ?: return emptyList()
            val array = when {
                v is JsonArray -> v

                v is JsonPrimitive && v.isString ->
                    runCatching { Json.parseToJsonElement(v.content) as? JsonArray }.getOrNull()

                else -> null
            }
            val items = array?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            if (items == null || items.any { it == null }) {
                invalid("argv deve essere un array di stringhe")
            }
            return items.filterNotNull()
        }

        fun direction(): Axis = when (text("direction")) {
            "right" -> Axis.ROW
            "down" -> Axis.COLUMN
            null -> invalid("direction è obbligatorio (right o down)")
            else -> invalid("direction deve essere right o down")
        }

        /** `id` or a 1-based `index` (a number, or a string of digits as the `name:value` shorthand gives). */
        fun selection(): Pair<String?, Int?> {
            val id = text("id")
            val index = o["index"]?.let { v ->
                val n = (v as? JsonPrimitive)?.let { p -> if (p.isString) p.content.toIntOrNull() else p.intOrNull }
                if (n == null || n < 1) invalid("index deve essere un numero da 1")
                n
            }
            if ((id == null) == (index == null)) invalid("serve id oppure index")
            return id to index
        }
    }
}
