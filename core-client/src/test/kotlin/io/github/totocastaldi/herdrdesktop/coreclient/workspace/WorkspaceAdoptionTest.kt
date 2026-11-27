package io.github.totocastaldi.herdrdesktop.coreclient.workspace

import io.github.totocastaldi.herdrdesktop.coreclient.agents.Placement
import io.github.totocastaldi.herdrdesktop.coreclient.connection.CoreRpc
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Terminals started by agents with the app's space and tab labels (REQ-WS-003), on a simulated core. */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceAdoptionTest {
    private val core = FakeCore()
    private val client = WorkspaceClient(core, CountingIds())

    private suspend fun adopt(): WorkspaceSnapshot = client.adopt(client.document.load(), client.terminals.list()).first

    private fun labels(space: String, tab: String? = null) = buildMap {
        put(CoreTerminals.SPACE_LABEL, space)
        tab?.let { put(CoreTerminals.TAB_LABEL, it) }
    }

    @Test
    fun `a worker labelled with a space and one of its tabs becomes a new pane of that tab, still running`() = runTest {
        val space = client.createSpace().workspace.spaces.single()
        val tab = space.tabs.single()
        core.addTerminal("t9", labels(space.id, tab.id))

        val ws = adopt().workspace

        assertEquals(listOf(listOf("t1", "t9")), ws.space(space.id)!!.tabs.map { it.terminals })
        assertTrue(core.terminals.getValue("t9").live)
        assertEquals(listOf("terminal.run"), core.calls.filter { it in ENDING + "terminal.run" })
    }

    @Test
    fun `without the tab label the worker gets a new tab of the space, whose id it is then labelled with`() = runTest {
        val space = client.createSpace().workspace.spaces.single()
        core.addTerminal("t9", labels(space.id))

        val tabs = adopt().workspace.space(space.id)!!.tabs

        assertEquals(listOf(listOf("t1"), listOf("t9")), tabs.map { it.terminals })
        assertEquals(tabs[1].id, core.terminals.getValue("t9").labels[CoreTerminals.TAB_LABEL])
        assertEquals(space.id, core.terminals.getValue("t9").labels[CoreTerminals.SPACE_LABEL])
    }

    @Test
    fun `a tab the space does not have becomes a new tab with that id, shared by the workers that name it`() = runTest {
        val space = client.createSpace().workspace.spaces.single()
        core.addTerminal("t8", labels(space.id, "tbWorkers"))
        core.addTerminal("t9", labels(space.id, "tbWorkers"))

        val tabs = adopt().workspace.space(space.id)!!.tabs

        assertEquals(listOf("tb2", "tbWorkers"), tabs.map { it.id })
        assertEquals(listOf("t8", "t9"), tabs[1].terminals)
    }

    @Test
    fun `a tab id used by another space is not reused`() = runTest {
        val first = client.createSpace().workspace.spaces.single()
        val second = client.createSpace().workspace.spaces.last()
        core.addTerminal("t9", labels(second.id, first.tabs.single().id))

        val ws = adopt().workspace

        assertEquals(listOf("t1"), ws.space(first.id)!!.tabs.single().terminals)
        val adopted = ws.space(second.id)!!.tabs.last()
        assertEquals(listOf("t9"), adopted.terminals)
        assertEquals(adopted.id, core.terminals.getValue("t9").labels[CoreTerminals.TAB_LABEL])
    }

    @Test
    fun `a space the document does not have, the desktop app's labels and terminals without labels are ignored`() =
        runTest {
            val space = client.createSpace().workspace.spaces.single()
            core.addTerminal("t7", labels("spUnknown"))
            core.addTerminal("t8", mapOf("herdr.space" to space.id, "herdr.tab" to space.tabs.single().id))
            core.addTerminal("t9")
            val before = core.docValue()

            val snapshot = adopt()

            assertEquals(listOf("t1"), snapshot.workspace.spaces.single().tabs.flatMap { it.terminals })
            assertEquals(before, core.docValue())
            assertEquals(
                mapOf("herdr.space" to space.id, "herdr.tab" to space.tabs.single().id),
                core.terminals["t8"]!!.labels
            )
        }

    @Test
    fun `an adopted terminal loses the desktop app's labels, as with keep as tab`() = runTest {
        val space = client.createSpace().workspace.spaces.single()
        core.addTerminal("t9", labels(space.id) + ("herdr.space" to "desktop"))

        adopt()

        assertFalse("herdr.space" in core.terminals.getValue("t9").labels)
    }

    @Test
    fun `removing the space label takes the pane out of the app without ending the terminal`() = runTest {
        val space = client.createSpace().workspace.spaces.single()
        val tab = space.tabs.single()
        core.addTerminal("t9", labels(space.id, tab.id))
        adopt()

        core.terminals.getValue("t9").labels.remove(CoreTerminals.SPACE_LABEL)
        val ws = adopt().workspace

        assertEquals(listOf("t1"), ws.space(space.id)!!.tab(tab.id)!!.terminals)
        assertTrue(core.terminals.getValue("t9").live)
        assertTrue(core.calls.none { it in ENDING })
    }

    @Test
    fun `removing the label of the only pane of a tab closes the tab, not the terminal`() = runTest {
        val space = client.createSpace().workspace.spaces.single()
        core.addTerminal("t9", labels(space.id))
        adopt()

        core.terminals.getValue("t9").labels.remove(CoreTerminals.SPACE_LABEL)
        val tabs = adopt().workspace.space(space.id)!!.tabs

        assertEquals(listOf(listOf("t1")), tabs.map { it.terminals })
        assertTrue(core.terminals.getValue("t9").live)
    }

    @Test
    fun `a finished terminal keeps its pane and is not adopted again`() = runTest {
        val space = client.createSpace().workspace.spaces.single()
        core.addTerminal("t8", labels(space.id), live = false)
        core.terminals.getValue("t1").labels.remove(CoreTerminals.SPACE_LABEL)
        core.terminals.getValue("t1").live = false

        val ws = adopt().workspace

        assertEquals(listOf(listOf("t1")), ws.space(space.id)!!.tabs.map { it.terminals })
    }

    @Test
    fun `nothing to adopt writes nothing`() = runTest {
        client.createSpace()
        val writes = core.calls.count { it == "doc.set" }

        adopt()

        assertEquals(writes, core.calls.count { it == "doc.set" })
        assertTrue(core.calls.none { it == "terminal.label" })
    }

    @Test
    fun `a terminal started while the app was away is adopted at the next session`() = runTest {
        val space = client.createSpace().workspace.spaces.single()
        core.addTerminal("t9", labels(space.id))
        val sessions = MutableStateFlow<Map<String, CoreRpc>>(emptyMap())
        val manager = WorkspaceManager(sessions, backgroundScope, CountingIds())

        sessions.value = mapOf("m1" to core)
        runCurrent()

        val state = manager.state("m1").value as WorkspaceState.Ready
        assertEquals(listOf(listOf("t1"), listOf("t9")), state.workspace.space(space.id)!!.tabs.map { it.terminals })
        assertEquals(
            state.workspace.space(space.id)!!.tabs[1].id,
            state.terminals["t9"]!!.labels[CoreTerminals.TAB_LABEL]
        )
    }

    @Test
    fun `a change of the labels in the core's events reads the machine again`() = runTest {
        val sessions = MutableStateFlow<Map<String, CoreRpc>>(mapOf("m1" to core))
        val manager = WorkspaceManager(sessions, backgroundScope, CountingIds())
        val placements = MutableStateFlow<Map<String, Map<String, Placement>>>(emptyMap())
        manager.follow(placements)
        manager.perform("m1") { createSpace() }
        runCurrent()
        val space = (manager.state("m1").value as WorkspaceState.Ready).workspace.spaces.single()

        core.addTerminal("t9", labels(space.id))
        placements.value = mapOf("m1" to mapOf("t9" to Placement(space.id, null)))
        runCurrent()

        val state = manager.state("m1").value as WorkspaceState.Ready
        assertEquals(2, state.workspace.space(space.id)!!.tabs.size)
        assertNull(state.error)
    }

    private companion object {
        val ENDING = setOf("terminal.kill", "terminal.rm")
    }
}
