package io.github.totocastaldi.herdrdesktop.coreclient.workspace

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's spaces move into its own document (REQ-WS-001): those it wrote in the desktop app's document while
 * sharing it, and those of its old document.
 */
class WorkspaceMigrationTest {
    private val core = FakeCore()
    private val client = WorkspaceClient(core, CountingIds())

    /** A space of the desktop app, with a terminal the desktop app started. */
    private val desktop =
        Space("spdesk", folder = "/home/toto/d", tabs = listOf(WorkspaceEdits.singlePaneTab("tbd", "pd", "t9")))

    /** A space the app wrote in the desktop app's document: two tabs, terminals started by the app. */
    private val own = Space(
        "spown",
        name = "Server",
        tabs = listOf(
            WorkspaceEdits.singlePaneTab("tb1", "p1", "t1"),
            WorkspaceEdits.singlePaneTab("tb2", "p2", "t2")
        )
    )

    /** A space with a terminal of the app and one the desktop app added to it. */
    private val mixed = Space(
        "spmix",
        tabs = listOf(WorkspaceEdits.singlePaneTab("tb3", "p3", "t3"), WorkspaceEdits.singlePaneTab("tb4", "p4", "t4"))
    )

    private fun appTerminal(address: String, space: String, tab: String, live: Boolean = true) = core.addTerminal(
        address,
        mapOf(APP to APP_VALUE, "herdr.space" to space, "herdr.tab" to tab, "herdr.seen" to "0"),
        live
    )

    private fun seed(vararg spaces: Space = arrayOf(desktop, own, mixed)) {
        core.addTerminal("t9", mapOf("herdr.space" to "spdesk", "herdr.tab" to "tbd"))
        appTerminal("t1", "spown", "tb1")
        appTerminal("t2", "spown", "tb2")
        appTerminal("t3", "spmix", "tb3")
        core.addTerminal("t4", mapOf("herdr.space" to "spmix", "herdr.tab" to "tb4"))
        core.write(WorkspaceCodec.DESKTOP_DOC_NAME, WorkspaceCodec.encode(Workspace(spaces.toList(), extra = EXTRA)))
    }

    private fun desktopDoc() = WorkspaceCodec.decode(core.docValue(WorkspaceCodec.DESKTOP_DOC_NAME))

    private fun appDoc() = WorkspaceCodec.decode(core.docValue())

    private fun labels(address: String) = core.terminals.getValue(address).labels

    @Test
    fun `a space of the app only moves to the app's document and leaves the desktop app's`() = runTest {
        seed()

        assertTrue(client.migrate())

        assertEquals(listOf(own), appDoc().spaces)
        assertEquals(listOf("spdesk", "spmix"), desktopDoc().spaces.map { it.id })
        // The rest of the desktop app's document is written back as it was.
        assertEquals(EXTRA, desktopDoc().extra)
        assertEquals(desktop, desktopDoc().space("spdesk"))
        val t2 = labels("t2")
        assertEquals("spown", t2["herdr-desktop-android.space"])
        assertEquals("tb2", t2["herdr-desktop-android.tab"])
        assertNull(t2["herdr.space"])
        assertNull(t2["herdr.tab"])
        assertEquals(APP_VALUE, t2[APP])
        assertEquals("0", t2["herdr.seen"])
    }

    @Test
    fun `a space with a terminal of the desktop app stays, with its labels`() = runTest {
        seed()

        client.migrate()

        assertEquals(mixed, desktopDoc().space("spmix"))
        assertNull(appDoc().space("spmix"))
        assertEquals("spmix", labels("t3")["herdr.space"])
        assertNull(labels("t3")["herdr-desktop-android.space"])
        assertEquals(mapOf("herdr.space" to "spdesk", "herdr.tab" to "tbd"), labels("t9"))
    }

    @Test
    fun `an empty space or one whose terminal is unknown stays with the desktop app`() = runTest {
        val empty = Space("spempty")
        val gone = Space("spgone", tabs = listOf(WorkspaceEdits.singlePaneTab("tb5", "p5", "t5")))
        seed(desktop, empty, gone)

        assertFalse(client.migrate())

        assertEquals(listOf("spdesk", "spempty", "spgone"), desktopDoc().spaces.map { it.id })
        assertNull(core.docValue())
    }

    @Test
    fun `a finished terminal of the app still counts as the app's`() = runTest {
        seed(own)
        core.terminals.getValue("t2").live = false

        assertTrue(client.migrate())

        assertEquals(listOf("spown"), appDoc().spaces.map { it.id })
    }

    @Test
    fun `a space whose id the app's document already has is not added twice but leaves the desktop app's`() = runTest {
        seed()
        core.write(WorkspaceCodec.DOC_NAME, WorkspaceCodec.encode(Workspace(listOf(own))))

        assertTrue(client.migrate())

        assertEquals(listOf("spown"), appDoc().spaces.map { it.id })
        assertEquals(listOf("spdesk", "spmix"), desktopDoc().spaces.map { it.id })
    }

    @Test
    fun `a conflict on the desktop app's document leaves both documents as they were`() = runTest {
        seed()
        val before = core.docValue(WorkspaceCodec.DESKTOP_DOC_NAME)!!
        // The desktop app writes its document between the app's two writes.
        core.beforeNextDocSet = {
            core.beforeNextDocSet = { core.write(WorkspaceCodec.DESKTOP_DOC_NAME, before) }
        }

        assertFalse(client.migrate())

        assertEquals(before, core.docValue(WorkspaceCodec.DESKTOP_DOC_NAME))
        assertTrue(appDoc().spaces.isEmpty())
        assertEquals("spown", labels("t1")["herdr.space"])
        assertNull(labels("t1")["herdr-desktop-android.space"])

        // The next session moves it.
        assertTrue(client.migrate())
        assertEquals(listOf("spown"), appDoc().spaces.map { it.id })
    }

    @Test
    fun `no terminal is started or ended`() = runTest {
        seed()

        client.migrate()
        client.migrate()

        assertEquals(setOf("t9", "t1", "t2", "t3", "t4"), core.terminals.keys)
        assertTrue(core.terminals.values.all { it.live })
        assertFalse(core.calls.any { it == "terminal.run" || it == "terminal.kill" || it == "terminal.rm" })
    }

    @Test
    fun `labels missed by an interrupted run are fixed by the next`() = runTest {
        appTerminal("t1", "spown", "tb1")
        appTerminal("t2", "spown", "tb2")
        core.write(WorkspaceCodec.DOC_NAME, WorkspaceCodec.encode(Workspace(listOf(own))))

        client.migrate()
        val labelled = core.calls.count { it == "terminal.label" }
        client.migrate()

        assertEquals("tb1", labels("t1")["herdr-desktop-android.tab"])
        assertNull(labels("t1")["herdr.space"])
        assertEquals(2, labelled)
        // Terminals already labelled are not written again.
        assertEquals(labelled, core.calls.count { it == "terminal.label" })
    }

    @Test
    fun `the app's old document moves to the app's document and is deleted, never written again`() = runTest {
        core.addTerminal("t1", mapOf(APP to APP_VALUE))
        val old = Space("spold", tabs = listOf(WorkspaceEdits.singlePaneTab("tb1", "p1", "t1")))
        core.write(WorkspaceCodec.LEGACY_DOC_NAME, WorkspaceCodec.encode(Workspace(listOf(old))))

        assertTrue(client.migrate())
        assertFalse(client.migrate())

        assertEquals(listOf(old), appDoc().spaces)
        assertEquals(JsonNull, core.docValue(WorkspaceCodec.LEGACY_DOC_NAME))
        assertEquals("spold", labels("t1")["herdr-desktop-android.space"])
        assertNull(core.docValue(WorkspaceCodec.DESKTOP_DOC_NAME))
    }

    @Test
    fun `a core without the other documents is left alone`() = runTest {
        assertFalse(client.migrate())

        assertTrue(core.calls.all { it == "doc.get" })
        assertTrue(core.docs.isEmpty())
    }

    private companion object {
        const val APP = "app"
        const val APP_VALUE = "herdr-android"
        val EXTRA = JsonObject(mapOf("future" to JsonPrimitive(1), "other" to JsonNull))
    }
}
