package io.github.totocastaldi.herdrdesktop.coreclient.agents

import io.github.totocastaldi.herdrdesktop.coreclient.agents.AgentEventsJson.event
import io.github.totocastaldi.herdrdesktop.coreclient.agents.AgentEventsJson.labels
import io.github.totocastaldi.herdrdesktop.coreclient.agents.AgentEventsJson.snapshot
import io.github.totocastaldi.herdrdesktop.coreclient.agents.AgentEventsJson.status
import io.github.totocastaldi.herdrdesktop.coreclient.agents.AgentEventsJson.terminal
import io.github.totocastaldi.herdrdesktop.coreclient.agents.AgentEventsJson.turn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSnapshotTest {
    private fun parse(text: String) = Json.parseToJsonElement(text) as JsonObject

    private fun AgentSnapshot.on(vararg notifications: String) = notifications.fold(this) { s, n -> s.apply(parse(n)) }

    private fun AgentSnapshot.stateOf(address: String) = records.getValue(address).state

    private val start = AgentSnapshot.from(
        parse(
            snapshot(
                10,
                terminal("t1", status = "working", turn = 2, seen = 2, name = "api"),
                terminal("t2"),
                terminal("t3", parent = "t1"),
                terminal("t4", status = "done", turn = 1, state = "finished"),
                terminal("walle/t7", status = "blocked", turn = 1, seen = 0)
            )
        )
    )

    @Test
    fun `agents are the live terminals that reported or were started by another, also on added machines`() {
        assertEquals(listOf("t1", "t3", "walle/t7"), start.agents.map { it.address })
        assertEquals("api", start.records.getValue("t1").displayName)
        assertEquals(AgentState.BLOCKED, start.stateOf("walle/t7"))
        assertEquals(AgentState.IDLE, start.stateOf("t3"))
    }

    @Test
    fun `a finished turn is Done until the seen label reaches it, then Idle`() {
        val done = start.on(status(11, "t1", "done"), turn(12, "t1", 3))
        assertEquals(AgentState.DONE, done.stateOf("t1"))
        assertEquals("m", done.records.getValue("t1").message)

        val seen = done.on(labels(13, "t1", 3))
        assertEquals(AgentState.IDLE, seen.stateOf("t1"))
    }

    @Test
    fun `working and blocked follow the reported status`() {
        val s = start.on(status(11, "t3", "working"), status(12, "walle/t7", "working"), status(13, "t3", "blocked"))
        assertEquals(AgentState.BLOCKED, s.stateOf("t3"))
        assertEquals(AgentState.WORKING, s.stateOf("walle/t7"))
    }

    @Test
    fun `events at or below the snapshot seq are dropped`() {
        assertEquals(AgentState.WORKING, start.on(status(10, "t1", "blocked")).stateOf("t1"))
    }

    @Test
    fun `created workers join, finished and removed terminals leave`() {
        val s = start.on(
            event(11, "created", "t9", terminal("t9", parent = "t1")),
            event(12, "finished", "t3", """{"reason":"exited","exit_code":0}"""),
            event(13, "removed", "walle/t7", "{}")
        )
        assertEquals(listOf("t1", "t9"), s.agents.map { it.address })
    }

    @Test
    fun `missed replaces everything with its snapshot`() {
        val missed = """{"jsonrpc":"2.0","method":"missed","params":${snapshot(50, terminal("t5", status = "done"))}}"""
        val s = start.on(missed, status(40, "t5", "working"))
        assertEquals(50, s.seq)
        assertEquals(listOf("t5"), s.agents.map { it.address })
        assertEquals(AgentState.DONE, s.stateOf("t5"))
    }

    @Test
    fun `heartbeats and unknown notifications change nothing`() {
        val s = start.on("""{"jsonrpc":"2.0","method":"heartbeat","params":{"seq":10}}""", "{}")
        assertTrue(s == start)
    }

    @Test
    fun `only the app's agents and their workers are shown, not the desktop app's`() {
        val s = AgentSnapshot.from(
            parse(
                snapshot(
                    1,
                    terminal("t1", status = "working"),
                    terminal("t2", parent = "t1", space = null),
                    terminal("t3", parent = "t2", space = null),
                    terminal("t4", status = "done", space = null),
                    terminal("t5", parent = "t4", space = null),
                    terminal("t6", parent = "t7", space = null),
                    terminal("t7", parent = "t6", space = null)
                )
            )
        )

        // t2 and t3 are workers (directly or not) of the app's t1; t4 is the desktop app's, t5 its worker;
        // t6 and t7 start each other, without looping.
        assertEquals(listOf("t1", "t2", "t3"), s.agents.map { it.address })
    }

    @Test
    fun `an agent leaves when its terminal loses the app's space label`() {
        val s = start.on(event(11, "labels", "t1", """{"labels":{"herdr.seen":"2"}}"""))

        // t3, a worker of t1, has its own space label.
        assertEquals(listOf("t3", "walle/t7"), s.agents.map { it.address })
    }
}
