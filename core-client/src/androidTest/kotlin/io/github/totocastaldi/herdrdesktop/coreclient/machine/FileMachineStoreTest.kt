package io.github.totocastaldi.herdrdesktop.coreclient.machine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.totocastaldi.herdrdesktop.coreclient.hostkey.HostKey
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileMachineStoreTest {
    @Test
    fun machinesSurviveANewRepositoryOnTheSameFile() {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "machines-test.json")
        file.delete()
        val first = MachineRepository(FileMachineStore(file))
        val m = first.add("vps", "vps.example.org", 2222, "toto", MachineAuth.Key("k"))
        first.confirmHostKey(m.id, HostKey("ssh-ed25519", byteArrayOf(1, 2, 3)))

        assertEquals(first.machines.value, MachineRepository(FileMachineStore(file)).machines.value)
        file.delete()
    }
}
