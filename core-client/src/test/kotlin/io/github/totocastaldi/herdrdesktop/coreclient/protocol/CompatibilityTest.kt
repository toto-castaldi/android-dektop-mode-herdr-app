package io.github.totocastaldi.herdrdesktop.coreclient.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibilityTest {
    private val ok = HelloResult(
        generation = 1,
        version = "0.1.22",
        framed = true,
        methods = listOf("hello", "server.status", "terminal.list")
    )

    @Test
    fun `matching core is compatible`() {
        assertEquals(Compatibility.Compatible, CoreProtocol.check(ok))
    }

    @Test
    fun `newer generation asks to update the app`() {
        assertEquals(Compatibility.AppUpdateNeeded(2), CoreProtocol.check(ok.copy(generation = 2)))
    }

    @Test
    fun `old version asks to update the core, with found and required version`() {
        assertEquals(
            Compatibility.CoreUpdateNeeded("0.1.10", "0.1.11"),
            CoreProtocol.check(ok.copy(version = "0.1.10"))
        )
        assertEquals(
            Compatibility.CoreUpdateNeeded("0.1.10-rc2", CoreProtocol.MIN_CORE_VERSION),
            CoreProtocol.check(ok.copy(version = "0.1.10-rc2"))
        )
    }

    @Test
    fun `minimum version is compatible`() {
        assertEquals(Compatibility.Compatible, CoreProtocol.check(ok.copy(version = CoreProtocol.MIN_CORE_VERSION)))
        assertEquals(Compatibility.Compatible, CoreProtocol.check(ok.copy(version = "0.2.0")))
    }

    @Test
    fun `older generation, missing method or no framed mode ask to update the core`() {
        assertEquals(
            Compatibility.CoreUpdateNeeded("0.1.22", missing = Compatibility.Missing.Generation(0)),
            CoreProtocol.check(ok.copy(generation = 0))
        )
        assertEquals(
            Compatibility.CoreUpdateNeeded("0.1.22", missing = Compatibility.Missing.Methods(listOf("server.status"))),
            CoreProtocol.check(ok.copy(methods = listOf("hello")))
        )
        assertEquals(
            Compatibility.CoreUpdateNeeded("0.1.22", missing = Compatibility.Missing.Framed),
            CoreProtocol.check(ok.copy(framed = false))
        )
    }

    @Test
    fun `another hello error asks to update the core without a known version`() {
        val error = RpcError(-32601, "method not found", "method_not_found")
        assertEquals(
            Compatibility.CoreUpdateNeeded(null, missing = Compatibility.Missing.HelloError("method_not_found")),
            CoreProtocol.checkError(error)
        )
    }

    @Test
    fun `unsupported_generation error means the app must be updated`() {
        val error = RpcError(-32000, "protocol generation 1 not supported", "unsupported_generation")
        assertEquals(Compatibility.AppUpdateNeeded(null), CoreProtocol.checkError(error))
    }

    @Test
    fun `versions compare numerically`() {
        assertTrue(CoreProtocol.compareVersions("0.1.20", "0.1.11") > 0)
        assertTrue(CoreProtocol.compareVersions("0.1.9", "0.1.11") < 0)
        assertEquals(0, CoreProtocol.compareVersions("0.1.11-rc1", "0.1.11"))
    }

    @Test
    fun `parses a real hello result and ignores unknown fields`() {
        val text = """{"core":{"id":"10","name":"walle"},"features":["framed"],"framed":true,"generation":1,
            "methods":["hello","server.status"],"version":"0.1.20","extra":42}"""
        val hello = CoreProtocol.parseHello(Json.parseToJsonElement(text).jsonObject)
        assertEquals("walle", hello.core?.name)
        assertEquals(Compatibility.Compatible, CoreProtocol.check(hello))
    }
}
