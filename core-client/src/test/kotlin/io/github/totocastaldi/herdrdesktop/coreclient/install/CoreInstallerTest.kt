package io.github.totocastaldi.herdrdesktop.coreclient.install

import io.github.totocastaldi.herdrdesktop.coreclient.machine.Machine
import io.github.totocastaldi.herdrdesktop.coreclient.machine.MachineAuth
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreInstallerTest {
    private val machine = Machine("m1", "vps", "vps.example.org", 22, "toto", MachineAuth.Key("k"))

    @Test
    fun `a supported machine with curl and tar misses nothing`() {
        for (arch in listOf("x86_64", "amd64", "arm64", "aarch64")) {
            assertEquals(
                emptyList<MissingPrerequisite>(),
                CoreInstaller.parsePrerequisites("Linux\n$arch\ncurl=yes\ntar=yes\n")
            )
        }
        assertEquals(
            emptyList<MissingPrerequisite>(),
            CoreInstaller.parsePrerequisites("Darwin\narm64\ncurl=yes\ntar=yes")
        )
    }

    @Test
    fun `unsupported system, architecture and missing commands are all reported`() {
        assertEquals(
            listOf(
                MissingPrerequisite.UnsupportedSystem("FreeBSD"),
                MissingPrerequisite.UnsupportedArchitecture("riscv64"),
                MissingPrerequisite.MissingCommand("curl"),
                MissingPrerequisite.MissingCommand("tar")
            ),
            CoreInstaller.parsePrerequisites("FreeBSD\nriscv64\ncurl=no\ntar=no\n")
        )
    }

    @Test
    fun `unexpected output does not block the installer`() {
        assertTrue(CoreInstaller.parsePrerequisites("").isEmpty())
        assertTrue(CoreInstaller.parsePrerequisites("Linux").isEmpty())
    }

    @Test
    fun `a check that fails or cannot run does not block the installer`() = runTest {
        val failing = CoreInstaller { _, _, _ -> CommandResult.Exited(2, "Linux\nriscv64\n") }
        assertTrue(failing.missingPrerequisites(machine).isEmpty())
        val notRun = CoreInstaller { _, _, _ -> CommandResult.NotRun("unreachable") }
        assertTrue(notRun.missingPrerequisites(machine).isEmpty())
    }

    @Test
    fun `install and update run their own commands and stream the output`() = runTest {
        val commands = mutableListOf<String>()
        val installer = CoreInstaller { _, command, onOutput ->
            commands += command
            onOutput("out")
            CommandResult.Exited(0, "out")
        }
        val seen = mutableListOf<String>()
        installer.run(machine, CoreOperation.INSTALL) { seen += it }
        installer.run(machine, CoreOperation.UPDATE) { seen += it }
        assertEquals(listOf(CoreInstaller.INSTALL_COMMAND, CoreInstaller.UPDATE_COMMAND), commands)
        assertEquals(listOf("out", "out"), seen)
    }

    @Test
    fun `commands match the design`() {
        assertTrue(CoreInstaller.INSTALL_COMMAND.contains("curl -fsSL https://releases.herdr.dev/core/install.sh"))
        assertTrue(CoreInstaller.UPDATE_COMMAND.contains("\$HOME/.local/bin/herdr-core"))
        assertTrue(CoreInstaller.UPDATE_COMMAND.endsWith("exec \"\$p\" update'"))
    }
}
