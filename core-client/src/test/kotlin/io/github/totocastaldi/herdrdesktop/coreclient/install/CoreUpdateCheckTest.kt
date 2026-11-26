package io.github.totocastaldi.herdrdesktop.coreclient.install

import io.github.totocastaldi.herdrdesktop.coreclient.machine.Machine
import io.github.totocastaldi.herdrdesktop.coreclient.machine.MachineAuth
import io.github.totocastaldi.herdrdesktop.coreclient.protocol.CoreProtocol
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading latest.json and comparing versions (REQ-CONN-004). */
class CoreUpdateCheckTest {
    private val machine = Machine("m1", "vps", "vps.example.org", 22, "toto", MachineAuth.Key("k"))

    private val manifest = """
        {"version":"0.1.22","generation":1,"notes":"…",
         "targets":{"linux-aarch64":{"url":"https://releases.herdr.dev/core/0.1.22/x.tar.gz","sha256":"98"}}}
    """.trimIndent()

    @Test
    fun `reads the version of a real manifest`() {
        assertEquals("0.1.22", CoreUpdateCheck.parseLatestVersion(manifest))
        assertEquals("0.2.0", CoreUpdateCheck.parseLatestVersion("""{"version":"0.2.0"}"""))
        assertEquals("1.0.0-rc1", CoreUpdateCheck.parseLatestVersion("""{"version":"1.0.0-rc1","generation":1}"""))
    }

    @Test
    fun `a malformed manifest has no version`() {
        listOf(
            "",
            "not json",
            "<html>404</html>",
            """{"version":""",
            "[]",
            "\"0.1.22\"",
            "{}",
            """{"version":22}""",
            """{"version":null}""",
            """{"version":{"name":"0.1.22"}}""",
            """{"version":"latest"}""",
            """{"version":"0.1.22; rm -rf /"}""",
            """{"version":"0.1.22","generation":"one"}"""
        ).forEach { assertNull(it, CoreUpdateCheck.parseLatestVersion(it)) }
    }

    @Test
    fun `a manifest for another protocol generation is ignored`() {
        val next = CoreProtocol.GENERATION + 1
        assertNull(CoreUpdateCheck.parseLatestVersion("""{"version":"0.3.0","generation":$next}"""))
    }

    @Test
    fun `only a newer version is an update`() {
        assertEquals("0.1.22", CoreUpdateCheck.newerVersion("0.1.11", manifest))
        assertEquals("0.1.22", CoreUpdateCheck.newerVersion("0.1.9", manifest))
        assertNull(CoreUpdateCheck.newerVersion("0.1.22", manifest))
        assertNull(CoreUpdateCheck.newerVersion("0.1.23", manifest))
        assertNull(CoreUpdateCheck.newerVersion("0.2.0", manifest))
        assertNull(CoreUpdateCheck.newerVersion("0.1.22-rc1", manifest))
        assertEquals("0.10.0", CoreUpdateCheck.newerVersion("0.9.9", """{"version":"0.10.0"}"""))
    }

    @Test
    fun `the manifest is read by the machine and failures mean no update`() = runTest {
        val commands = mutableListOf<String>()
        fun check(result: CommandResult) = CoreUpdateCheck(
            CommandRunner { _, command, _ ->
                commands += command
                result
            }
        )

        assertEquals("0.1.22", check(CommandResult.Exited(0, manifest)).availableUpdate(machine, "0.1.20"))
        assertNull(check(CommandResult.Exited(22, "curl: (22) 404")).availableUpdate(machine, "0.1.20"))
        assertNull(check(CommandResult.Exited(127, "sh: curl: not found")).availableUpdate(machine, "0.1.20"))
        assertNull(check(CommandResult.Exited(null, manifest)).availableUpdate(machine, "0.1.20"))
        assertNull(check(CommandResult.NotRun("unreachable")).availableUpdate(machine, "0.1.20"))
        assertTrue(commands.all { it == CoreUpdateCheck.LATEST_COMMAND })
        assertTrue(CoreUpdateCheck.LATEST_COMMAND.contains("https://releases.herdr.dev/core/latest.json"))
    }
}
