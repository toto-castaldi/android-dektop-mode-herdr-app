package io.github.totocastaldi.herdrdesktop.coreclient.ssh

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.totocastaldi.herdrdesktop.coreclient.connection.OpenResult
import io.github.totocastaldi.herdrdesktop.coreclient.hostkey.HostKeyDecision
import io.github.totocastaldi.herdrdesktop.coreclient.keys.AndroidKeystoreSecretCipher
import io.github.totocastaldi.herdrdesktop.coreclient.keys.AndroidKeystoreSshKeys
import io.github.totocastaldi.herdrdesktop.coreclient.machine.LocalMachine
import io.github.totocastaldi.herdrdesktop.coreclient.machine.MachineAuth
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real SSH to the local machine (127.0.0.1:2222, the port the Linux Terminal forwards), from an app
 * UID. Run with `-Pandroid.testInstrumentationRunnerArguments.e2eLocal=running` while the Terminal is
 * open and its VM configured, or `=stopped` while the Terminal is closed; skipped otherwise.
 */
@RunWith(AndroidJUnit4::class)
class LocalMachineE2ETest {
    private val mode: String? = InstrumentationRegistry.getArguments().getString("e2eLocal")
    private val alias = "e2e-local-unauthorized-key"
    private val keys = AndroidKeystoreSshKeys()
    private val transport =
        SshlibTransport(keys, AndroidKeystoreSecretCipher("e2e-secret-cipher"), connectTimeoutMillis = 10_000)

    @After
    fun cleanUp() = keys.delete(alias)

    private fun local() = LocalMachine.create("e2e").copy(auth = MachineAuth.Key(alias))

    @Test
    fun runningTerminalPresentsTheVmHostKeyThenRefusesAnUnknownKey() = runBlocking {
        assumeTrue("e2eLocal=running not given", mode == "running")
        keys.generate(alias)

        val first = transport.open(local())
        assertTrue(first.toString(), first is OpenResult.HostKeyRejected)
        val presented = ((first as OpenResult.HostKeyRejected).decision as HostKeyDecision.Unconfirmed).presented

        assertEquals(OpenResult.AuthFailed, transport.open(local().copy(hostKey = presented)))
    }

    @Test
    fun closedTerminalRefusesTheConnection() = runBlocking {
        assumeTrue("e2eLocal=stopped not given", mode == "stopped")
        val result = transport.open(local())
        assertTrue(result.toString(), result is OpenResult.Unreachable && result.refused)
    }
}
