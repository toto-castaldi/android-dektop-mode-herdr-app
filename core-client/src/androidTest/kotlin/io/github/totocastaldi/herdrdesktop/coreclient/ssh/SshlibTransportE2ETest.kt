package io.github.totocastaldi.herdrdesktop.coreclient.ssh

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.totocastaldi.herdrdesktop.coreclient.connection.OpenResult
import io.github.totocastaldi.herdrdesktop.coreclient.hostkey.HostKey
import io.github.totocastaldi.herdrdesktop.coreclient.hostkey.HostKeyDecision
import io.github.totocastaldi.herdrdesktop.coreclient.keys.AndroidKeystoreSecretCipher
import io.github.totocastaldi.herdrdesktop.coreclient.keys.AndroidKeystoreSshKeys
import io.github.totocastaldi.herdrdesktop.coreclient.machine.Machine
import io.github.totocastaldi.herdrdesktop.coreclient.machine.MachineAuth
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real SSH against a host given with `-Pandroid.testInstrumentationRunnerArguments.e2eHost=<host>`
 * (and optionally `e2eUser`); skipped without it. Uses a fresh Keystore key that the host does not authorize.
 */
@RunWith(AndroidJUnit4::class)
class SshlibTransportE2ETest {
    private val args = InstrumentationRegistry.getArguments()
    private val host: String? = args.getString("e2eHost")
    private val user: String = args.getString("e2eUser") ?: "herdr-e2e"
    private val alias = "e2e-unauthorized-key"
    private val keys = AndroidKeystoreSshKeys()
    private val transport =
        SshlibTransport(keys, AndroidKeystoreSecretCipher("e2e-secret-cipher"), connectTimeoutMillis = 10_000)

    @After
    fun cleanUp() = keys.delete(alias)

    private fun machine(hostKey: HostKey? = null, target: String = checkNotNull(host)) =
        Machine("e2e", "e2e", target, 22, user, MachineAuth.Key(alias), hostKey)

    @Test
    fun unknownHostKeyIsRejectedThenChangedKeyIsBlockedThenUnauthorizedKeyFails() = runBlocking {
        assumeTrue("e2eHost not given", host != null)
        keys.generate(alias)

        val first = transport.open(machine())
        assertTrue(first.toString(), first is OpenResult.HostKeyRejected)
        val presented = ((first as OpenResult.HostKeyRejected).decision as HostKeyDecision.Unconfirmed).presented

        val forged =
            HostKey(presented.type, presented.blob.copyOf().also { it[it.size - 1] = (it.last() + 1).toByte() })
        val changed = transport.open(machine(forged))
        assertEquals(OpenResult.HostKeyRejected(HostKeyDecision.Changed(forged, presented)), changed)

        assertEquals(OpenResult.AuthFailed, transport.open(machine(presented)))
    }

    @Test
    fun unroutableHostIsUnreachable() = runBlocking {
        assumeTrue("e2eHost not given", host != null)
        val result = transport.open(machine(target = "192.0.2.1"))
        assertTrue(result.toString(), result is OpenResult.Unreachable)
    }
}
