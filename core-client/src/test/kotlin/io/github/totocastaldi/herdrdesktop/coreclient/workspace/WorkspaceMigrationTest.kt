package io.github.totocastaldi.herdrdesktop.coreclient.workspace

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The spaces saved in the app's old document move to the shared one, once (REQ-WS-001). */
class WorkspaceMigrationTest {
    private val core = FakeCore()
    private val client = WorkspaceClient(core, CountingIds())

    private val desktop =
        Space("spdesk", folder = "/home/toto/d", tabs = listOf(WorkspaceEdits.singlePaneTab("tbd", "pd", "t9")))
    private val legacy = Space(
        "spold",
        name = "Server",
        tabs = listOf(
            WorkspaceEdits.singlePaneTab("tb1", "p1", "t1"),
            WorkspaceEdits.singlePaneTab("tb2", "p2", "t2")
        )
    )

    private fun seed() {
        core.addTerminal("t9", mapOf("herdr.space" to "spdesk", "herdr.tab" to "tbd"))
        core.addTerminal("t1", mapOf("app" to "herdr-android"))
        core.addTerminal("t2", mapOf("app" to "herdr-android"))
        core.write(WorkspaceCodec.DOC_NAME, WorkspaceCodec.encode(Workspace(listOf(desktop), extra = EXTRA)))
        core.write(WorkspaceCodec.LEGACY_DOC_NAME, WorkspaceCodec.encode(Workspace(listOf(legacy))))
    }

    @Test
    fun `old spaces join the shared document after the desktop app's, with their labels`() = runTest {
        seed()

        assertTrue(client.migrateLegacy())

        val shared = WorkspaceCodec.decode(core.docValue())
        assertEquals(listOf("spdesk", "spold"), shared.spaces.map { it.id })
        assertEquals(legacy, shared.space("spold"))
        assertEquals(EXTRA, shared.extra)
        assertEquals("spold", core.terminals.getValue("t2").labels["herdr.space"])
        assertEquals("tb2", core.terminals.getValue("t2").labels["herdr.tab"])
        assertEquals("herdr-android", core.terminals.getValue("t2").labels["app"])
    }

    @Test
    fun `no terminal is started or ended and the old document is deleted, not written again`() = runTest {
        seed()

        client.migrateLegacy()
        val calls = core.calls.toList()
        assertFalse(client.migrateLegacy())

        assertEquals(setOf("t9", "t1", "t2"), core.terminals.keys)
        assertTrue(core.terminals.values.all { it.live })
        assertFalse(calls.any { it == "terminal.run" || it == "terminal.kill" || it == "terminal.rm" })
        assertEquals(JsonNull, core.docValue(WorkspaceCodec.LEGACY_DOC_NAME))
        // The second run only reads the old document.
        assertEquals(listOf("doc.get"), core.calls.drop(calls.size))
    }

    @Test
    fun `a space already moved is not added twice`() = runTest {
        seed()
        core.write(WorkspaceCodec.DOC_NAME, WorkspaceCodec.encode(Workspace(listOf(desktop, legacy))))

        client.migrateLegacy()

        assertEquals(listOf("spdesk", "spold"), WorkspaceCodec.decode(core.docValue()).spaces.map { it.id })
    }

    @Test
    fun `a core without the old document is left alone`() = runTest {
        assertFalse(client.migrateLegacy())
        assertEquals(listOf("doc.get"), core.calls)
    }

    @Test
    fun `an old terminal already gone does not stop the move`() = runTest {
        seed()
        core.terminals.remove("t1")

        assertTrue(client.migrateLegacy())

        assertEquals(JsonNull, core.docValue(WorkspaceCodec.LEGACY_DOC_NAME))
    }

    private companion object {
        val EXTRA = JsonObject(mapOf("future" to JsonNull))
    }
}
