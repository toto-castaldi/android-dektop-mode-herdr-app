package io.github.totocastaldi.herdrdesktop.coreclient.workspace

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Splitting, resizing and closing panes through the core document (REQ-WS-002). */
class PaneEditsTest {
    private val core = FakeCore()
    private val client = WorkspaceClient(core, CountingIds())

    private suspend fun firstTab(): Pair<String, Tab> {
        val space = client.createSpace().workspace.spaces.single()
        return space.id to space.tabs.single()
    }

    @Test
    fun `splitting a pane starts a shell in the given folder and adds its pane to the document`() = runTest {
        val (spaceId, tab) = firstTab()
        val pane = PaneLayout.leaves(tab.root).single()

        val ws = client.splitPane(spaceId, tab.id, pane.id, Axis.COLUMN, "/srv").workspace

        val root = ws.space(spaceId)!!.tab(tab.id)!!.root as PaneNode.Split
        assertEquals(Axis.COLUMN, root.axis)
        assertEquals(listOf("t1", "t2"), root.terminals())
        assertEquals("/srv", core.terminals.getValue("t2").cwd)
        assertEquals(ws, WorkspaceCodec.decode(core.docValue()))
    }

    @Test
    fun `a split of a pane that no longer exists ends the new shell`() = runTest {
        val (spaceId, tab) = firstTab()

        try {
            client.splitPane(spaceId, tab.id, "gone", Axis.ROW, null)
            fail("expected a failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("gone"))
        }

        assertEquals(setOf("t1"), core.terminals.keys)
    }

    @Test
    fun `closing a pane removes it and ends only its terminal`() = runTest {
        val (spaceId, tab) = firstTab()
        val first = PaneLayout.leaves(tab.root).single()
        val ws = client.splitPane(spaceId, tab.id, first.id, Axis.ROW, null).workspace
        val second = PaneLayout.leaves(ws.space(spaceId)!!.tab(tab.id)!!.root)[1]

        val after = client.closePane(spaceId, tab.id, second.id).workspace

        assertEquals(listOf("t1"), after.space(spaceId)!!.tab(tab.id)!!.terminals)
        assertEquals(setOf("t1"), core.terminals.keys)
    }

    @Test
    fun `closing the last pane closes the tab`() = runTest {
        val (spaceId, tab) = firstTab()
        val pane = PaneLayout.leaves(tab.root).single()

        val after = client.closePane(spaceId, tab.id, pane.id).workspace

        assertTrue(after.space(spaceId)!!.tabs.isEmpty())
        assertFalse("t1" in core.terminals)
    }

    @Test
    fun `resizing writes the weights of the split`() = runTest {
        val (spaceId, tab) = firstTab()
        val pane = PaneLayout.leaves(tab.root).single()
        client.splitPane(spaceId, tab.id, pane.id, Axis.ROW, null)

        val ws = client.resizeSplit(spaceId, tab.id, tab.root.id, listOf(0.7, 0.3)).workspace

        val root = ws.space(spaceId)!!.tab(tab.id)!!.root as PaneNode.Split
        assertEquals(listOf(0.7, 0.3), root.children.map { it.weight })
    }

    @Test
    fun `a pane edit retries on conflict with another client`() = runTest {
        val (spaceId, tab) = firstTab()
        val pane = PaneLayout.leaves(tab.root).single()
        core.interleavedWrites = 2

        val ws = client.splitPane(spaceId, tab.id, pane.id, Axis.ROW, null).workspace

        assertEquals(listOf("t1", "t2"), ws.space(spaceId)!!.tab(tab.id)!!.terminals)
    }
}
