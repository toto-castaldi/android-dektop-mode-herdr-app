package io.github.totocastaldi.herdrdesktop.coreclient.workspace

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Spaces and tabs of one machine, as stored in the core document [WorkspaceCodec.DOC_NAME]
 * (docs/design/data-model.md). Fields the app does not know are kept in `extra` and written back.
 */
data class Workspace(val spaces: List<Space> = emptyList(), val extra: JsonObject = EMPTY) {
    fun space(id: String): Space? = spaces.firstOrNull { it.id == id }

    /** The space, tab and pane showing [terminal], or null when no pane shows it. */
    fun paneOf(terminal: String): PaneLocation? = spaces.firstNotNullOfOrNull { space ->
        space.tabs.firstNotNullOfOrNull { tab ->
            tab.root.leafOf(terminal)?.let { PaneLocation(space.id, tab.id, it.id) }
        }
    }
}

/** Where a terminal is shown: space, tab and pane ids. */
data class PaneLocation(val spaceId: String, val tabId: String, val paneId: String)

/** A space: optional [name] (blank when unnamed), optional [folder], ordered [tabs]. */
data class Space(
    val id: String,
    val name: String = "",
    val folder: String = "",
    val tabs: List<Tab> = emptyList(),
    val extra: JsonObject = EMPTY
) {
    fun tab(id: String): Tab? = tabs.firstOrNull { it.id == id }
}

/** A tab: optional [name] (blank when unnamed) and its pane tree. */
data class Tab(val id: String, val name: String = "", val root: PaneNode, val extra: JsonObject = EMPTY) {
    /** Terminal addresses of the tab, in pane order. */
    val terminals: List<String> get() = root.terminals()
}

/** Pane tree: a split of children along an [Axis], or a leaf showing a terminal. */
sealed interface PaneNode {
    val id: String

    /** Share of the parent split, when the parent records one. */
    val weight: Double?

    val extra: JsonObject

    data class Split(
        override val id: String,
        val axis: Axis,
        val children: List<PaneNode>,
        override val weight: Double? = null,
        override val extra: JsonObject = EMPTY
    ) : PaneNode

    data class Leaf(
        override val id: String,
        val terminal: String?,
        override val weight: Double? = null,
        override val extra: JsonObject = EMPTY,
        val contentExtra: JsonObject = EMPTY
    ) : PaneNode

    fun terminals(): List<String> = when (this) {
        is Leaf -> listOfNotNull(terminal)
        is Split -> children.flatMap { it.terminals() }
    }

    fun leafOf(terminal: String): Leaf? = when (this) {
        is Leaf -> takeIf { it.terminal == terminal }
        is Split -> children.firstNotNullOfOrNull { it.leafOf(terminal) }
    }
}

/** `row` lays children side by side, `column` one above the other. */
enum class Axis(val wire: String) {
    ROW("row"),
    COLUMN("column")
}

private val EMPTY = JsonObject(emptyMap())

/** JSON form of [Workspace], the same shape the desktop app uses for its own document. */
object WorkspaceCodec {
    /** Name of the core document holding this app's spaces and tabs; never the desktop app's `herdr-app`. */
    const val DOC_NAME = "herdr-android"

    fun decode(value: JsonElement?): Workspace {
        val root = value as? JsonObject ?: return Workspace()
        val spaces = (root["spaces"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::space) }
        return Workspace(spaces, root.without("spaces"))
    }

    fun encode(workspace: Workspace): JsonObject =
        JsonObject(workspace.extra + ("spaces" to JsonArray(workspace.spaces.map(::encodeSpace))))

    private fun space(o: JsonObject): Space? {
        val id = o.string("id") ?: return null
        val tabs = (o["tabs"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::tab) }
        return Space(
            id,
            o.string("name").orEmpty(),
            o.string("folder").orEmpty(),
            tabs,
            o.without("id", "name", "folder", "tabs")
        )
    }

    private fun tab(o: JsonObject): Tab? {
        val id = o.string("id") ?: return null
        val root = (o["root"] as? JsonObject)?.let(::node) ?: PaneNode.Split(id, Axis.ROW, emptyList())
        return Tab(id, o.string("name").orEmpty(), root, o.without("id", "name", "root"))
    }

    private fun node(o: JsonObject): PaneNode? {
        val id = o.string("id") ?: return null
        val weight = ((o["size"] as? JsonObject)?.get("weight") as? JsonPrimitive)?.doubleOrNull
        val type = o.string("type")
        val axis = Axis.entries.firstOrNull { it.wire == type }
        return if (axis != null) {
            val children = (o["children"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::node) }
            PaneNode.Split(id, axis, children, weight, o.without("id", "type", "children", "size"))
        } else {
            val content = o["content"] as? JsonObject ?: EMPTY
            PaneNode.Leaf(
                id,
                content.string("terminal"),
                weight,
                o.without("id", "type", "content", "size"),
                content.without("terminal")
            )
        }
    }

    private fun encodeSpace(s: Space) = JsonObject(
        s.extra + mapOf(
            "id" to JsonPrimitive(s.id),
            "name" to JsonPrimitive(s.name),
            "folder" to JsonPrimitive(s.folder),
            "tabs" to JsonArray(s.tabs.map(::encodeTab))
        )
    )

    private fun encodeTab(t: Tab) = JsonObject(
        t.extra + mapOf("id" to JsonPrimitive(t.id), "name" to JsonPrimitive(t.name), "root" to encodeNode(t.root))
    )

    private fun encodeNode(n: PaneNode): JsonObject {
        val known = mutableMapOf<String, JsonElement>("id" to JsonPrimitive(n.id))
        n.weight?.let { known["size"] = JsonObject(mapOf("weight" to JsonPrimitive(it))) }
        when (n) {
            is PaneNode.Split -> {
                known["type"] = JsonPrimitive(n.axis.wire)
                known["children"] = JsonArray(n.children.map(::encodeNode))
            }

            is PaneNode.Leaf -> {
                known["type"] = JsonPrimitive(LEAF_TYPE)
                val terminal = n.terminal?.let { mapOf("terminal" to JsonPrimitive(it)) }.orEmpty()
                known["content"] = JsonObject(n.contentExtra + terminal)
            }
        }
        return JsonObject(n.extra + known)
    }

    private const val LEAF_TYPE = "block"

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.without(vararg keys: String) = JsonObject(this - keys.toSet())
}
