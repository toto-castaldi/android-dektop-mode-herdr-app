package io.github.totocastaldi.herdrdesktop.coreclient.machine

import io.github.totocastaldi.herdrdesktop.coreclient.hostkey.HostKey
import io.github.totocastaldi.herdrdesktop.coreclient.keys.SshKeyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMachineTest {
    private class MemoryStore : MachineStore {
        var saved: String? = null

        override fun load(): List<Machine> = saved?.let(MachineCodec::decode).orEmpty()

        override fun save(machines: List<Machine>) {
            saved = MachineCodec.encode(machines)
        }
    }

    private val store = MemoryStore()

    @Test
    fun `local machine is added once, first, with fixed endpoint and the app key`() {
        val repo = MachineRepository(store)
        repo.add("vps", "vps.example.org", 22, "toto", MachineAuth.Key("k"))
        repo.ensureLocal("Questo dispositivo")
        repo.confirmHostKey(LocalMachine.ID, HostKey("ssh-ed25519", byteArrayOf(1)))

        val restarted = MachineRepository(store).also { it.ensureLocal("This device") }
        val local = restarted.machines.value.first()

        assertEquals(2, restarted.machines.value.size)
        assertTrue(local.isLocal)
        assertEquals("Questo dispositivo", local.name)
        assertEquals("droid@127.0.0.1:2222", "${local.user}@${local.host}:${local.port}")
        assertEquals(MachineAuth.Key(SshKeyStore.DEFAULT_ALIAS), local.auth)
        assertEquals(HostKey("ssh-ed25519", byteArrayOf(1)), local.hostKey)
    }

    @Test
    fun `reset forgets only the local host key`() {
        val repo = MachineRepository(store).also { it.ensureLocal("Questo dispositivo") }
        val vps = repo.add("vps", "vps.example.org", 22, "toto", MachineAuth.Key("k"))
        repo.confirmHostKey(LocalMachine.ID, HostKey("ssh-ed25519", byteArrayOf(1)))
        repo.confirmHostKey(vps.id, HostKey("ssh-ed25519", byteArrayOf(2)))

        repo.resetLocalHostKey()

        assertNull(repo.get(LocalMachine.ID)?.hostKey)
        assertEquals(HostKey("ssh-ed25519", byteArrayOf(2)), repo.get(vps.id)?.hostKey)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `local machine cannot be removed`() {
        MachineRepository(store).apply { ensureLocal("Questo dispositivo") }.remove(LocalMachine.ID)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `local machine cannot be edited`() {
        val repo = MachineRepository(store).apply { ensureLocal("Questo dispositivo") }
        repo.update(repo.get(LocalMachine.ID)!!.copy(host = "10.0.0.1"))
    }

    @Test
    fun `setup command configures sshd on 2222 and authorizes the key once`() {
        val line = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIA herdr-desktop-android"
        val command = LocalMachine.setupCommand(line)

        assertTrue(command.contains("apt-get install -y openssh-server"))
        assertTrue(command.contains("echo 'Port 2222' | sudo tee /etc/ssh/sshd_config.d/port.conf"))
        assertTrue(command.contains("systemctl restart ssh"))
        assertTrue(
            command.contains("grep -qxF '$line' ~/.ssh/authorized_keys || echo '$line' >> ~/.ssh/authorized_keys")
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `setup command rejects a key line that would break the quoting`() {
        LocalMachine.setupCommand("ssh-ed25519 AAAA x'; rm -rf ~; '")
    }
}
