package io.github.totocastaldi.herdrdesktop.coreclient.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspaceRulesTest {
    private val cwds = mapOf("t1" to "/home/toto/scm/herdr", "t2" to "/srv/www/", "t3" to "/")
    private val cwdOf: (String) -> String? = { cwds[it] }

    private fun tab(id: String, vararg terminals: String, name: String = "") = Tab(
        id,
        name,
        PaneNode.Split(id, Axis.ROW, terminals.mapIndexed { i, t -> PaneNode.Leaf("$id-p$i", t) })
    )

    private val ws = Workspace(
        listOf(
            Space("a", tabs = listOf(tab("a1", "t1"), tab("a2", "t2"))),
            Space("b", name = "Server", tabs = listOf(tab("b1", "t2"))),
            Space("c", folder = "/opt/app/", tabs = emptyList())
        )
    )

    @Test
    fun `a named space shows its name`() {
        assertEquals("Server", WorkspaceNames.displayName(ws.space("b")!!, cwdOf))
        assertEquals("x", WorkspaceNames.displayName(Space("s", name = "  x  "), cwdOf))
    }

    @Test
    fun `an unnamed space shows the folder of its first terminal`() {
        assertEquals("herdr", WorkspaceNames.displayName(ws.space("a")!!, cwdOf))
        val split = Space("s", tabs = listOf(tab("x", "t2", "t1")))
        assertEquals("www", WorkspaceNames.displayName(split, cwdOf))
        val blankName = Space("s", name = "   ", tabs = listOf(tab("x", "t3")))
        assertEquals("/", WorkspaceNames.displayName(blankName, cwdOf))
    }

    @Test
    fun `without a known terminal folder the space folder is shown, else nothing`() {
        assertEquals("app", WorkspaceNames.displayName(ws.space("c")!!, cwdOf))
        val unknown = Space("s", folder = "/data/x", tabs = listOf(tab("x", "t9")))
        assertEquals("x", WorkspaceNames.displayName(unknown, cwdOf))
        assertNull(WorkspaceNames.displayName(Space("s", tabs = listOf(tab("x", "t9"))), cwdOf))
    }

    @Test
    fun `an unnamed tab shows its position`() {
        assertEquals("2", WorkspaceNames.tabLabel(tab("a2", "t2"), 1))
        assertEquals("logs", WorkspaceNames.tabLabel(tab("a2", "t2", name = " logs "), 1))
    }

    @Test
    fun `a new tab starts in the folder of the active terminal`() {
        val space = ws.space("a")!!
        assertEquals("/srv/www/", WorkspaceNames.newTabFolder(space, space.tab("a2"), cwdOf))
        val split = tab("x", "t2", "t1")
        assertEquals("/srv/www/", WorkspaceNames.newTabFolder(space, split, cwdOf))
        assertEquals("/home/toto/scm/herdr", WorkspaceNames.newTabFolder(space, split, cwdOf, activeTerminal = "t1"))
    }

    @Test
    fun `without an active terminal folder a new tab starts in the space folder, else the core default`() {
        val c = ws.space("c")!!
        assertEquals("/opt/app/", WorkspaceNames.newTabFolder(c, null, cwdOf))
        assertEquals("/opt/app/", WorkspaceNames.newTabFolder(c, tab("x", "t9"), cwdOf))
        assertNull(WorkspaceNames.newTabFolder(Space("s"), null, cwdOf))
    }

    @Test
    fun `renaming trims the name and a blank name makes the space unnamed`() {
        assertEquals("Web", WorkspaceEdits.renameSpace(ws, "b", "  Web ").space("b")!!.name)
        val unnamed = WorkspaceEdits.renameSpace(ws, "b", "   ")
        assertEquals("", unnamed.space("b")!!.name)
        assertEquals("www", WorkspaceNames.displayName(unnamed.space("b")!!, cwdOf))
        assertEquals("logs", WorkspaceEdits.renameTab(ws, "a", "a2", "logs ").space("a")!!.tab("a2")!!.name)
    }

    @Test
    fun `spaces and tabs move to a clamped position`() {
        assertEquals(listOf("b", "c", "a"), WorkspaceEdits.moveSpace(ws, "a", 2).spaces.map { it.id })
        assertEquals(listOf("c", "a", "b"), WorkspaceEdits.moveSpace(ws, "c", -5).spaces.map { it.id })
        assertEquals(listOf("b", "c", "a"), WorkspaceEdits.moveSpace(ws, "a", 99).spaces.map { it.id })
        assertEquals(listOf("a2", "a1"), WorkspaceEdits.moveTab(ws, "a", "a2", 0).space("a")!!.tabs.map { it.id })
    }

    @Test
    fun `adding and removing spaces and tabs`() {
        val added = WorkspaceEdits.addTab(ws, "c", tab("c1", "t5"))
        assertEquals(listOf("c1"), added.space("c")!!.tabs.map { it.id })
        assertEquals(listOf("a1"), WorkspaceEdits.removeTab(ws, "a", "a2").space("a")!!.tabs.map { it.id })
        assertEquals(listOf("a", "c"), WorkspaceEdits.removeSpace(ws, "b").spaces.map { it.id })
        assertEquals(listOf("a", "b", "c", "d"), WorkspaceEdits.addSpace(ws, Space("d")).spaces.map { it.id })
    }

    @Test
    fun `edits of ids that no longer exist change nothing`() {
        assertEquals(ws, WorkspaceEdits.renameSpace(ws, "zz", "x"))
        assertEquals(ws, WorkspaceEdits.moveSpace(ws, "zz", 0))
        assertEquals(ws, WorkspaceEdits.moveTab(ws, "a", "zz", 0))
        assertEquals(ws, WorkspaceEdits.removeTab(ws, "zz", "a1"))
        assertEquals(ws, WorkspaceEdits.addTab(ws, "zz", tab("q", "t1")))
    }

    @Test
    fun `folder names`() {
        assertEquals("herdr", WorkspaceNames.folderName("/home/toto/herdr"))
        assertEquals("herdr", WorkspaceNames.folderName("/home/toto/herdr/"))
        assertEquals("/", WorkspaceNames.folderName("/"))
        assertEquals("rel", WorkspaceNames.folderName("rel"))
    }
}
