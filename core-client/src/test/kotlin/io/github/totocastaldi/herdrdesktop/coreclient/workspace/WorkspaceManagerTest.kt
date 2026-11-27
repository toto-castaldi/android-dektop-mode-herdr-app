package io.github.totocastaldi.herdrdesktop.coreclient.workspace

import io.github.totocastaldi.herdrdesktop.coreclient.connection.CoreRpc
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceManagerTest {
    private val core = FakeCore()
    private val sessions = MutableStateFlow<Map<String, CoreRpc>>(emptyMap())

    private fun ready(manager: WorkspaceManager, id: String = "m1") = manager.state(id).value as WorkspaceState.Ready

    @Test
    fun `nothing is shown until the machine is connected, then the core state is loaded`() = runTest {
        val manager = WorkspaceManager(sessions, backgroundScope, CountingIds())
        runCurrent()
        assertEquals(WorkspaceState.Unavailable, manager.state("m1").value)

        sessions.value = mapOf("m1" to core)
        runCurrent()

        assertTrue(ready(manager).workspace.spaces.isEmpty())
    }

    @Test
    fun `actions show the core's answer and terminals`() = runTest {
        val manager = WorkspaceManager(sessions, backgroundScope, CountingIds())
        sessions.value = mapOf("m1" to core)
        runCurrent()

        manager.perform("m1") { createSpace() }
        runCurrent()
        val space = ready(manager).workspace.spaces.single()
        manager.perform("m1") { renameSpace(space.id, "Web") }
        manager.perform("m1") { createTab(space.id, "/srv") }
        runCurrent()

        val state = ready(manager)
        assertEquals("Web", state.workspace.space(space.id)!!.name)
        assertEquals("/srv", state.cwdOf("t2"))
    }

    @Test
    fun `after a reconnection the state is read again from the core`() = runTest {
        val manager = WorkspaceManager(sessions, backgroundScope, CountingIds())
        sessions.value = mapOf("m1" to core)
        runCurrent()
        manager.perform("m1") { createSpace() }
        runCurrent()

        sessions.value = emptyMap()
        runCurrent()
        assertEquals(WorkspaceState.Unavailable, manager.state("m1").value)

        // Another client changed the document while the app was away.
        WorkspaceClient(core).document.update { ws -> ws.copy(spaces = ws.spaces.map { it.copy(name = "Altro") }) }
        sessions.value = mapOf("m1" to CoreRpc { m, p -> core.call(m, p) })
        runCurrent()

        assertEquals("Altro", ready(manager).workspace.spaces.single().name)
    }

    @Test
    fun `a failed action keeps the shown state and reports the error`() = runTest {
        val manager = WorkspaceManager(sessions, backgroundScope, CountingIds())
        sessions.value = mapOf("m1" to core)
        runCurrent()
        core.failDocSet = "too_large"

        manager.perform("m1") { createSpace() }
        runCurrent()

        val state = ready(manager)
        assertTrue(state.workspace.spaces.isEmpty())
        assertNotNull(state.error)
        assertTrue(core.terminals.isEmpty())
    }

    @Test
    fun `a new session shows the app's spaces moved out of the desktop app's document`() = runTest {
        core.addTerminal("t1", mapOf("app" to "herdr-android", "herdr.space" to "spold", "herdr.tab" to "tb1"))
        val old = Space("spold", tabs = listOf(WorkspaceEdits.singlePaneTab("tb1", "p1", "t1")))
        core.write(WorkspaceCodec.DESKTOP_DOC_NAME, WorkspaceCodec.encode(Workspace(listOf(old))))
        val manager = WorkspaceManager(sessions, backgroundScope, CountingIds())

        sessions.value = mapOf("m1" to core)
        runCurrent()

        assertEquals(listOf("spold"), ready(manager).workspace.spaces.map { it.id })
        assertEquals(listOf("spold"), WorkspaceCodec.decode(core.docValue()).spaces.map { it.id })
        assertTrue(WorkspaceCodec.decode(core.docValue(WorkspaceCodec.DESKTOP_DOC_NAME)).spaces.isEmpty())
    }

    @Test
    fun `a failed move still shows the shared document`() = runTest {
        core.addTerminal("t1", mapOf("app" to "herdr-android"))
        val old = Space("spold", tabs = listOf(WorkspaceEdits.singlePaneTab("tb1", "p1", "t1")))
        core.write(WorkspaceCodec.DESKTOP_DOC_NAME, WorkspaceCodec.encode(Workspace(listOf(old))))
        core.failDocSet = "unavailable"
        val manager = WorkspaceManager(sessions, backgroundScope, CountingIds())

        sessions.value = mapOf("m1" to core)
        runCurrent()

        assertTrue(ready(manager).workspace.spaces.isEmpty())
    }
}
