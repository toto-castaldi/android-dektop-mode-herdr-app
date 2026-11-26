package io.github.totocastaldi.herdrdesktop.coreclient.agents

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The facts of a core terminal the agents sidebar needs, from a `Terminal` record of `events.subscribe` and
 * kept up to date by its events ([AgentSnapshot]).
 */
data class AgentRecord(
    val address: String,
    val name: String? = null,
    val title: String = "",
    val parent: String? = null,
    val live: Boolean = true,
    val reported: Boolean = false,
    val status: String? = null,
    val app: String? = null,
    val message: String = "",
    val turn: Long = 0,
    val labels: Map<String, String> = emptyMap()
) {
    /** A live program that reported its status, or a worker started by another terminal (`hc run`). */
    val isAgent: Boolean get() = live && (reported || parent != null)

    /** Last turn a person looked at, on any client; null when the label is missing (not 0). */
    val seen: Long? get() = labels[SEEN_LABEL]?.toLongOrNull()

    val state: AgentState get() = AgentState.of(status, turn, seen)

    /** Terminal name, else the reporting program, else the title, else the address. */
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: app ?: title.ifBlank { address }

    /** Applies a `status` event: the root record (null when cleared) and whether it ever reported. */
    fun withStatus(data: JsonObject): AgentRecord {
        val root = data["status"] as? JsonObject
        return copy(
            status = root?.string("state"),
            app = root?.string("app")?.takeIf { it.isNotBlank() },
            message = root?.string("msg").orEmpty(),
            reported = data["reported"].bool() ?: reported
        )
    }

    companion object {
        /** Label shared by the Herdr clients: the last turn a person looked at. */
        const val SEEN_LABEL = "herdr.seen"

        fun from(o: JsonObject): AgentRecord? {
            val address = o.string("address") ?: return null
            val base = AgentRecord(
                address = address,
                name = o.string("name"),
                title = o.string("title").orEmpty(),
                parent = o.string("parent"),
                live = o.string("state").let { it == null || it == "live" },
                turn = o["turn"].long() ?: 0,
                labels = labelsOf(o["labels"])
            )
            return base.withStatus(o)
        }

        fun labelsOf(value: JsonElement?): Map<String, String> = (value as? JsonObject).orEmpty()
            .mapNotNull { (k, v) -> (v as? JsonPrimitive)?.let { k to it.content } }.toMap()
    }
}

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.longOrNull

private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
