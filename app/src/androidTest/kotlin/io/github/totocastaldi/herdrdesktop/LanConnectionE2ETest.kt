package io.github.totocastaldi.herdrdesktop

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.totocastaldi.herdrdesktop.coreclient.connection.MachineStatus
import io.github.totocastaldi.herdrdesktop.coreclient.connection.status
import io.github.totocastaldi.herdrdesktop.coreclient.keys.SshKeyStore
import io.github.totocastaldi.herdrdesktop.coreclient.machine.Machine
import io.github.totocastaldi.herdrdesktop.coreclient.machine.MachineAuth
import io.github.totocastaldi.herdrdesktop.machines.AndroidLocalNetworkAccess
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real connection to herdr-core on a LAN host (BUG-001), with `-e lanHost <host> -e lanUser <user>`; skipped
 * without them. The app key must be authorized on the host: the test writes its public key (only that) to
 * `lan-e2e.pub` in the app's external files directory, to be pulled with adb. With `-e grant false` and a
 * host outside the local network (Tailscale, internet) it proves that no permission is needed.
 */
@RunWith(AndroidJUnit4::class)
class LanConnectionE2ETest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val args = InstrumentationRegistry.getArguments()
    private val host: String? = args.getString("lanHost")
    private val user: String? = args.getString("lanUser")

    /** `-e grant false` keeps the permission as it is: for hosts that must connect without it (Tailscale). */
    private val grant = args.getString("grant") != "false"
    private val container get() = (composeRule.activity.application as HerdrApp).container
    private var machine: Machine? = null

    @After
    fun cleanUp() {
        machine?.let {
            container.connections.forget(it.id)
            container.machines.remove(it.id)
        }
    }

    @Test
    fun connectsToTheCoreOfALanHost() {
        assumeTrue("lanHost and lanUser not given", host != null && user != null)
        if (grant) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                composeRule.activity.packageName,
                AndroidLocalNetworkAccess.PERMISSION
            )
        }
        val key =
            container.keys.publicKey(SshKeyStore.DEFAULT_ALIAS) ?: container.keys.generate(SshKeyStore.DEFAULT_ALIAS)
        File(composeRule.activity.getExternalFilesDir(null), "lan-e2e.pub").writeText(key.authorizedKeysLine() + "\n")

        val lan = container.machines.add(
            "lan-e2e",
            checkNotNull(host),
            22,
            checkNotNull(user),
            MachineAuth.Key(SshKeyStore.DEFAULT_ALIAS)
        )
        machine = lan
        container.connections.connect(lan.id)
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            container.connections.status(lan.id) is MachineStatus.HostKeyUnconfirmed
        }
        val presented = (container.connections.status(lan.id) as MachineStatus.HostKeyUnconfirmed).presented
        container.connections.confirmHostKey(lan.id, presented)

        composeRule.waitUntil(TIMEOUT_MILLIS) {
            when (container.connections.status(lan.id)) {
                is MachineStatus.Connected, MachineStatus.AuthFailed, MachineStatus.CoreMissing,
                is MachineStatus.CoreIncompatible, MachineStatus.LocalNetworkPermissionNeeded -> true

                else -> false
            }
        }
        val status = container.connections.status(lan.id)
        assertTrue(
            "expected Connected, got $status (authorize lan-e2e.pub on the host)",
            status is MachineStatus.Connected
        )
    }

    private companion object {
        const val TIMEOUT_MILLIS = 40_000L
    }
}
