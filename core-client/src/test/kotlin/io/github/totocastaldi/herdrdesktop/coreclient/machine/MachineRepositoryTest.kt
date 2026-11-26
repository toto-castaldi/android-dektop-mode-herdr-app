package io.github.totocastaldi.herdrdesktop.coreclient.machine

import io.github.totocastaldi.herdrdesktop.coreclient.hostkey.HostKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MachineRepositoryTest {
    private class MemoryStore : MachineStore {
        var saved: String? = null

        override fun load(): List<Machine> = saved?.let(MachineCodec::decode).orEmpty()

        override fun save(machines: List<Machine>) {
            saved = MachineCodec.encode(machines)
        }
    }

    private val store = MemoryStore()
    private var next = 0
    private fun repo() = MachineRepository(store) { "m${next++}" }

    @Test
    fun `machines persist across a restart`() {
        val first = repo()
        val vps = first.add(" vps ", "vps.example.org", 2222, "toto", MachineAuth.Key("k"))
        first.add("box", "10.0.0.2", 22, "me", MachineAuth.Password(byteArrayOf(1, 2, 3)))
        first.confirmHostKey(vps.id, HostKey("ssh-ed25519", byteArrayOf(7)))

        val restarted = repo().machines.value

        assertEquals(first.machines.value, restarted)
        assertEquals("vps", restarted[0].name)
        assertEquals(HostKey("ssh-ed25519", byteArrayOf(7)), restarted[0].hostKey)
    }

    @Test
    fun `update keeps the host key only for the same endpoint`() {
        val repo = repo()
        val m = repo.add("vps", "vps.example.org", 22, "toto", MachineAuth.Key("k"))
        repo.confirmHostKey(m.id, HostKey("ssh-ed25519", byteArrayOf(7)))

        repo.update(m.copy(name = "renamed", user = "other"))
        assertEquals("renamed", repo.get(m.id)?.name)
        assertTrue(repo.get(m.id)?.hostKey != null)

        repo.update(checkNotNull(repo.get(m.id)).copy(host = "new.example.org"))
        assertNull(repo.get(m.id)?.hostKey)
    }

    @Test
    fun `remove deletes the machine from the store`() {
        val repo = repo()
        val m = repo.add("vps", "h", 22, "u", MachineAuth.Key("k"))
        repo.remove(m.id)
        assertTrue(repo().machines.value.isEmpty())
    }

    @Test
    fun `stored json holds the sealed password only as base64`() {
        repo().add("box", "h", 22, "u", MachineAuth.Password("opaque-cipher".toByteArray()))
        assertFalse(checkNotNull(store.saved).contains("opaque-cipher"))
        assertEquals("Password(***)", MachineAuth.Password(byteArrayOf(1)).toString())
    }

    @Test
    fun `draft validation reports the invalid fields`() {
        assertEquals(emptyList<MachineField>(), MachineDraft("vps", "vps.example.org", "22", "toto").validate())
        assertEquals(
            listOf(MachineField.NAME, MachineField.HOST, MachineField.PORT, MachineField.USER),
            MachineDraft(" ", "a b", "70000", "").validate()
        )
    }
}
