package io.github.totocastaldi.herdrdesktop.coreclient.workspace

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Spaces in the desktop app's format (`herdr-app`, the shape of the app's document too, where the app's
 * spaces move from it) survive the app's writes: what the app does not use (mod panes, stacks, pixel sizes,
 * floating blocks, fields of a newer desktop app) is written back unchanged.
 * `two-tabs.json` is a document read from a real core used by the desktop app (folder anonymised);
 * `mods-and-stacks.json` adds the nodes the desktop app's layout allows in a tab.
 */
class DesktopDocumentTest {
    private val core = FakeCore()
    private val client = WorkspaceClient(core, CountingIds())

    @Test
    fun `real documents are written back exactly as they were read`() {
        DOCS.forEach { name ->
            val doc = load(name)
            assertEquals(name, doc, WorkspaceCodec.encode(WorkspaceCodec.decode(doc)))
        }
    }

    @Test
    fun `a pane without a terminal and a stack are kept whole`() {
        val tab = WorkspaceCodec.decode(load("mods-and-stacks.json")).spaces.single().tabs.first()

        assertEquals(listOf("t4", "t5"), tab.terminals)
        val leaves = PaneLayout.leaves(tab.root)
        assertEquals(listOf("block", "block", "block", "stack"), leaves.map { it.type })
        assertNull(leaves[0].terminal)
    }

    @Test
    fun `renaming, adding and closing keep the parts the app does not use`() = runTest {
        val doc = load("mods-and-stacks.json")
        core.write(WorkspaceCodec.DOC_NAME, doc)
        core.addTerminal("t5")

        client.renameTab("sp9f1c", "tb77a0", "codice")
        client.splitPane("sp9f1c", "tb77a0", "p4", Axis.COLUMN, null)
        client.closePane("sp9f1c", "tb77a0", "p4")
        client.createTab("sp9f1c", null)
        client.closeTab("sp9f1c", "tb2")

        val after = core.docValue()!!.jsonObject
        val before = doc.jsonObject
        val tabsBefore = before.space().getValue("tabs").jsonArray
        val tabsAfter = after.space().getValue("tabs").jsonArray
        // Space fields (also `color`) and the first tab's other fields are unchanged.
        assertEquals(before.space() - "tabs", after.space() - "tabs")
        val first = tabsAfter[0].jsonObject
        assertEquals(tabsBefore[0].jsonObject - "name" - "root", first - "name" - "root")
        val children = first.getValue("root").jsonObject.getValue("children").jsonArray
        val childrenBefore = tabsBefore[0].jsonObject.getValue("root").jsonObject.getValue("children").jsonArray
        // The mod pane and the stack are untouched; in the column only the new pane replaced p4.
        assertEquals(childrenBefore[0], children[0])
        assertEquals(childrenBefore[2], children[2])
        val column = children[1].jsonObject
        assertEquals(childrenBefore[1].jsonObject - "children", column - "children")
        assertEquals(childrenBefore[1].jsonObject.children()[0], column.children()[0])
        // Closing tb2 and adding a tab: two tabs again, the new one in the desktop app's shape.
        assertEquals(listOf("tb77a0", "tb3"), tabsAfter.map { it.jsonObject.getValue("id").toString().trim('"') })
    }

    private fun JsonObject.space() = getValue("spaces").jsonArray[0].jsonObject

    private fun JsonObject.children(): JsonArray = getValue("children").jsonArray

    private fun load(name: String): JsonElement =
        Json.parseToJsonElement(checkNotNull(javaClass.getResource("/desktop-docs/$name")).readText())

    private companion object {
        val DOCS = listOf("two-tabs.json", "mods-and-stacks.json")
    }
}
