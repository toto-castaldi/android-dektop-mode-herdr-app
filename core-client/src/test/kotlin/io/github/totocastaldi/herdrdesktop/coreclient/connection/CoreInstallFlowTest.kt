package io.github.totocastaldi.herdrdesktop.coreclient.connection

import io.github.totocastaldi.herdrdesktop.coreclient.hostkey.HostKey
import io.github.totocastaldi.herdrdesktop.coreclient.install.CommandResult
import io.github.totocastaldi.herdrdesktop.coreclient.install.CommandRunner
import io.github.totocastaldi.herdrdesktop.coreclient.install.CoreInstaller
import io.github.totocastaldi.herdrdesktop.coreclient.install.CoreOperation
import io.github.totocastaldi.herdrdesktop.coreclient.install.MissingPrerequisite
import io.github.totocastaldi.herdrdesktop.coreclient.machine.Machine
import io.github.totocastaldi.herdrdesktop.coreclient.machine.MachineAuth
import io.github.totocastaldi.herdrdesktop.coreclient.machine.MachineRepository
import io.github.totocastaldi.herdrdesktop.coreclient.machine.MachineStore
import io.github.totocastaldi.herdrdesktop.coreclient.protocol.Compatibility
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Installing and updating herdr-core through [ConnectionManager] (REQ-CONN-003). */
@OptIn(ExperimentalCoroutinesApi::class)
class CoreInstallFlowTest {
    private class MemoryStore : MachineStore {
        var list = emptyList<Machine>()

        override fun load() = list

        override fun save(machines: List<Machine>) {
            list = machines
        }
    }

    private val repo = MachineRepository(MemoryStore()) { "m1" }
    private val machine = repo.add("vps", "vps.example.org", 22, "toto", MachineAuth.Key("k")).also {
        repo.confirmHostKey(it.id, HostKey("ssh-ed25519", byteArrayOf(1)))
    }

    /** Streams handed out in order; when they run out the machine is unreachable. */
    private class Streams(vararg streams: () -> CoreStream) : CoreTransport {
        private val queue = streams.toMutableList()
        var opened = 0

        override suspend fun open(machine: Machine): OpenResult {
            val next = queue.removeFirstOrNull() ?: return OpenResult.Unreachable("no route")
            opened++
            return OpenResult.Opened(next())
        }
    }

    /**
     * Records every command and answers with [answer]. The output is reported before the command ends,
     * which waits for [gate] if set.
     */
    private class Commands(private val answer: (String) -> CommandResult) : CommandRunner {
        val run = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun run(machine: Machine, command: String, onOutput: (String) -> Unit): CommandResult {
            run += command
            val result = answer(command)
            if (result is CommandResult.Exited && result.output.isNotEmpty()) onOutput(result.output)
            gate?.takeIf { command != CoreInstaller.PREREQUISITES_COMMAND }?.await()
            return result
        }
    }

    private val missing = { FakeCoreStream(helloReply = null, exitStatus = CORE_NOT_FOUND_EXIT) }
    private val old = {
        FakeCoreStream(
            helloReply = """{"jsonrpc":"2.0","id":1,"result":{"framed":true,"generation":1,""" +
                """"methods":["hello","server.status"],"version":"0.1.10"}}"""
        )
    }
    private val current = { FakeCoreStream() }

    private val prerequisitesOk = CommandResult.Exited(0, "Linux\nx86_64\ncurl=yes\ntar=yes\n")

    private fun TestScope.manager(transport: CoreTransport, commands: CommandRunner) = ConnectionManager(
        repo,
        transport,
        backgroundScope,
        ConnectionPolicy(1_000, 8_000, 30_000, 10_000),
        commands = commands
    )

    @Test
    fun `missing core is reported after the prerequisite check, and nothing is installed without confirmation`() =
        runTest {
            val commands = Commands { prerequisitesOk }
            val manager = manager(Streams(missing), commands)

            manager.connect(machine.id)
            advanceTimeBy(120_000)
            runCurrent()

            assertEquals(MachineStatus.CoreMissing(), manager.status(machine.id))
            assertEquals(listOf(CoreInstaller.PREREQUISITES_COMMAND), commands.run)
        }

    @Test
    fun `missing prerequisites are reported and the installer cannot be started`() = runTest {
        val commands = Commands { CommandResult.Exited(0, "Linux\nriscv64\ncurl=no\ntar=yes\n") }
        val manager = manager(Streams(missing), commands)

        manager.connect(machine.id)
        runCurrent()

        val expected = listOf(
            MissingPrerequisite.UnsupportedArchitecture("riscv64"),
            MissingPrerequisite.MissingCommand("curl")
        )
        assertEquals(MachineStatus.CoreMissing(expected), manager.status(machine.id))
        val refused = runCatching { manager.installCore(machine.id) }
        assertTrue(refused.exceptionOrNull() is IllegalArgumentException)
        assertEquals(listOf(CoreInstaller.PREREQUISITES_COMMAND), commands.run)
    }

    @Test
    fun `confirmed installation shows progress, then connects to the new core`() = runTest {
        val commands = Commands {
            if (it == CoreInstaller.INSTALL_COMMAND) CommandResult.Exited(0, "hc 0.1.22 installed") else prerequisitesOk
        }
        val transport = Streams(missing, current)
        val manager = manager(transport, commands)
        val gate = CompletableDeferred<Unit>().also { commands.gate = it }

        manager.connect(machine.id)
        runCurrent()
        manager.installCore(machine.id)
        runCurrent()
        assertEquals(
            MachineStatus.CoreInstalling(CoreOperation.INSTALL, "hc 0.1.22 installed"),
            manager.status(machine.id)
        )

        gate.complete(Unit)
        runCurrent()

        assertEquals(listOf(CoreInstaller.PREREQUISITES_COMMAND, CoreInstaller.INSTALL_COMMAND), commands.run)
        assertEquals(MachineStatus.Connected("vps", "0.1.22"), manager.status(machine.id))
        assertEquals(2, transport.opened)
    }

    @Test
    fun `failed installation shows the output of the command and can be retried`() = runTest {
        val output = "hc: cannot reach https://releases.herdr.dev/core/latest.json"
        val commands = Commands {
            if (it == CoreInstaller.INSTALL_COMMAND) CommandResult.Exited(1, output) else prerequisitesOk
        }
        val transport = Streams(missing)
        val manager = manager(transport, commands)

        manager.connect(machine.id)
        runCurrent()
        manager.installCore(machine.id)
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(MachineStatus.CoreInstallFailed(CoreOperation.INSTALL, 1, output), manager.status(machine.id))
        assertEquals(1, transport.opened)

        manager.installCore(machine.id)
        runCurrent()
        assertEquals(2, commands.run.count { it == CoreInstaller.INSTALL_COMMAND })
    }

    @Test
    fun `a command that cannot run is a failure with its reason`() = runTest {
        val commands = Commands {
            if (it == CoreInstaller.INSTALL_COMMAND) CommandResult.NotRun("authentication failed") else prerequisitesOk
        }
        val manager = manager(Streams(missing), commands)

        manager.connect(machine.id)
        runCurrent()
        manager.installCore(machine.id)
        runCurrent()

        assertEquals(
            MachineStatus.CoreInstallFailed(CoreOperation.INSTALL, null, "authentication failed"),
            manager.status(machine.id)
        )
    }

    @Test
    fun `incompatible core reports found and required version and updates only after confirmation`() = runTest {
        val commands = Commands { CommandResult.Exited(0, """{"was":"0.1.10","now":"0.1.22","updated":true}""") }
        val manager = manager(Streams(old, current), commands)

        manager.connect(machine.id)
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(
            MachineStatus.CoreIncompatible(Compatibility.CoreUpdateNeeded("0.1.10", "0.1.11")),
            manager.status(machine.id)
        )
        assertTrue(commands.run.isEmpty())

        manager.updateCore(machine.id)
        runCurrent()

        assertEquals(listOf(CoreInstaller.UPDATE_COMMAND), commands.run)
        assertEquals(MachineStatus.Connected("vps", "0.1.22"), manager.status(machine.id))
    }

    @Test
    fun `a core that needs a newer app cannot be updated, nor a connected one installed`() = runTest {
        val commands = Commands { prerequisitesOk }
        val manager = manager(Streams({ FakeCoreStream(helloReply = FakeCoreStream.HELLO_FUTURE) }, current), commands)

        manager.connect(machine.id)
        runCurrent()
        assertTrue(runCatching { manager.updateCore(machine.id) }.exceptionOrNull() is IllegalArgumentException)

        manager.connect(machine.id)
        runCurrent()
        assertTrue(manager.status(machine.id) is MachineStatus.Connected)
        assertTrue(runCatching { manager.installCore(machine.id) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(commands.run.isEmpty())
    }
}
