package io.github.totocastaldi.herdrdesktop.agents

import io.github.totocastaldi.herdrdesktop.coreclient.workspace.PaneLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentWindowRegistryTest {
    private val registry = AgentWindowRegistry(post = { it.run() })

    private class Recorder : AgentWindowActions {
        val done = mutableListOf<String>()

        override fun select(request: WindowSelect) {
            done += "select ${request.spaceId} ${request.tabId}"
        }

        override fun focus(request: PaneFocus) {
            done += "focus ${request.location.paneId}"
        }

        override fun peek(machineId: String, address: String) {
            done += "peek $address"
        }
    }

    private fun window(machine: String?, resumedAt: Long): Pair<AgentWindow, Recorder> {
        val recorder = Recorder()
        val w = registry.register()
        w.actions = recorder
        w.resumedAt = resumedAt
        w.machineId = machine
        return w to recorder
    }

    @Test
    fun `commands go to the window of the machine that was in the foreground last`() {
        val (_, first) = window("m1", 10)
        val (_, second) = window("m1", 20)
        val (_, other) = window("m2", 30)

        assertTrue(registry.select("m1", "sp1", null))
        assertTrue(registry.focus("m1", "t1", PaneLocation("sp1", "tb1", "p1")))
        assertTrue(registry.focus("m1", "t2", null))

        assertEquals(listOf("select sp1 null", "focus p1", "peek t2"), second.done)
        assertTrue(first.done.isEmpty() && other.done.isEmpty())
        assertEquals(setOf("m1", "m2"), registry.machines.value)
    }

    @Test
    fun `without a window of the machine nothing is done`() {
        val (w, recorder) = window("m1", 10)
        assertFalse(registry.select("m2", "sp1", null))

        registry.unregister(w)

        assertFalse(registry.select("m1", "sp1", null))
        assertTrue(recorder.done.isEmpty())
        assertTrue(registry.machines.value.isEmpty())
    }
}
