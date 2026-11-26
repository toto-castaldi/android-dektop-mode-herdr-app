package io.github.totocastaldi.herdrdesktop

import android.app.ActivityOptions
import android.content.Intent
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.totocastaldi.herdrdesktop.coreclient.connection.MachineStatus
import io.github.totocastaldi.herdrdesktop.coreclient.connection.status
import io.github.totocastaldi.herdrdesktop.coreclient.install.CoreOperation
import io.github.totocastaldi.herdrdesktop.coreclient.machine.Machine
import io.github.totocastaldi.herdrdesktop.coreclient.machine.MachineAuth
import io.github.totocastaldi.herdrdesktop.coreclient.protocol.Compatibility
import io.github.totocastaldi.herdrdesktop.machines.AndroidLocalNetworkAccess
import io.github.totocastaldi.herdrdesktop.machines.CORE_OUTPUT_TAG
import io.github.totocastaldi.herdrdesktop.machines.machineRowTag
import io.github.totocastaldi.herdrdesktop.machines.machineStatusTag
import java.io.File
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real installation and update of herdr-core through the app's UI (REQ-CONN-003, REQ-CONN-004), on a host
 * prepared by hand: `-e coreHost <host> -e coreUser <user> [-e corePort <port>]
 * -e coreCase install|update|fail|available|nomanifest`;
 * skipped without them. The test key `e2e-core-install` (public part written to `core-e2e.pub` in the
 * app's external files directory) must be authorized on the host, ideally with a forced command that
 * gives it an isolated `HOME` and `HC_SESSION`; the host must then have no core (`install`, `fail` with
 * an unreachable release server), a core older than the app's minimum (`update`), a compatible core older
 * than the published one (`available`), or a compatible core on a host where `curl` cannot read the
 * manifest (`nomanifest`). `-e coreDeleteKey true`
 * deletes the test key at the end; `-e coreDisplay <id>` opens the app on that display (Desktop Mode) and
 * `-e corePauseMillis <ms>` holds the confirmation dialog and the final state on screen, for screenshots.
 */
@RunWith(AndroidJUnit4::class)
class CoreInstallE2ETest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val args = InstrumentationRegistry.getArguments()
    private val host: String? = args.getString("coreHost")
    private val user: String? = args.getString("coreUser")
    private val port = args.getString("corePort")?.toInt() ?: Machine.DEFAULT_PORT
    private val case: String? = args.getString("coreCase")
    private val pause = args.getString("corePauseMillis")?.toLong() ?: 0L
    private val display = args.getString("coreDisplay")?.toInt()
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HerdrApp
    private val container get() = app.container
    private var scenario: ActivityScenario<MainActivity>? = null
    private val seen = Collections.synchronizedList(mutableListOf<MachineStatus>())
    private val watcher = CoroutineScope(Dispatchers.Default)
    private var machine: Machine? = null

    @After
    fun cleanUp() {
        composeRule.waitForIdle()
        Thread.sleep(pause)
        watcher.cancel()
        scenario?.close()
        machine?.let {
            container.connections.forget(it.id)
            container.machines.remove(it.id)
        }
        if (args.getString("coreDeleteKey") == "true") container.keys.delete(KEY_ALIAS)
    }

    @Test
    fun installsOrUpdatesTheCoreAfterConfirmation() {
        assumeTrue("coreHost, coreUser and coreCase not given", host != null && user != null && case != null)
        val intent = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = display?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it).toBundle() }
        scenario = ActivityScenario.launch(intent, options)
        val target = addMachine()
        when (case) {
            "install" -> install(target)
            "update" -> update(target)
            "fail" -> failedInstall(target)
            "available" -> availableUpdate(target)
            "nomanifest" -> noManifest(target)
            else -> error("unknown coreCase $case")
        }
    }

    private fun install(target: Machine) {
        awaitStatus(target) { it is MachineStatus.CoreMissing }
        assertEquals(MachineStatus.CoreMissing(), status(target))
        composeRule.onNodeWithText("herdr-core assente").assertExists()
        assertNothingRunsWithoutConfirmation(target)

        confirm("Installa herdr-core", "Installare herdr-core su ${target.name}?")
        awaitStatus(target) { it is MachineStatus.Connected || it is MachineStatus.CoreInstallFailed }
        assertTrue("got ${status(target)}", status(target) is MachineStatus.Connected)
        assertTrue(seen.any { it is MachineStatus.CoreInstalling && it.operation == CoreOperation.INSTALL })
        composeRule.onNodeWithText("Connessa", substring = true).assertExists()
    }

    private fun update(target: Machine) {
        awaitStatus(target) { it is MachineStatus.CoreIncompatible }
        val needed = (status(target) as MachineStatus.CoreIncompatible).compatibility
        assertTrue("got $needed", needed is Compatibility.CoreUpdateNeeded && needed.foundVersion != null)
        needed as Compatibility.CoreUpdateNeeded
        composeRule.onNodeWithText(
            "Core incompatibile: trovata ${needed.foundVersion}, richiesta ${needed.requiredVersion} o successiva"
        ).assertExists()
        assertNothingRunsWithoutConfirmation(target)

        confirm("Aggiorna herdr-core", "Aggiornare herdr-core su ${target.name}?")
        awaitStatus(target) { it is MachineStatus.Connected || it is MachineStatus.CoreInstallFailed }
        val connected = status(target)
        assertTrue("got $connected", connected is MachineStatus.Connected)
        assertTrue(seen.any { it is MachineStatus.CoreInstalling && it.operation == CoreOperation.UPDATE })
    }

    /** REQ-CONN-004: the older compatible core stays in use until the user confirms the update. */
    private fun availableUpdate(target: Machine) {
        awaitStatus(target) { it is MachineStatus.Connected && it.availableUpdate != null }
        val connected = status(target) as MachineStatus.Connected
        val latest = checkNotNull(connected.availableUpdate)
        statusLine(target).assertTextEquals(
            "Connessa · herdr-core ${connected.coreVersion} · aggiornamento disponibile: $latest"
        )
        assertTrue(target.id in container.connections.sessions.value)
        assertNothingRunsWithoutConfirmation(target)

        confirm("Aggiorna herdr-core", "Aggiornare herdr-core su ${target.name}?")
        awaitStatus(target) { it is MachineStatus.Connected && it.coreVersion == latest }
        assertTrue(seen.any { it is MachineStatus.CoreInstalling && it.operation == CoreOperation.UPDATE })
        assertNoUpdateShown(target)
    }

    /** REQ-CONN-004: a machine that cannot read the manifest connects normally, without warnings. */
    private fun noManifest(target: Machine) {
        awaitStatus(target) { it is MachineStatus.Connected }
        assertNoUpdateShown(target)
    }

    /** After the check had time to run, the core is connected and no update is shown. */
    private fun assertNoUpdateShown(target: Machine) {
        Thread.sleep(IDLE_MILLIS)
        val connected = status(target)
        assertTrue("got $connected", connected is MachineStatus.Connected && connected.availableUpdate == null)
        connected as MachineStatus.Connected
        statusLine(target).assertTextEquals("Connessa · herdr-core ${connected.coreVersion}")
    }

    private fun failedInstall(target: Machine) {
        awaitStatus(target) { it is MachineStatus.CoreMissing }
        confirm("Installa herdr-core", "Installare herdr-core su ${target.name}?")
        awaitStatus(target) { it is MachineStatus.CoreInstallFailed || it is MachineStatus.Connected }
        val failed = status(target)
        assertTrue("got $failed", failed is MachineStatus.CoreInstallFailed && failed.output.contains("hc:"))
        failed as MachineStatus.CoreInstallFailed
        composeRule.onNodeWithText("Installazione di herdr-core non riuscita", substring = true).assertExists()
        composeRule.onNodeWithTag(CORE_OUTPUT_TAG, useUnmergedTree = true)
            .assertTextContains(failed.output.lines().first { it.startsWith("hc:") }, substring = true)
        composeRule.onNodeWithText("Riprova").assertExists()
    }

    private fun addMachine(): Machine {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            app.packageName,
            AndroidLocalNetworkAccess.PERMISSION
        )
        // A run killed halfway leaves its machine behind: remove it so that only this run's machine is shown.
        container.machines.machines.value.filter { it.name == MACHINE_NAME }.forEach {
            container.connections.forget(it.id)
            container.machines.remove(it.id)
        }
        val key = container.keys.publicKey(KEY_ALIAS) ?: container.keys.generate(KEY_ALIAS)
        File(app.getExternalFilesDir(null), "core-e2e.pub").writeText(key.authorizedKeysLine() + "\n")
        val added = container.machines.add(
            MACHINE_NAME,
            checkNotNull(host),
            port,
            checkNotNull(user),
            MachineAuth.Key(KEY_ALIAS)
        )
        machine = added
        watcher.launch { container.connections.statuses.collect { all -> all[added.id]?.let(seen::add) } }
        container.connections.connect(added.id)
        awaitStatus(added) { it is MachineStatus.HostKeyUnconfirmed }
        container.connections.confirmHostKey(added.id, (status(added) as MachineStatus.HostKeyUnconfirmed).presented)
        return added
    }

    /** The core stays missing or old for a while: nothing is installed before the user confirms. */
    private fun assertNothingRunsWithoutConfirmation(target: Machine) {
        val before = status(target)
        Thread.sleep(IDLE_MILLIS)
        assertEquals(before, status(target))
        assertTrue(seen.none { it is MachineStatus.CoreInstalling })
    }

    /** Presses [button] in the card of [machine], other machines may show the same one, then confirms. */
    private fun confirm(button: String, title: String) {
        val row = hasAnyAncestor(hasTestTag(machineRowTag(checkNotNull(machine).id)))
        composeRule.onNode(hasText(button) and row).performScrollTo().performClick()
        composeRule.onNodeWithText(title).assertExists()
        Thread.sleep(pause)
        composeRule.onAllNodesWithText(button).onLast().performClick()
    }

    private fun status(target: Machine) = container.connections.status(target.id)

    private fun statusLine(target: Machine) = composeRule.onNodeWithTag(machineStatusTag(target.id))

    private fun awaitStatus(target: Machine, done: (MachineStatus) -> Boolean) {
        composeRule.waitUntil(TIMEOUT_MILLIS) { done(status(target)) || status(target).isFinalFailure() }
        assertTrue("unexpected ${status(target)}", done(status(target)))
    }

    private fun MachineStatus.isFinalFailure() =
        this == MachineStatus.AuthFailed || this is MachineStatus.HostKeyChanged

    private companion object {
        const val KEY_ALIAS = "e2e-core-install"
        const val MACHINE_NAME = "core-e2e"
        const val TIMEOUT_MILLIS = 180_000L
        const val IDLE_MILLIS = 5_000L
    }
}
