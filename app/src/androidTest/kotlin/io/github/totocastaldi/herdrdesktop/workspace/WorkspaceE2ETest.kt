package io.github.totocastaldi.herdrdesktop.workspace

import android.app.ActivityOptions
import android.content.Intent
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.totocastaldi.herdrdesktop.HerdrApp
import io.github.totocastaldi.herdrdesktop.MainActivity
import io.github.totocastaldi.herdrdesktop.R
import io.github.totocastaldi.herdrdesktop.coreclient.connection.CoreRpc
import io.github.totocastaldi.herdrdesktop.coreclient.connection.MachineStatus
import io.github.totocastaldi.herdrdesktop.coreclient.connection.status
import io.github.totocastaldi.herdrdesktop.coreclient.machine.Machine
import io.github.totocastaldi.herdrdesktop.coreclient.machine.MachineAuth
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.Workspace
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.WorkspaceClient
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.WorkspaceCodec
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.WorkspaceNames
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.WorkspaceState
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spaces and tabs through the app's UI against a real herdr-core (REQ-WS-001):
 * `-e wsHost <host> -e wsUser <user> [-e wsPort <port>]`; skipped without them. The test key `e2e-workspace`
 * (public part written to `workspace-e2e.pub` in the app's external files directory) must be authorized on
 * the host, ideally with a forced command that reaches a separate core session (`HC_SESSION`), so that the
 * user's own core is not touched. The test removes the terminals it started and empties the document.
 * `-e wsDisplay <id>` opens the app on that display (Desktop Mode); `-e wsPauseMillis <ms>` holds each step
 * on screen, for screenshots; `-e wsDeleteKey true` deletes the test key at the end.
 */
@RunWith(AndroidJUnit4::class)
class WorkspaceE2ETest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val args = InstrumentationRegistry.getArguments()
    private val host: String? = args.getString("wsHost")
    private val user: String? = args.getString("wsUser")
    private val port = args.getString("wsPort")?.toInt() ?: Machine.DEFAULT_PORT
    private val pause = args.getString("wsPauseMillis")?.toLong() ?: 0L
    private val display = args.getString("wsDisplay")?.toInt()
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HerdrApp
    private val container get() = app.container
    private var scenario: ActivityScenario<MainActivity>? = null
    private var machine: Machine? = null
    private val spaces = E2eSpaces()

    /** True when the state must stay on the core and in the app for the next phase. */
    private var keep = false
    private val restartFile get() = File(app.getExternalFilesDir(null), "workspace-restart.txt")

    @After
    fun cleanUp() {
        machine?.takeUnless { keep }?.let { m ->
            container.connections.sessions.value[m.id]?.let { rpc ->
                runBlocking { spaces.cleanUp(rpc) }
            }
            container.connections.forget(m.id)
            container.machines.remove(m.id)
        }
        scenario?.close()
        if (args.getString("wsDeleteKey") == "true") {
            container.keys.delete(KEY_ALIAS)
            File(app.getExternalFilesDir(null), "workspace-e2e.pub").delete()
        }
    }

    @Test
    fun createRenameReorderAndCloseSpacesAndTabsOnTheCore() {
        assumeTrue("wsHost and wsUser not given", host != null && user != null)
        val target = connect()
        assertTrue("the e2e core must start with no spaces", workspace(target).spaces.isEmpty())

        // Create: one space with one tab and a shell; unnamed, it shows the folder of its terminal.
        rule.onNodeWithText(text(R.string.workspace_new_space)).performClick()
        awaitWorkspace(target) { it.spaces.size == 1 }
        val first = workspace(target).spaces.single()
        val firstTerminal = first.tabs.single().terminals.single()
        val home = terminals(target).getValue(firstTerminal)
        rule.onNodeWithTag("space-0").assertTextContains(WorkspaceNames.folderName(home))
        step()

        // A new tab starts in the folder of the active terminal.
        runBlocking { rpc(target).call("terminal.send", sendParams(firstTerminal, "cd /tmp")) }
        rule.waitUntil(TIMEOUT_MILLIS) { terminals(target)[firstTerminal] == "/tmp" }
        container.workspaces.refresh(target.id)
        rule.waitUntil(TIMEOUT_MILLIS) { (state(target) as? WorkspaceState.Ready)?.cwdOf(firstTerminal) == "/tmp" }
        rule.onNodeWithText(text(R.string.workspace_new_tab)).performClick()
        awaitWorkspace(target) { it.spaces.single().tabs.size == 2 }
        val secondTerminal = workspace(target).spaces.single().tabs[1].terminals.single()
        assertEquals("/tmp", terminals(target)[secondTerminal])
        step()

        // Rename space and tab; move the second tab first.
        rename("space-0-menu", "Progetto")
        awaitWorkspace(target) { it.spaces.single().name == "Progetto" }
        rename("tab-1-menu", "logs")
        awaitWorkspace(target) { it.spaces.single().tabs[1].name == "logs" }
        menu("tab-1-menu", R.string.workspace_move_left)
        awaitWorkspace(target) { it.spaces.single().tabs.map { t -> t.name } == listOf("logs", "") }
        step()

        // A second space, moved above the first.
        rule.onNodeWithText(text(R.string.workspace_new_space)).performClick()
        awaitWorkspace(target) { it.spaces.size == 2 }
        menu("space-1-menu", R.string.workspace_move_up)
        awaitWorkspace(target) { it.spaces[1].name == "Progetto" }
        step()

        // Closing a tab ends its terminal on the core.
        rule.onNodeWithTag("space-1").performClick()
        menu("tab-0-menu", R.string.workspace_close)
        rule.onAllNodesWithText(text(R.string.workspace_close)).onLast().performClick()
        awaitWorkspace(target) { it.spaces[1].tabs.size == 1 }
        rule.waitUntil(TIMEOUT_MILLIS) { secondTerminal !in terminals(target) }
        assertTrue(firstTerminal in terminals(target))

        // Closing the connection and connecting again restores everything from the core.
        val before = workspace(target)
        container.connections.disconnect(target.id)
        rule.waitUntil(TIMEOUT_MILLIS) { state(target) == WorkspaceState.Unavailable }
        rule.onNodeWithText(text(R.string.workspace_unavailable)).assertExists()
        assertTrue("terminals keep running", firstTerminal in terminalsWith(target, reconnect = true))
        rule.waitUntil(TIMEOUT_MILLIS) { (state(target) as? WorkspaceState.Ready)?.workspace == before }
        rule.onNodeWithText("Progetto").assertExists()
        step()

        // Closing a space ends the terminals of its tabs.
        menu("space-1-menu", R.string.workspace_close)
        rule.onAllNodesWithText(text(R.string.workspace_close)).onLast().performClick()
        awaitWorkspace(target) { it.spaces.size == 1 }
        rule.waitUntil(TIMEOUT_MILLIS) { firstTerminal !in terminals(target) }
        assertFalse(rule.onAllNodesWithText("Progetto").fetchSemanticsNodes().isNotEmpty())
    }

    /**
     * `-e wsRestart setup`, then the app process is stopped (`am force-stop`, or simply the next
     * instrumentation, which starts a new process), then `-e wsRestart verify`: spaces, tabs and running
     * terminals come back from the core in the new process.
     */
    @Test
    fun spacesTabsAndTerminalsAreRestoredAfterTheAppRestarts() {
        val phase = args.getString("wsRestart")
        assumeTrue("wsHost, wsUser and wsRestart not given", host != null && user != null && phase != null)
        when (phase) {
            "setup" -> {
                val target = connect()
                rule.onNodeWithText(text(R.string.workspace_new_space)).performClick()
                awaitWorkspace(target) { it.spaces.size == 1 }
                rename("space-0-menu", RESTART_SPACE)
                rule.onNodeWithText(text(R.string.workspace_new_tab)).performClick()
                awaitWorkspace(target) { it.spaces.single().let { s -> s.name == RESTART_SPACE && s.tabs.size == 2 } }
                restartFile.writeText(workspace(target).spaces.single().tabs.flatMap { it.terminals }.joinToString(","))
                step()
                keep = true
            }

            "verify" -> {
                val target = container.machines.machines.value.first { it.name == NAME }
                machine = target
                val expected = restartFile.readText().split(",")
                launch()
                rule.waitUntil(TIMEOUT_MILLIS) { state(target) is WorkspaceState.Ready }
                open()
                val space = (state(target) as WorkspaceState.Ready).workspace.spaces.single()
                spaces.own(space.id)
                assertEquals(RESTART_SPACE, space.name)
                assertEquals(expected, space.tabs.flatMap { it.terminals })
                assertTrue(terminals(target).keys.containsAll(expected))
                rule.onNodeWithText(RESTART_SPACE).assertExists()
                step()
                restartFile.delete()
            }

            else -> error("unknown wsRestart $phase")
        }
    }

    private fun launch() {
        val intent = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = display?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it).toBundle() }
        scenario = ActivityScenario.launch(intent, options)
    }

    private fun open() {
        rule.onNode(
            hasText(text(R.string.machine_open)) and hasAnyAncestor(hasAnyDescendant(hasText(NAME)))
        ).performClick()
        rule.onNodeWithText(text(R.string.workspace_spaces)).assertExists()
    }

    private fun connect(): Machine {
        launch()
        val key = container.keys.publicKey(KEY_ALIAS) ?: container.keys.generate(KEY_ALIAS)
        File(app.getExternalFilesDir(null), "workspace-e2e.pub").writeText(key.authorizedKeysLine() + "\n")
        val auth = MachineAuth.Key(KEY_ALIAS)
        val added = container.machines.add(NAME, checkNotNull(host), port, checkNotNull(user), auth)
        machine = added
        container.connections.connect(added.id)
        rule.waitUntil(TIMEOUT_MILLIS) { container.connections.status(added.id) is MachineStatus.HostKeyUnconfirmed }
        val presented = (container.connections.status(added.id) as MachineStatus.HostKeyUnconfirmed).presented
        container.connections.confirmHostKey(added.id, presented)
        rule.waitUntil(TIMEOUT_MILLIS) { state(added) is WorkspaceState.Ready }
        spaces.record(rpc(added))
        open()
        return added
    }

    private fun rename(menuTag: String, name: String) {
        menu(menuTag, R.string.workspace_rename)
        rule.onNode(hasSetTextAction()).performTextReplacement(name)
        rule.onNodeWithText(text(R.string.save)).performClick()
    }

    private fun menu(menuTag: String, item: Int) {
        rule.onNodeWithTag(menuTag).performClick()
        rule.onNodeWithText(text(item)).performClick()
    }

    private fun step() {
        rule.waitForIdle()
        if (pause > 0) Thread.sleep(pause)
    }

    private fun text(id: Int) = app.getString(id)

    private fun state(m: Machine) = container.workspaces.state(m.id).value

    private fun rpc(m: Machine): CoreRpc = checkNotNull(container.connections.sessions.value[m.id])

    /** The document as it is on the core now. */
    private fun workspace(m: Machine): Workspace = runBlocking { WorkspaceClient(rpc(m)).document.load().workspace }

    /** Live terminals on the core now, with their current folder. */
    private fun terminals(m: Machine): Map<String, String> =
        runBlocking { WorkspaceClient(rpc(m)).terminals.list().mapValues { it.value.cwd } }

    /** Connects again and lists the live terminals. */
    private fun terminalsWith(m: Machine, reconnect: Boolean): Map<String, String> {
        if (reconnect) container.connections.connect(m.id)
        rule.waitUntil(TIMEOUT_MILLIS) { container.connections.sessions.value[m.id] != null }
        return terminals(m)
    }

    private fun awaitWorkspace(m: Machine, done: (Workspace) -> Boolean) {
        rule.waitUntil(TIMEOUT_MILLIS) {
            val shown = (state(m) as? WorkspaceState.Ready)?.workspace
            shown != null && done(shown) && done(workspace(m))
        }
    }

    private fun sendParams(terminal: String, line: String) = buildJsonObject {
        put("terminal", terminal)
        put("text", line)
    }

    private companion object {
        const val NAME = "workspace-e2e"
        const val RESTART_SPACE = "Riapertura"
        const val KEY_ALIAS = "e2e-workspace"
        const val TIMEOUT_MILLIS = 40_000L
    }
}
