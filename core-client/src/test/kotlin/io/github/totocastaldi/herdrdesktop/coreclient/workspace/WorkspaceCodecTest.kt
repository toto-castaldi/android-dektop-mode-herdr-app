package io.github.totocastaldi.herdrdesktop.coreclient.workspace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceCodecTest {
    /** Shape of the desktop app document, with fields the app does not know. */
    private val doc = Json.parseToJsonElement(
        """
        {"spaces":[{"folder":"/home/toto/p","id":"sp1","name":"","color":"red","tabs":[
          {"id":"tb1","name":"","root":{"id":"tb1","type":"row","children":[
            {"content":{"terminal":"t70","zoom":2},"id":"p1","size":{"weight":0.5},"type":"block"},
            {"id":"c1","type":"column","children":[
              {"content":{"terminal":"t71"},"id":"p2","type":"block"},
              {"content":{},"id":"p3","type":"block","pinned":true}]}]}}]}],
         "focus":"sp1"}
        """
    )

    @Test
    fun `decodes spaces, tabs and pane trees`() {
        val ws = WorkspaceCodec.decode(doc)
        val space = ws.spaces.single()
        assertEquals("sp1", space.id)
        assertEquals("/home/toto/p", space.folder)
        assertEquals("", space.name)
        val tab = space.tabs.single()
        assertEquals(listOf("t70", "t71"), tab.terminals)
        val root = tab.root as PaneNode.Split
        assertEquals(Axis.ROW, root.axis)
        assertEquals(0.5, root.children[0].weight)
        assertEquals(Axis.COLUMN, (root.children[1] as PaneNode.Split).axis)
    }

    @Test
    fun `encoding keeps the fields the app does not know`() {
        assertEquals(doc, WorkspaceCodec.encode(WorkspaceCodec.decode(doc)))
    }

    @Test
    fun `a missing or foreign document is an empty workspace`() {
        assertTrue(WorkspaceCodec.decode(null).spaces.isEmpty())
        assertTrue(WorkspaceCodec.decode(JsonNull).spaces.isEmpty())
        assertTrue(WorkspaceCodec.decode(Json.parseToJsonElement("[1,2]")).spaces.isEmpty())
        assertTrue(WorkspaceCodec.decode(Json.parseToJsonElement("""{"spaces":[{"name":"no id"}]}""")).spaces.isEmpty())
    }

    @Test
    fun `the document is the app's own, not the desktop app's`() {
        assertEquals("herdr-android", WorkspaceCodec.DOC_NAME)
    }
}
