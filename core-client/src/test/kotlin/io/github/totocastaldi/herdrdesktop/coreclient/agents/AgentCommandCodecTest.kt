package io.github.totocastaldi.herdrdesktop.coreclient.agents

import io.github.totocastaldi.herdrdesktop.coreclient.workspace.Axis
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCommandCodecTest {
    private fun command(json: String) = AgentCommandCodec.command(AgentCommandCodec.request(json)!!)

    private fun req(action: String, args: String) = """{"id":"1","action":"$action","args":$args}"""

    private fun failure(json: String) = assertThrows(AgentCommandFailure::class.java) { command(json) }.kind

    @Test
    fun `a request needs a JSON object with a string id`() {
        assertNull(AgentCommandCodec.request(null))
        assertNull(AgentCommandCodec.request(""))
        assertNull(AgentCommandCodec.request("not json"))
        assertNull(AgentCommandCodec.request("""{"action":"space.new"}"""))
        assertNull(AgentCommandCodec.request("""{"id":7,"action":"space.new"}"""))
        assertEquals("a1", AgentCommandCodec.request("""{"id":"a1","action":"space.new"}""")!!.id)
    }

    @Test
    fun `the supported actions take the arguments of herdr-app do`() {
        assertEquals(
            AgentCommand.SpaceNew("Web", "/srv", listOf("claude", "--resume")),
            command(
                """{"id":"1","action":"space.new","args":{"name":"Web","folder":"/srv","argv":["claude","--resume"]}}"""
            )
        )
        assertEquals(AgentCommand.SpaceNew("", null, emptyList()), command("""{"id":"1","action":"space.new"}"""))
        assertEquals(
            AgentCommand.TabNew("", "sp1", null, listOf("htop")),
            command("""{"id":"1","action":"tab.new","args":{"space":"sp1","argv":"[\"htop\"]","folder":null}}""")
        )
        assertEquals(
            AgentCommand.PaneSplit(Axis.COLUMN, "p3", emptyList()),
            command("""{"id":"1","action":"pane.split","args":{"direction":"down","pane":"p3"}}""")
        )
        assertEquals(AgentCommand.SpaceSelect(null, 2), command(req("space.select", """{"index":2}""")))
        assertEquals(AgentCommand.TabSelect(null, 1), command(req("tab.select", """{"index":"1"}""")))
        assertEquals(AgentCommand.TabSelect("tb1", null), command(req("tab.select", """{"id":"tb1"}""")))
        assertEquals(AgentCommand.AgentFocus("t4"), command(req("agent.focus", """{"terminal":"t4"}""")))
    }

    @Test
    fun `only selection and focus need a window`() {
        assertTrue(command("""{"id":"1","action":"agent.focus","args":{"terminal":"t4"}}""").needsWindow)
        assertTrue(command("""{"id":"1","action":"space.select","args":{"id":"sp1"}}""").needsWindow)
        assertEquals(false, command("""{"id":"1","action":"space.new"}""").needsWindow)
    }

    @Test
    fun `other actions are unsupported and wrong arguments are invalid`() {
        assertEquals("unsupported", failure("""{"id":"1","action":"tab.close","args":{"confirmed":true}}"""))
        assertEquals("unsupported", failure("""{"id":"1","action":"terminal.kill","args":{"terminal":"t1"}}"""))
        assertEquals("invalid_args", failure("""{"id":"1"}"""))
        assertEquals("invalid_args", failure("""{"id":"1","action":"space.new","args":{"machine":"x"}}"""))
        assertEquals("invalid_args", failure("""{"id":"1","action":"space.new","args":["x"]}"""))
        assertEquals("invalid_args", failure("""{"id":"1","action":"pane.split"}"""))
        assertEquals("invalid_args", failure("""{"id":"1","action":"pane.split","args":{"direction":"left"}}"""))
        assertEquals("invalid_args", failure("""{"id":"1","action":"tab.new","args":{"argv":[1]}}"""))
        assertEquals("invalid_args", failure("""{"id":"1","action":"tab.new","args":{"space":3}}"""))
        assertEquals("invalid_args", failure("""{"id":"1","action":"space.select","args":{}}"""))
        assertEquals("invalid_args", failure("""{"id":"1","action":"space.select","args":{"index":0}}"""))
        assertEquals("invalid_args", failure("""{"id":"1","action":"space.select","args":{"id":"sp1","index":1}}"""))
        assertEquals("invalid_args", failure("""{"id":"1","action":"agent.focus"}"""))
    }

    @Test
    fun `answers start with the id so that the agent's recipe finds them`() {
        val ok = AgentCommandCodec.ok("a1", buildJsonObject { put("space", "sp1") })
        assertTrue(ok.startsWith("""{"id":"a1""""))
        assertEquals("sp1", Json.parseToJsonElement(ok).jsonObject["result"]!!.jsonObject["space"].toString().trim('"'))
        val error = AgentCommandCodec.error("a2", "unsupported", "azione non supportata")
        assertEquals("""{"id":"a2","ok":false,"error":"unsupported","message":"azione non supportata"}""", error)
        assertEquals("a2", AgentCommandCodec.answeredId(error))
        assertNull(AgentCommandCodec.answeredId("garbage"))
    }

    @Test
    fun `only known action names are logged`() {
        assertEquals("space.new", AgentCommandCodec.loggable("space.new"))
        assertEquals("other", AgentCommandCodec.loggable("rm -rf /secret"))
        assertEquals("other", AgentCommandCodec.loggable(null))
    }
}
