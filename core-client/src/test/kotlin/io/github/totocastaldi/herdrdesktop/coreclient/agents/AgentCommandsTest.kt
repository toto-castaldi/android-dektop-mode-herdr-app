package io.github.totocastaldi.herdrdesktop.coreclient.agents

import io.github.totocastaldi.herdrdesktop.coreclient.connection.CoreRpc
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.CountingIds
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.FakeCore
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.PaneLocation
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.WorkspaceCodec
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.WorkspaceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeWindows : AgentWindows {
    override val machines = MutableStateFlow<Set<String>>(emptySet())
    var selected: String? = null
    val selects = mutableListOf<Triple<String, String, String?>>()
    val focuses = mutableListOf<Pair<String, PaneLocation?>>()

    override fun selectedSpace(machineId: String) = selected

    override fun select(machineId: String, spaceId: String, tabId: String?): Boolean {
        if (machineId !in machines.value) return false
        selects += Triple(machineId, spaceId, tabId)
        return true
    }

    override fun focus(machineId: String, address: String, location: PaneLocation?): Boolean {
        if (machineId !in machines.value) return false
        focuses += address to location
        return true
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AgentCommandsTest {
    private val core = FakeCore()
    private val sessions = MutableStateFlow<Map<String, CoreRpc>>(mapOf(M to core))
    private val snapshots = MutableStateFlow<Map<String, AgentSnapshot>>(emptyMap())
    private val windows = FakeWindows()
    private val logged = mutableListOf<String>()
    private var seq = 0L

    private fun TestScope.device(scope: CoroutineScope = backgroundScope, w: AgentWindows = windows): AgentCommands {
        val workspaces = WorkspaceManager(sessions, scope, CountingIds())
        runCurrent()
        return AgentCommands(sessions, snapshots, workspaces, w, scope) { m, action, outcome ->
            logged += "$m $action $outcome"
        }
    }

    /** The core's terminals as the events would bring them. */
    private fun TestScope.sync() {
        val records = core.terminals.values.associate {
            it.address to AgentRecord(it.address, live = it.live, labels = it.labels.toMap())
        }
        snapshots.value = mapOf(M to AgentSnapshot(++seq, records))
        runCurrent()
    }

    private fun TestScope.ask(address: String, id: String, action: String, args: String = "{}") {
        val value = """{"id":"$id","action":"$action","args":$args}"""
        val t = core.terminals[address]
        if (t == null) {
            core.addTerminal(address, mapOf(AgentCommandCodec.REQUEST_LABEL to value))
        } else {
            t.labels[AgentCommandCodec.REQUEST_LABEL] = value
        }
        sync()
        sync()
    }

    private fun answer(address: String): JsonObject? =
        core.terminals[address]?.labels?.get(AgentCommandCodec.RESPONSE_LABEL)?.let {
            Json.parseToJsonElement(it).jsonObject
        }

    private fun JsonObject.text(key: String) = get(key)!!.jsonPrimitive.content

    private fun result(address: String) = answer(address)!!.getValue("result").jsonObject

    @Test
    fun `space new runs in the requester's folder and answers what it made`() = runTest {
        device()
        core.addTerminal("t1")
        core.terminals.getValue("t1").cwd = "/work"

        ask("t1", "a1", "space.new", """{"name":"Web","argv":["claude"]}""")

        val answer = answer("t1")!!
        assertEquals("a1", answer.text("id"))
        assertEquals("true", answer.text("ok"))
        val made = result("t1")
        val ws = WorkspaceCodec.decode(core.docValue())
        val space = ws.spaces.single()
        assertEquals("Web", space.name)
        assertEquals(made.text("space"), space.id)
        assertEquals(made.text("tab"), space.tabs.single().id)
        assertEquals(listOf(made.text("terminal")), space.tabs.single().terminals)
        val run = core.runs.single()
        assertEquals("/work", run.text("cwd"))
        assertEquals("""["claude"]""", run["argv"].toString())
        assertFalse("terminal.kill" in core.calls)
        assertNull(core.docValue(WorkspaceCodec.DESKTOP_DOC_NAME))
        assertEquals(listOf("m1 space.new ok"), logged)
    }

    @Test
    fun `with two devices a request runs once`() = runTest {
        device()
        device()
        core.addTerminal("t1")

        ask("t1", "a1", "space.new")
        ask("t1", "a2", "space.new")

        assertEquals(2, core.runs.size)
        assertEquals("a2", answer("t1")!!.text("id"))
        assertEquals(2, logged.count { it.endsWith(" ok") })
        assertEquals(2, logged.count { it.endsWith("claimed_elsewhere") })
    }

    @Test
    fun `a request found unanswered at connection runs, an answered one does not`() = runTest {
        core.addTerminal("t1", mapOf(AgentCommandCodec.REQUEST_LABEL to """{"id":"old","action":"space.new"}"""))
        core.addTerminal(
            "t2",
            mapOf(
                AgentCommandCodec.REQUEST_LABEL to """{"id":"done","action":"space.new"}""",
                AgentCommandCodec.RESPONSE_LABEL to """{"id":"done","ok":true,"result":{}}"""
            )
        )
        val dead = """{"id":"dead","action":"space.new"}"""
        core.addTerminal("t3", mapOf(AgentCommandCodec.REQUEST_LABEL to dead), live = false)
        sync()

        device()
        sync()

        assertEquals(1, core.runs.size)
        assertEquals("old", answer("t1")!!.text("id"))
        assertEquals("done", answer("t2")!!.text("id"))
        assertNull(answer("t3"))
    }

    @Test
    fun `tab new and pane split default to the requester's space and pane`() = runTest {
        device()
        core.addTerminal("t1")
        ask("t1", "a1", "space.new")
        val requester = result("t1").text("terminal")
        core.terminals.getValue(requester).cwd = "/proj"

        ask(requester, "b1", "tab.new", """{"name":"Logs"}""")
        ask(requester, "b2", "pane.split", """{"direction":"down"}""")

        val ws = WorkspaceCodec.decode(core.docValue())
        val space = ws.spaces.single()
        assertEquals(listOf("", "Logs"), space.tabs.map { it.name })
        val tab = result(requester).let { ws.paneOf(it.text("terminal"))!! }
        assertEquals(space.tabs.first().id, tab.tabId)
        assertEquals(tab.paneId, result(requester).text("pane"))
        assertEquals(listOf("/proj", "/proj"), core.runs.drop(1).map { it.text("cwd") })
    }

    @Test
    fun `selection and focus wait for a window of the machine`() = runTest {
        device()
        core.addTerminal("t1")
        ask("t1", "a1", "space.new")
        val agent = result("t1").text("terminal")

        ask("t1", "f1", "agent.focus", """{"terminal":"$agent"}""")
        assertEquals("a1", answer("t1")!!.text("id"))
        assertFalse(core.docValue(AgentClaims.DOC_NAME).toString().contains("f1"))

        windows.machines.value = setOf(M)
        runCurrent()
        sync()

        assertEquals("f1", answer("t1")!!.text("id"))
        val location = WorkspaceCodec.decode(core.docValue()).paneOf(agent)
        assertEquals(listOf(agent to location), windows.focuses)
        assertEquals(location!!.paneId, result("t1").text("pane"))
    }

    @Test
    fun `space and tab select by index or id`() = runTest {
        device()
        windows.machines.value = setOf(M)
        core.addTerminal("t1")
        ask("t1", "a1", "space.new")
        ask("t1", "a2", "space.new")
        val ws = WorkspaceCodec.decode(core.docValue())

        ask("t1", "s1", "space.select", """{"index":2}""")
        windows.selected = ws.spaces[1].id
        ask("t1", "s2", "tab.select", """{"index":1}""")
        ask("t1", "s3", "tab.select", """{"id":"${ws.spaces[0].tabs[0].id}"}""")

        assertEquals(
            listOf(
                Triple(M, ws.spaces[1].id, null),
                Triple(M, ws.spaces[1].id, ws.spaces[1].tabs[0].id),
                Triple(M, ws.spaces[0].id, ws.spaces[0].tabs[0].id)
            ),
            windows.selects
        )
    }

    @Test
    fun `unsupported and failing requests answer an error, and the log has no arguments`() = runTest {
        device()
        windows.machines.value = setOf(M)
        core.addTerminal("t1")

        ask("t1", "a1", "tab.close", """{"secret":"/home/x/key"}""")
        assertEquals("unsupported", answer("t1")!!.text("error"))
        ask("t1", "a2", "space.select", """{"id":"nope"}""")
        assertEquals("not_found", answer("t1")!!.text("error"))
        ask("t1", "a3", "agent.focus", """{"terminal":"t77"}""")
        assertEquals("not_found", answer("t1")!!.text("error"))

        assertEquals(listOf("m1 other unsupported", "m1 space.select not_found", "m1 agent.focus not_found"), logged)
        assertTrue(core.runs.isEmpty())
    }

    private companion object {
        const val M = "m1"
    }
}
