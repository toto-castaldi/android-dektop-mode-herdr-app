package io.github.totocastaldi.herdrdesktop

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.totocastaldi.herdrdesktop.coreclient.connection.MachineStatus
import io.github.totocastaldi.herdrdesktop.coreclient.connection.status
import io.github.totocastaldi.herdrdesktop.coreclient.hostkey.HostKey
import io.github.totocastaldi.herdrdesktop.coreclient.keys.SshKeyStore
import io.github.totocastaldi.herdrdesktop.coreclient.machine.Machine
import io.github.totocastaldi.herdrdesktop.coreclient.machine.MachineAuth
import io.github.totocastaldi.herdrdesktop.machines.AndroidLocalNetworkAccess
import java.io.File
import java.util.regex.Pattern
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * The system dialog of `ACCESS_LOCAL_NETWORK` (BUG-001). Needs the permission not granted at start (a fresh
 * install, as with `connectedDebugAndroidTest`): revoking it from the test would kill the app process.
 * The LAN host defaults to an unused private address; `-e lanHost <host>` uses a real one.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class LocalNetworkPermissionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val lanHost = InstrumentationRegistry.getArguments().getString("lanHost") ?: "192.168.255.254"
    private val container get() = (composeRule.activity.application as HerdrApp).container
    private var machine: Machine? = null

    @After
    fun cleanUp() {
        machine?.let {
            container.connections.forget(it.id)
            container.machines.remove(it.id)
        }
    }

    /** A LAN machine with a made-up confirmed host key: the app connects to it without prompting for the key. */
    private fun connectLanMachine(): Machine {
        val added = container.machines.add(
            "lan-permission-test",
            lanHost,
            22,
            "tester",
            MachineAuth.Key(SshKeyStore.DEFAULT_ALIAS)
        )
        container.machines.confirmHostKey(added.id, HostKey("ssh-ed25519", ByteArray(32) { 7 }))
        machine = added
        container.connections.connect(added.id)
        return added
    }

    private fun waitStatus(id: String, timeoutMillis: Long = 30_000, predicate: (MachineStatus) -> Boolean) {
        composeRule.waitUntil(timeoutMillis) { predicate(container.connections.status(id)) }
    }

    @Test
    fun a_deniedPermissionShowsLocalNetworkNotAllowedWithInstructions() {
        assertFalse("ACCESS_LOCAL_NETWORK must not be granted at start", container.localNetwork.granted())
        val lan = connectLanMachine()
        // The test frame clock moves only while Compose syncs: let the request effect run.
        waitStatus(lan.id) { it == MachineStatus.LocalNetworkPermissionNeeded }
        composeRule.waitForIdle()

        val deny = device.wait(Until.findObject(By.res(Pattern.compile(".*:id/permission_deny_button"))), DIALOG_MILLIS)
        assertNotNull("system permission dialog not shown", deny)
        deny.click()

        waitStatus(lan.id) { it == MachineStatus.LocalNetworkDenied }
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.status_local_network_denied)
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.local_network_open_settings)
        ).assertIsDisplayed()
        // No connection is hanging: the machine stays denied without retrying.
        Thread.sleep(SETTLE_MILLIS)
        assertTrue(container.connections.status(lan.id) == MachineStatus.LocalNetworkDenied)
        screenshot("local-network-denied.png")
    }

    /** The app window (also on an external Desktop Mode display), pulled with adb as evidence. */
    private fun screenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot(composeRule.activity.window) ?: return
        File(composeRule.activity.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it)
        }
    }

    @Test
    fun b_grantedPermissionLetsTheConnectionProceed() {
        instrumentation.uiAutomation.grantRuntimePermission(
            composeRule.activity.packageName,
            AndroidLocalNetworkAccess.PERMISSION
        )
        val lan = connectLanMachine()

        // Past the permission check: connecting, then the made-up host key or the unused address stop it.
        waitStatus(lan.id) {
            it != MachineStatus.LocalNetworkPermissionNeeded && it != MachineStatus.LocalNetworkDenied &&
                it != MachineStatus.Disconnected
        }
        assertFalse(device.hasObject(By.res(Pattern.compile(".*:id/permission_deny_button"))))
    }

    private companion object {
        const val DIALOG_MILLIS = 10_000L
        const val SETTLE_MILLIS = 3_000L
        const val PNG_QUALITY = 100
    }
}
