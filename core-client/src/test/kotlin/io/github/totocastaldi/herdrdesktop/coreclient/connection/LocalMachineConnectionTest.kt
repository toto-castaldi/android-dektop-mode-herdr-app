package io.github.totocastaldi.herdrdesktop.coreclient.connection

import io.github.totocastaldi.herdrdesktop.coreclient.hostkey.HostKey
import io.github.totocastaldi.herdrdesktop.coreclient.hostkey.HostKeyDecision
import io.github.totocastaldi.herdrdesktop.coreclient.hostkey.HostKeyPolicy
import io.github.totocastaldi.herdrdesktop.coreclient.machine.LocalMachine
import io.github.totocastaldi.herdrdesktop.coreclient.machine.Machine
import io.github.totocastaldi.herdrdesktop.coreclient.machine.MachineRepository
import io.github.totocastaldi.herdrdesktop.coreclient.machine.MachineStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalMachineConnectionTest {
    private class MemoryStore : MachineStore {
        var list = emptyList<Machine>()

        override fun load() = list

        override fun save(machines: List<Machine>) {
            list = machines
        }
    }

    private val vmKey = HostKey("ecdsa-sha2-nistp256", byteArrayOf(9, 8, 7))
    private val repo = MachineRepository(MemoryStore()).also { it.ensureLocal("Questo dispositivo") }

    /** Loopback of Android: refuses while [listening] is false, then checks the host key and opens [streams]. */
    private class LoopbackTransport(private val vmKey: HostKey) : CoreTransport {
        var listening = false
        var keyAuthorized = true
        val streams = mutableListOf<CoreStream>()
        val targets = mutableListOf<String>()

        override suspend fun open(machine: Machine): OpenResult {
            targets += "${machine.user}@${machine.host}:${machine.port}"
            val decision = HostKeyPolicy.evaluate(machine.hostKey, vmKey)
            return when {
                !listening -> OpenResult.Unreachable("ConnectException", refused = true)
                decision != HostKeyDecision.Trusted -> OpenResult.HostKeyRejected(decision)
                !keyAuthorized -> OpenResult.AuthFailed
                else -> OpenResult.Opened(streams.removeFirstOrNull() ?: FakeCoreStream())
            }
        }
    }

    private fun TestScope.manager(transport: CoreTransport, terminalInstalled: Boolean = true) = ConnectionManager(
        machines = repo,
        transport = transport,
        scope = backgroundScope,
        policy = ConnectionPolicy(1_000, 8_000, 30_000, 10_000, localStartMillis = 20_000),
        localTerminal = { terminalInstalled }
    )

    @Test
    fun `local machine is reached on the loopback without host or port from the user`() = runTest {
        val transport = LoopbackTransport(vmKey).apply { listening = true }
        val manager = manager(transport)

        manager.connect(LocalMachine.ID)
        runCurrent()
        assertEquals(MachineStatus.HostKeyUnconfirmed(vmKey), manager.status(LocalMachine.ID))

        manager.confirmHostKey(LocalMachine.ID, vmKey)
        runCurrent()
        assertTrue(manager.status(LocalMachine.ID) is MachineStatus.Connected)
        assertEquals(listOf("droid@127.0.0.1:2222", "droid@127.0.0.1:2222"), transport.targets)
    }

    @Test
    fun `missing Linux Terminal stops without connecting`() = runTest {
        val transport = LoopbackTransport(vmKey)
        val manager = manager(transport, terminalInstalled = false)

        manager.connect(LocalMachine.ID)
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(MachineStatus.LocalTerminalMissing, manager.status(LocalMachine.ID))
        assertTrue(transport.targets.isEmpty())
    }

    @Test
    fun `refused connection means the Terminal is not running, then starting, then not configured`() = runTest {
        repo.confirmHostKey(LocalMachine.ID, vmKey)
        val transport = LoopbackTransport(vmKey)
        val manager = manager(transport)

        manager.connect(LocalMachine.ID)
        runCurrent()
        assertEquals(MachineStatus.LocalTerminalNotRunning(1_000), manager.status(LocalMachine.ID))

        manager.localTerminalOpened()
        runCurrent()
        assertEquals(MachineStatus.LocalTerminalStarting(1_000), manager.status(LocalMachine.ID))

        advanceTimeBy(20_001)
        runCurrent()
        assertTrue(manager.status(LocalMachine.ID) is MachineStatus.LocalTerminalNotConfigured)

        transport.listening = true
        advanceTimeBy(8_001)
        runCurrent()
        assertTrue(manager.status(LocalMachine.ID) is MachineStatus.Connected)
    }

    @Test
    fun `Terminal closed after a connection is reported as not running again`() = runTest {
        repo.confirmHostKey(LocalMachine.ID, vmKey)
        val first = FakeCoreStream()
        val transport = LoopbackTransport(vmKey).apply {
            listening = true
            streams += first
        }
        val manager = manager(transport)
        manager.localTerminalOpened()
        runCurrent()
        assertTrue(manager.status(LocalMachine.ID) is MachineStatus.Connected)

        transport.listening = false
        first.drop()
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(MachineStatus.LocalTerminalNotRunning(2_000), manager.status(LocalMachine.ID))
    }

    @Test
    fun `refused app key means not configured, without retrying`() = runTest {
        repo.confirmHostKey(LocalMachine.ID, vmKey)
        val transport = LoopbackTransport(vmKey).apply {
            listening = true
            keyAuthorized = false
        }
        val manager = manager(transport)

        manager.connect(LocalMachine.ID)
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(MachineStatus.LocalTerminalNotConfigured(true, null), manager.status(LocalMachine.ID))
        assertEquals(1, transport.targets.size)
    }

    @Test
    fun `reset VM with a new host key is blocked like any changed key`() = runTest {
        val oldKey = HostKey("ecdsa-sha2-nistp256", byteArrayOf(1))
        repo.confirmHostKey(LocalMachine.ID, oldKey)
        val manager = manager(LoopbackTransport(vmKey).apply { listening = true })

        manager.connect(LocalMachine.ID)
        runCurrent()
        assertEquals(MachineStatus.HostKeyChanged(oldKey, vmKey), manager.status(LocalMachine.ID))

        repo.resetLocalHostKey()
        manager.connect(LocalMachine.ID)
        runCurrent()
        assertEquals(MachineStatus.HostKeyUnconfirmed(vmKey), manager.status(LocalMachine.ID))
    }

    @Test
    fun `refused remote machine stays unreachable`() = runTest {
        val remote = repo.add("vps", "vps.example.org", 22, "toto", repo.get(LocalMachine.ID)!!.auth)
        repo.confirmHostKey(remote.id, vmKey)
        val manager = manager(LoopbackTransport(vmKey))

        manager.connect(remote.id)
        runCurrent()
        assertEquals(MachineStatus.Unreachable("ConnectException", 1_000), manager.status(remote.id))
    }
}
