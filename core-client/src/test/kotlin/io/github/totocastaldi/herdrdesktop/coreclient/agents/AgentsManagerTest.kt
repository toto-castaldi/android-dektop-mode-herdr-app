package io.github.totocastaldi.herdrdesktop.coreclient.agents

import io.github.totocastaldi.herdrdesktop.coreclient.agents.AgentEventsJson.snapshot
import io.github.totocastaldi.herdrdesktop.coreclient.agents.AgentEventsJson.status
import io.github.totocastaldi.herdrdesktop.coreclient.agents.AgentEventsJson.terminal
import io.github.totocastaldi.herdrdesktop.coreclient.agents.AgentEventsJson.turn
import io.github.totocastaldi.herdrdesktop.coreclient.connection.CoreRpc
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A core that answers `events.subscribe` and turns `terminal.label` into a `labels` event, like herdr-core. */
private class EventsCore(private val snapshot: String) : CoreRpc {
    val events = Channel<String>(Channel.UNLIMITED)
    val calls = mutableListOf<String>()
    val labelWrites = mutableListOf<Pair<String, String>>()

    // Above the seqs the tests emit by hand.
    var seq = 1000L

    override suspend fun call(method: String, params: JsonObject?): JsonElement {
        calls += method
        return when (method) {
            "events.subscribe" -> Json.parseToJsonElement(snapshot)

            "terminal.label" -> {
                val terminal = params!!.getValue("terminal").jsonPrimitive.content
                val seen = params.getValue("labels").jsonObject.getValue("herdr.seen").jsonPrimitive.content
                labelWrites += terminal to seen
                emit(AgentEventsJson.labels(++seq, terminal, seen.toLong()))
                JsonObject(emptyMap())
            }

            else -> error("unexpected $method")
        }
    }

    override fun lines(channel: Int): Flow<String> = events.receiveAsFlow()

    fun emit(line: String) {
        events.trySend(line)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AgentsManagerTest {
    private val sessions = MutableStateFlow<Map<String, CoreRpc>>(emptyMap())
    private val core = EventsCore(
        snapshot(100, terminal("t1", status = "working", turn = 1, seen = 1), terminal("t2", parent = "t1"))
    )

    private fun AgentsManager.stateOf(address: String, machine: String = "m1") =
        agents.value.getValue(machine).first { it.address == address }.state

    @Test
    fun `agents come from the subscription and update without reloading`() = runTest {
        val manager = AgentsManager(sessions, backgroundScope)
        sessions.value = mapOf("m1" to core)
        runCurrent()
        assertEquals(listOf("t1", "t2"), manager.agents.value.getValue("m1").map { it.address })

        core.emit(status(101, "t1", "blocked"))
        runCurrent()
        assertEquals(AgentState.BLOCKED, manager.stateOf("t1"))
        core.emit(status(102, "t1", "done"))
        core.emit(turn(103, "t1", 2))
        runCurrent()

        assertEquals(AgentState.DONE, manager.stateOf("t1"))
        assertEquals(listOf("events.subscribe"), core.calls)
    }

    @Test
    fun `looking at a Done agent writes herdr seen on the core and it becomes Idle`() = runTest {
        val manager = AgentsManager(sessions, backgroundScope)
        sessions.value = mapOf("m1" to core)
        runCurrent()
        core.emit(status(101, "t1", "done"))
        core.emit(turn(102, "t1", 2))
        runCurrent()
        assertEquals(AgentState.DONE, manager.stateOf("t1"))

        manager.setLooking("m1", setOf("t1"))
        runCurrent()

        assertEquals(listOf("t1" to "2"), core.labelWrites)
        assertEquals(AgentState.IDLE, manager.stateOf("t1"))
    }

    @Test
    fun `while on screen the label is written once per turn, again after looking away and back`() = runTest {
        val manager = AgentsManager(sessions, backgroundScope)
        sessions.value = mapOf("m1" to core)
        runCurrent()
        manager.setLooking("m1", setOf("t1"))
        core.emit(status(101, "t1", "done"))
        core.emit(turn(102, "t1", 2))
        runCurrent()
        // Another client marks it unread: the screen does not overrule it.
        core.emit(AgentEventsJson.labels(2000, "t1", 1))
        core.seq = 2000
        runCurrent()
        assertEquals(AgentState.DONE, manager.stateOf("t1"))
        assertEquals(listOf("t1" to "2"), core.labelWrites)

        manager.setLooking("m1", emptySet())
        manager.setLooking("m1", setOf("t1"))
        runCurrent()

        assertEquals(listOf("t1" to "2", "t1" to "2"), core.labelWrites)
        assertEquals(AgentState.IDLE, manager.stateOf("t1"))
    }

    @Test
    fun `agents not on screen are left Done`() = runTest {
        val manager = AgentsManager(sessions, backgroundScope)
        sessions.value = mapOf("m1" to core)
        runCurrent()
        manager.setLooking("m1", setOf("t2"))
        core.emit(status(101, "t1", "done"))
        core.emit(turn(102, "t1", 2))
        runCurrent()

        // t2 is on screen without the label, so it joins with its turn; t1 is not touched.
        assertEquals(listOf("t2" to "0"), core.labelWrites)
        assertEquals(AgentState.DONE, manager.stateOf("t1"))
    }

    @Test
    fun `a machine without session has no agents and a new session subscribes again`() = runTest {
        val manager = AgentsManager(sessions, backgroundScope)
        sessions.value = mapOf("m1" to core)
        runCurrent()

        sessions.value = emptyMap()
        runCurrent()
        assertTrue(manager.agents.value.isEmpty())

        val again = EventsCore(snapshot(1, terminal("t1", status = "done", turn = 2, seen = 1)))
        sessions.value = mapOf("m1" to again)
        runCurrent()
        assertEquals(listOf("events.subscribe"), again.calls)
        assertEquals(AgentState.DONE, manager.stateOf("t1"))
    }

    @Test
    fun `the desktop app's agents are not shown and never marked as seen`() = runTest {
        val mixed = EventsCore(
            snapshot(
                1,
                terminal("t1", status = "done", turn = 2, seen = 1),
                terminal("t5", status = "done", turn = 2, seen = 1, space = null)
            )
        )
        val manager = AgentsManager(sessions, backgroundScope)
        sessions.value = mapOf("m1" to mixed)
        runCurrent()

        manager.setLooking("m1", setOf("t1", "t5"))
        runCurrent()

        assertEquals(listOf("t1"), manager.agents.value.getValue("m1").map { it.address })
        assertEquals(listOf("t1" to "2"), mixed.labelWrites)
    }
}
