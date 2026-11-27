package io.github.totocastaldi.herdrdesktop.coreclient.workspace

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Terminals the app starts carry the app's space and tab labels, never the desktop app's (REQ-WS-001). */
class SharedLabelsTest {
    private val core = FakeCore()
    private val client = WorkspaceClient(core, CountingIds())

    @Test
    fun `new spaces, tabs and panes label their terminal with the ids of the document`() = runTest {
        val space = client.createSpace().workspace.spaces.single()
        val tab = client.createTab(space.id, null).workspace.space(space.id)!!.tabs[1]
        val pane = PaneLayout.leaves(tab.root).single()
        val split = client.splitPane(space.id, tab.id, pane.id, Axis.ROW, null).workspace

        val ws = split.space(space.id)!!
        ws.tabs.forEach { t ->
            t.terminals.forEach { address ->
                val labels = core.terminals.getValue(address).labels
                assertEquals(space.id, labels["herdr-desktop-android.space"])
                assertEquals(t.id, labels["herdr-desktop-android.tab"])
                assertEquals("0", labels["herdr.seen"])
                assertEquals("herdr-android", labels["app"])
                assertFalse(labels.containsKey("herdr.space"))
                assertFalse(labels.containsKey("herdr.tab"))
            }
        }
        assertEquals(3, ws.tabs.sumOf { it.terminals.size })
        assertEquals(setOf(WorkspaceCodec.DOC_NAME), core.docs.keys)
    }

    @Test
    fun `a terminal placed meanwhile by another client is not added twice`() = runTest {
        // Another client of the app (another device) writes the space itself first.
        core.beforeNextDocSet = {
            val placed = Space("sp1", tabs = listOf(WorkspaceEdits.singlePaneTab("tb2", "pdesk", "t1")))
            core.write(WorkspaceCodec.DOC_NAME, WorkspaceCodec.encode(Workspace(listOf(placed))))
        }

        val ws = client.createSpace().workspace

        assertEquals(listOf("pdesk"), ws.spaces.single().tabs.single().root.let(PaneLayout::leaves).map { it.id })
        assertEquals(setOf("t1"), core.terminals.keys)
    }

    @Test
    fun `a pane placed meanwhile by another client is not split twice`() = runTest {
        val space = client.createSpace().workspace.spaces.single()
        val tab = space.tabs.single()
        val pane = PaneLayout.leaves(tab.root).single()
        core.beforeNextDocSet = {
            val adopted = WorkspaceEdits.addTab(
                Workspace(listOf(space)),
                space.id,
                WorkspaceEdits.singlePaneTab("tbx", "px", "t2")
            )
            core.write(WorkspaceCodec.DOC_NAME, WorkspaceCodec.encode(adopted))
        }

        val ws = client.splitPane(space.id, tab.id, pane.id, Axis.ROW, null).workspace

        assertEquals(listOf("t1", "t2"), ws.space(space.id)!!.tabs.flatMap { it.terminals })
    }
}
