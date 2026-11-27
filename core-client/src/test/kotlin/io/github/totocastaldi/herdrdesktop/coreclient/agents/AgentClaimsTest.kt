package io.github.totocastaldi.herdrdesktop.coreclient.agents

import io.github.totocastaldi.herdrdesktop.coreclient.workspace.FakeCore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentClaimsTest {
    private val core = FakeCore()

    private fun claims() = core.docValue(AgentClaims.DOC_NAME).toString()

    @Test
    fun `a request is claimed once, also by two devices`() = runTest {
        assertTrue(AgentClaims(core).claim("t1", "a1", setOf("t1")))
        assertFalse(AgentClaims(core).claim("t1", "a1", setOf("t1")))
        assertTrue(AgentClaims(core).claim("t1", "a2", setOf("t1")))
        assertEquals("""{"t1":"a2"}""", claims())
    }

    @Test
    fun `a conflict reads again and sees the other device's claim`() = runTest {
        core.beforeNextDocSet = { core.write(AgentClaims.DOC_NAME, Json.parseToJsonElement("""{"t1":"a1"}""")) }

        assertFalse(AgentClaims(core).claim("t1", "a1", setOf("t1")))
        assertEquals("""{"t1":"a1"}""", claims())
    }

    @Test
    fun `a conflict on another terminal keeps both claims`() = runTest {
        core.beforeNextDocSet = { core.write(AgentClaims.DOC_NAME, Json.parseToJsonElement("""{"t2":"b1"}""")) }

        assertTrue(AgentClaims(core).claim("t1", "a1", setOf("t1", "t2")))
        assertEquals("""{"t2":"b1","t1":"a1"}""", claims())
    }

    @Test
    fun `claims of terminals that are gone are dropped`() = runTest {
        core.write(AgentClaims.DOC_NAME, Json.parseToJsonElement("""{"t1":"a1","t9":"z"}"""))

        assertTrue(AgentClaims(core).claim("t2", "b1", setOf("t1", "t2")))
        assertEquals("""{"t1":"a1","t2":"b1"}""", claims())
    }
}
