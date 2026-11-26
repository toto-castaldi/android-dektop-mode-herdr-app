package io.github.totocastaldi.herdrdesktop.coreclient.connection

import io.github.totocastaldi.herdrdesktop.coreclient.hostkey.HostKey
import io.github.totocastaldi.herdrdesktop.coreclient.hostkey.HostKeyDecision
import io.github.totocastaldi.herdrdesktop.coreclient.hostkey.HostKeyPolicy
import io.github.totocastaldi.herdrdesktop.coreclient.machine.Machine
import io.github.totocastaldi.herdrdesktop.coreclient.machine.MachineAuth
import io.github.totocastaldi.herdrdesktop.coreclient.machine.MachineRepository
import io.github.totocastaldi.herdrdesktop.coreclient.machine.MachineStore
import io.github.totocastaldi.herdrdesktop.coreclient.protocol.Compatibility
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionManagerTest {
    private class MemoryStore : MachineStore {
        var list = emptyList<Machine>()

        override fun load() = list

        override fun save(machines: List<Machine>) {
            list = machines
        }
    }

    private val serverKey = HostKey("ssh-ed25519", byteArrayOf(1, 2, 3))
    private val repo = MachineRepository(MemoryStore()) { "m1" }
    private val machine = repo.add("vps", "vps.example.org", 22, "toto", MachineAuth.Key("k"))

    /** Transport that checks the host key like the real one and hands out scripted streams. */
    private class ScriptedTransport(private val serverKey: HostKey, private val script: MutableList<() -> OpenResult>) :
        CoreTransport {
        val targets = mutableListOf<String>()

        override suspend fun open(machine: Machine): OpenResult {
            targets += "${machine.host}:${machine.port}"
            val decision = HostKeyPolicy.evaluate(machine.hostKey, serverKey)
            if (decision != HostKeyDecision.Trusted) return OpenResult.HostKeyRejected(decision)
            return script.removeFirstOrNull()?.invoke() ?: OpenResult.Unreachable("no route")
        }
    }

    private fun TestScope.manager(transport: CoreTransport) =
        ConnectionManager(repo, transport, backgroundScope, ConnectionPolicy(1_000, 8_000, 30_000, 10_000))

    @Test
    fun `first connection waits for host key confirmation, then connects`() = runTest {
        val stream = FakeCoreStream()
        val transport = ScriptedTransport(serverKey, mutableListOf({ OpenResult.Opened(stream) }))
        val manager = manager(transport)

        manager.connect(machine.id)
        runCurrent()
        assertEquals(MachineStatus.HostKeyUnconfirmed(serverKey), manager.status(machine.id))

        manager.confirmHostKey(machine.id, serverKey)
        runCurrent()
        assertEquals(MachineStatus.Connected("vps", "0.1.22"), manager.status(machine.id))
        assertEquals(serverKey, repo.get(machine.id)?.hostKey)
        assertEquals(listOf("vps.example.org:22", "vps.example.org:22"), transport.targets)
    }

    @Test
    fun `changed host key blocks the connection without retrying`() = runTest {
        val oldKey = HostKey("ssh-ed25519", byteArrayOf(4, 5, 6))
        repo.confirmHostKey(machine.id, oldKey)
        val transport = ScriptedTransport(serverKey, mutableListOf())
        val manager = manager(transport)

        manager.connect(machine.id)
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(MachineStatus.HostKeyChanged(oldKey, serverKey), manager.status(machine.id))
        assertEquals(1, transport.targets.size)
        assertEquals(oldKey, repo.get(machine.id)?.hostKey)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a key that was not presented cannot be confirmed`() = runTest {
        manager(ScriptedTransport(serverKey, mutableListOf())).confirmHostKey(machine.id, serverKey)
    }

    @Test
    fun `auth failure, missing and incompatible core are reported`() = runTest {
        repo.confirmHostKey(machine.id, serverKey)
        val cases = listOf<Pair<() -> OpenResult, MachineStatus>>(
            { OpenResult.AuthFailed } to MachineStatus.AuthFailed,
            { OpenResult.Opened(FakeCoreStream(helloReply = null, exitStatus = 127)) } to MachineStatus.CoreMissing(),
            { OpenResult.Opened(FakeCoreStream(helloReply = FakeCoreStream.HELLO_FUTURE)) } to
                MachineStatus.CoreIncompatible(Compatibility.AppUpdateNeeded(2))
        )
        for ((open, expected) in cases) {
            val manager = manager(ScriptedTransport(serverKey, mutableListOf(open)))
            manager.connect(machine.id)
            runCurrent()
            assertEquals(expected, manager.status(machine.id))
        }
    }

    @Test
    fun `unreachable machine is retried with backoff`() = runTest {
        repo.confirmHostKey(machine.id, serverKey)
        val transport = ScriptedTransport(serverKey, mutableListOf())
        val manager = manager(transport)

        manager.connect(machine.id)
        runCurrent()
        assertEquals(MachineStatus.Unreachable("no route", 1_000), manager.status(machine.id))
        advanceTimeBy(1_001)
        assertEquals(MachineStatus.Unreachable("no route", 2_000), manager.status(machine.id))
        advanceTimeBy(2_001)
        assertEquals(3, transport.targets.size)
    }

    @Test
    fun `dropped connection reconnects by itself`() = runTest {
        repo.confirmHostKey(machine.id, serverKey)
        val first = FakeCoreStream()
        val second = FakeCoreStream()
        val manager =
            manager(
                ScriptedTransport(
                    serverKey,
                    mutableListOf({
                        OpenResult.Opened(first)
                    }, { OpenResult.Opened(second) })
                )
            )

        manager.connect(machine.id)
        runCurrent()
        assertTrue(manager.status(machine.id) is MachineStatus.Connected)

        first.drop()
        runCurrent()
        assertEquals(MachineStatus.Unreachable("connection lost", 1_000), manager.status(machine.id))
        advanceTimeBy(1_001)
        assertTrue(manager.status(machine.id) is MachineStatus.Connected)
    }

    @Test
    fun `a core that stops answering pings is treated as dropped`() = runTest {
        repo.confirmHostKey(machine.id, serverKey)
        val stream = FakeCoreStream(answersPings = false)
        val manager = manager(ScriptedTransport(serverKey, mutableListOf({ OpenResult.Opened(stream) })))

        manager.connect(machine.id)
        runCurrent()
        advanceTimeBy(30_000 + 10_001)
        runCurrent()

        assertTrue(stream.closed)
        assertTrue(manager.status(machine.id) is MachineStatus.Unreachable)
    }

    @Test
    fun `disconnect closes the stream and stops retrying`() = runTest {
        repo.confirmHostKey(machine.id, serverKey)
        val stream = FakeCoreStream()
        val transport = ScriptedTransport(serverKey, mutableListOf({ OpenResult.Opened(stream) }))
        val manager = manager(transport)

        manager.connect(machine.id)
        runCurrent()
        manager.disconnect(machine.id)
        advanceTimeBy(60_000)
        runCurrent()

        assertTrue(stream.closed)
        assertEquals(MachineStatus.Disconnected, manager.status(machine.id))
        assertEquals(1, transport.targets.size)
    }

    /** Permission state of a fake Android: hosts in [localHosts] need it. */
    private class FakeLocalNetwork(var isGranted: Boolean, private val localHosts: Set<String>) : LocalNetworkAccess {
        override suspend fun needsPermission(host: String) = host in localHosts

        override fun granted() = isGranted
    }

    /** The test machine moved to a LAN address, with its host key confirmed. */
    private fun lanMachine(): Machine {
        repo.update(machine.copy(host = "172.27.0.5"))
        repo.confirmHostKey(machine.id, serverKey)
        return checkNotNull(repo.get(machine.id))
    }

    private fun TestScope.lanManager(transport: CoreTransport, access: LocalNetworkAccess) = ConnectionManager(
        repo,
        transport,
        backgroundScope,
        ConnectionPolicy(1_000, 8_000, 30_000, 10_000),
        device = DeviceAccess(localNetwork = access)
    )

    @Test
    fun `local network host without permission asks for it and never opens a connection`() = runTest {
        val lan = lanMachine()
        val transport = ScriptedTransport(serverKey, mutableListOf())
        val access = FakeLocalNetwork(isGranted = false, localHosts = setOf("172.27.0.5"))
        val manager = lanManager(transport, access)

        manager.connect(lan.id)
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(MachineStatus.LocalNetworkPermissionNeeded, manager.status(lan.id))
        assertTrue(transport.targets.isEmpty())
    }

    @Test
    fun `denied permission shows local network access not allowed without retrying`() = runTest {
        val lan = lanMachine()
        val transport = ScriptedTransport(serverKey, mutableListOf())
        val manager = lanManager(transport, FakeLocalNetwork(isGranted = false, localHosts = setOf("172.27.0.5")))

        manager.connect(lan.id)
        runCurrent()
        manager.localNetworkPermissionResult(granted = false)
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(MachineStatus.LocalNetworkDenied, manager.status(lan.id))
        assertTrue(transport.targets.isEmpty())
    }

    @Test
    fun `granted permission connects the machines that were waiting for it`() = runTest {
        val lan = lanMachine()
        val transport = ScriptedTransport(serverKey, mutableListOf({ OpenResult.Opened(FakeCoreStream()) }))
        val access = FakeLocalNetwork(isGranted = false, localHosts = setOf("172.27.0.5"))
        val manager = lanManager(transport, access)

        manager.connect(lan.id)
        runCurrent()
        access.isGranted = true
        manager.localNetworkPermissionResult(granted = true)
        runCurrent()

        assertTrue(manager.status(lan.id) is MachineStatus.Connected)
        assertEquals(listOf("172.27.0.5:22"), transport.targets)
    }

    @Test
    fun `hosts outside the local network connect without the permission`() = runTest {
        repo.confirmHostKey(machine.id, serverKey)
        val transport = ScriptedTransport(serverKey, mutableListOf({ OpenResult.Opened(FakeCoreStream()) }))
        val manager = lanManager(transport, FakeLocalNetwork(isGranted = false, localHosts = setOf("172.27.0.5")))

        manager.connect(machine.id)
        runCurrent()

        assertTrue(manager.status(machine.id) is MachineStatus.Connected)
    }
}
