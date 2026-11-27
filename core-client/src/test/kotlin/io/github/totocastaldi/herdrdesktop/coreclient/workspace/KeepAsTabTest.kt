package io.github.totocastaldi.herdrdesktop.coreclient.workspace

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class KeepAsTabTest {
    private val core = FakeCore()
    private val client = WorkspaceClient(core, CountingIds())

    @Test
    fun `a peeked worker becomes a new tab of the space, still running and with the app's labels`() = runTest {
        val space = client.createSpace().workspace.spaces.single()
        core.addTerminal("t9", mapOf("herdr.space" to "desktop", "other" to "x"))

        val ws = client.keepAsTab(space.id, "t9").workspace

        val tabs = ws.space(space.id)!!.tabs
        assertEquals(listOf(listOf("t1"), listOf("t9")), tabs.map { it.terminals })
        val worker = core.terminals.getValue("t9")
        assertTrue(worker.live)
        assertEquals(space.id, worker.labels[CoreTerminals.SPACE_LABEL])
        assertEquals(tabs[1].id, worker.labels[CoreTerminals.TAB_LABEL])
        assertFalse(CoreTerminals.DESKTOP_SPACE_LABEL in worker.labels)
        assertEquals("x", worker.labels["other"])
        assertEquals(listOf("terminal.run"), core.calls.filter { it.startsWith("terminal.r") || it == "terminal.kill" })
        assertEquals(setOf(WorkspaceCodec.DOC_NAME), core.docs.keys)
    }

    @Test
    fun `a terminal the document already shows is not added twice`() = runTest {
        val space = client.createSpace().workspace.spaces.single()

        val ws = client.keepAsTab(space.id, "t1").workspace

        assertEquals(1, ws.space(space.id)!!.tabs.size)
    }

    @Test
    fun `a space closed meanwhile is an error and the terminal is left as it was`() = runTest {
        core.addTerminal("t9")

        try {
            client.keepAsTab("gone", "t9")
            fail("expected an error")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("gone"))
        }
        assertTrue(core.terminals.getValue("t9").live)
        assertTrue(core.terminals.getValue("t9").labels.isEmpty())
    }
}
