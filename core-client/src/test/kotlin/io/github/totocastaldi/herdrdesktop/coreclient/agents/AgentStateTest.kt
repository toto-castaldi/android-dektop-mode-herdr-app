package io.github.totocastaldi.herdrdesktop.coreclient.agents

import io.github.totocastaldi.herdrdesktop.coreclient.agents.AgentState.BLOCKED
import io.github.totocastaldi.herdrdesktop.coreclient.agents.AgentState.DONE
import io.github.totocastaldi.herdrdesktop.coreclient.agents.AgentState.IDLE
import io.github.totocastaldi.herdrdesktop.coreclient.agents.AgentState.WORKING
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentStateTest {
    @Test
    fun `working, blocked and error are shown whatever was seen`() {
        assertEquals(WORKING, AgentState.of("working", 3, 3))
        assertEquals(BLOCKED, AgentState.of("blocked", 3, 1))
        assertEquals(BLOCKED, AgentState.of("error", 3, null))
    }

    @Test
    fun `a turn ended after the last look is Done, and looking turns it Idle`() {
        // working at turn 4, seen up to 4; the turn ends: turn 5 > seen 4.
        assertEquals(WORKING, AgentState.of("working", 4, 4))
        assertEquals(DONE, AgentState.of("done", 5, 4))
        // The user looks: herdr.seen=5.
        assertEquals(IDLE, AgentState.of("done", 5, 5))
        // A new turn ends unseen: Done again.
        assertEquals(DONE, AgentState.of("done", 6, 5))
    }

    @Test
    fun `idle reported after an unseen turn is Done until seen`() {
        assertEquals(DONE, AgentState.of("idle", 2, 1))
        assertEquals(IDLE, AgentState.of("idle", 2, 2))
    }

    @Test
    fun `without the seen label a reported done is Done and anything else Idle`() {
        assertEquals(DONE, AgentState.of("done", 7, null))
        assertEquals(IDLE, AgentState.of("idle", 7, null))
    }

    @Test
    fun `a program that never reported or whose status was cleared is Idle`() {
        assertEquals(IDLE, AgentState.of(null, 3, 0))
    }
}
