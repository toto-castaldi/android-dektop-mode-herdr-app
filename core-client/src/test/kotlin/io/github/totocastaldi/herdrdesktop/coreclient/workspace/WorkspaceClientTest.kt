package io.github.totocastaldi.herdrdesktop.coreclient.workspace

import io.github.totocastaldi.herdrdesktop.coreclient.protocol.RpcException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WorkspaceClientTest {
    private val core = FakeCore()
    private val client = WorkspaceClient(core, CountingIds())

    @Test
    fun `a new space has one tab with a shell, written to the app document only`() = runTest {
        val snapshot = client.createSpace()

        val space = snapshot.workspace.spaces.single()
        assertEquals(listOf("t1"), space.tabs.single().terminals)
        assertEquals(setOf(WorkspaceCodec.DOC_NAME), core.docs.keys)
        assertEquals("herdr-android", core.terminals.getValue("t1").labels["app"])
        assertEquals(1L, snapshot.version)
    }

    @Test
    fun `a new tab starts its shell in the given folder`() = runTest {
        val spaceId = client.createSpace().workspace.spaces.single().id

        val ws = client.createTab(spaceId, "/srv/www").workspace

        assertEquals(listOf("t1", "t2"), ws.space(spaceId)!!.tabs.flatMap { it.terminals })
        assertEquals("/srv/www", core.terminals.getValue("t2").cwd)
    }

    @Test
    fun `state written by one client is what another client reads after a restart`() = runTest {
        val spaceId = client.createSpace().workspace.spaces.single().id
        client.renameSpace(spaceId, "Server")
        client.createTab(spaceId, null)

        val reopened = WorkspaceClient(FakeCoreView(core), CountingIds()).document.load().workspace

        assertEquals("Server", reopened.space(spaceId)!!.name)
        assertEquals(2, reopened.space(spaceId)!!.tabs.size)
        assertEquals(setOf("t1", "t2"), WorkspaceClient(core).terminals.list().keys)
    }

    @Test
    fun `closing a tab ends and removes its terminals, and only them`() = runTest {
        val spaceId = client.createSpace().workspace.spaces.single().id
        val ws = client.createTab(spaceId, null).workspace
        val second = ws.space(spaceId)!!.tabs[1]

        val after = client.closeTab(spaceId, second.id).workspace

        assertEquals(1, after.space(spaceId)!!.tabs.size)
        assertEquals(setOf("t1"), core.terminals.keys)
    }

    @Test
    fun `closing a space ends the terminals of all its tabs`() = runTest {
        val first = client.createSpace().workspace.spaces.single().id
        client.createTab(first, null)
        client.createSpace()

        val after = client.closeSpace(first).workspace

        assertEquals(1, after.spaces.size)
        assertEquals(setOf("t3"), core.terminals.keys)
    }

    @Test
    fun `closing a tab whose terminal is already gone still succeeds`() = runTest {
        val space = client.createSpace().workspace.spaces.single()
        core.terminals.clear()

        client.closeTab(space.id, space.tabs.single().id)

        assertTrue(WorkspaceCodec.decode(core.docValue()).space(space.id)!!.tabs.isEmpty())
    }

    @Test
    fun `a concurrent write is merged by reading again`() = runTest {
        val spaceId = client.createSpace().workspace.spaces.single().id
        core.interleavedWrites = 2

        val ws = client.renameSpace(spaceId, "Web").workspace

        assertEquals("Web", ws.space(spaceId)!!.name)
        assertEquals(4L, core.docs.getValue(WorkspaceCodec.DOC_NAME).second)
    }

    @Test
    fun `a write that fails ends the terminal just started`() = runTest {
        core.failDocSet = "too_large"
        try {
            client.createSpace()
            fail("expected an RpcException")
        } catch (e: RpcException) {
            assertEquals("too_large", e.error.kind)
        }
        assertTrue(core.terminals.isEmpty())
        assertFalse(core.docs.containsKey(WorkspaceCodec.DOC_NAME))
    }

    @Test
    fun `a tab for a space removed meanwhile is not added and its terminal is ended`() = runTest {
        try {
            client.createTab("gone", null)
            fail("expected an IllegalStateException")
        } catch (_: IllegalStateException) {
        }
        assertTrue(core.terminals.isEmpty())
    }

    @Test
    fun `an unchanged document is not written`() = runTest {
        client.createSpace()
        val before = core.calls.count { it == "doc.set" }

        client.renameSpace("gone", "x")

        assertEquals(before, core.calls.count { it == "doc.set" })
    }

    @Test
    fun `terminals report their current folder`() = runTest {
        client.createSpace()
        core.terminals.getValue("t1").cwd = "/tmp/work"

        assertEquals("/tmp/work", client.terminals.list().getValue("t1").cwd)
    }

    /** The same core seen through another session. */
    private class FakeCoreView(core: FakeCore) :
        io.github.totocastaldi.herdrdesktop.coreclient.connection.CoreRpc by core
}
