package io.github.totocastaldi.herdrdesktop.workspace

import android.app.ActivityOptions
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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
import io.github.totocastaldi.herdrdesktop.WindowTarget
import io.github.totocastaldi.herdrdesktop.coreclient.connection.CoreRpc
import io.github.totocastaldi.herdrdesktop.coreclient.connection.MachineStatus
import io.github.totocastaldi.herdrdesktop.coreclient.connection.status
import io.github.totocastaldi.herdrdesktop.coreclient.keys.SshKeyStore
import io.github.totocastaldi.herdrdesktop.coreclient.machine.Machine
import io.github.totocastaldi.herdrdesktop.coreclient.machine.MachineAuth
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.Axis
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.CoreTerminals
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.PaneLayout
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.Space
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.Workspace
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.WorkspaceCodec
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.WorkspaceState
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.splitPane
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spaces and tabs shared with the desktop app (REQ-WS-001, US-012) on the real core the desktop app is
 * connected to: `-e shHost <host> -e shUser <user> -e shHostKey SHA256:… [-e shPort <port>]
 * [-e shDisplay <id>] [-e shPauseMillis <ms>]`; skipped without them. It uses the app's own key
 * ([SshKeyStore.DEFAULT_ALIAS]), which must reach the core session of the desktop app.
 *
 * The desktop app is observed through the core, without driving its window: it writes on every terminal it
 * places the label `where` = `<space> / <tab>` (an unnamed tab by its position), and it adopts a live
 * terminal labelled `herdr.space`/`herdr.tab` that no pane shows, writing it into the shared document. The
 * test works only in a space it creates, opens the window directly on it (so that no terminal of the user is
 * attached or resized) and closes it at the end; the user's spaces are never changed.
 */
@RunWith(AndroidJUnit4::class)
class SharedWorkspaceE2ETest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val args = InstrumentationRegistry.getArguments()
    private val host: String? = args.getString("shHost")
    private val user: String? = args.getString("shUser")
    private val hostKey: String? = args.getString("shHostKey")
    private val port = args.getString("shPort")?.toInt() ?: Machine.DEFAULT_PORT
    private val pause = args.getString("shPauseMillis")?.toLong() ?: 0L
    private val display = args.getString("shDisplay")?.toInt()
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HerdrApp
    private val container get() = app.container
    private var scenario: ActivityScenario<MainActivity>? = null
    private var machine: Machine? = null
    private val spaces = E2eSpaces()

    /** Terminals the test started on the core outside the app (as an agent or a script would). */
    private val started = mutableListOf<String>()

    @After
    fun cleanUp() {
        machine?.let { m ->
            container.connections.sessions.value[m.id]?.let { rpc ->
                runBlocking {
                    val ended = CoreTerminals(rpc)
                    started.forEach { runCatching { ended.end(it) } }
                    spaces.cleanUp(rpc)
                }
            }
            container.connections.forget(m.id)
            container.machines.remove(m.id)
        }
        scenario?.close()
    }

    @Test
    fun spacesTabsAndPanesChangeInBothApps() {
        assumeTrue("shHost, shUser and shHostKey not given", host != null && user != null && hostKey != null)
        val target = connect()
        val name = "e2e-condiviso-${System.currentTimeMillis() % 100_000}"

        // App → desktop: a new space, placed by the desktop app under its own label.
        container.workspaces.perform(target.id) { createSpace() }
        val space = awaitUntil { workspace(target).spaces.firstOrNull { it.id !in before } }
        spaces.own(space.id)
        val first = space.tabs.single().terminals.single()
        assertEquals(space.id, labels(target, first)[SPACE])
        assertEquals(space.tabs.single().id, labels(target, first)[TAB])
        launch(target, space.id)
        awaitLabel(target, first) { it.endsWith(" / 1") }
        step()

        // Renamed in the app: the desktop app shows the new name.
        rename("space-${index(target, space.id)}-menu", name)
        awaitLabel(target, first) { it == "$name / 1" }
        step()

        // A new tab, renamed and moved first: the desktop app follows the name and the order.
        rule.onNodeWithText(text(R.string.workspace_new_tab)).performClick()
        val second = awaitUntil { ownSpace(target).tabs.getOrNull(1) }
        val secondTerminal = second.terminals.single()
        awaitLabel(target, secondTerminal) { it == "$name / 2" }
        rename("tab-1-menu", "logs")
        awaitLabel(target, secondTerminal) { it == "$name / logs" }
        rule.onNodeWithTag("tab-1-menu").performClick()
        rule.onNodeWithText(text(R.string.workspace_move_left)).performClick()
        awaitLabel(target, first) { it == "$name / 2" }
        step()

        // A pane split in the app is placed by the desktop app in the same tab.
        val pane = PaneLayout.leaves(second.root).single()
        container.workspaces.perform(target.id) { splitPane(space.id, second.id, pane.id, Axis.ROW, null) }
        val split = awaitUntil { ownSpace(target).tab(second.id)?.terminals?.getOrNull(1) }
        assertEquals(second.id, labels(target, split)[TAB])
        awaitLabel(target, split) { it == "$name / logs" }
        step()

        // Desktop → app: a terminal labelled for a new tab of the space is adopted by the desktop app,
        // which writes the tab; the app shows it without being restarted.
        val adoptedTab = "tbe2e${System.currentTimeMillis()}"
        val adopted = runShell(target, space.id, adoptedTab)
        val shown = awaitUntil { readyWorkspace(target)?.space(space.id)?.tab(adoptedTab) }
        assertEquals(listOf(adopted), shown.terminals)
        rule.waitUntil(TIMEOUT_MILLIS) { runCatching { rule.onNodeWithTag("tab-2").fetchSemanticsNode() }.isSuccess }
        step()

        // Desktop → app: a terminal labelled for an existing tab gets a pane split there by the desktop app.
        val firstTab = tabOf(target, first)
        val adoptedPane = runShell(target, space.id, firstTab)
        awaitUntil { readyWorkspace(target)?.space(space.id)?.tab(firstTab)?.terminals?.takeIf { adoptedPane in it } }
        step()

        // Desktop → app: a pane whose terminal ended is closed by the desktop app.
        runBlocking { CoreTerminals(rpc(target)).end(adoptedPane) }
        started.remove(adoptedPane)
        awaitUntil {
            readyWorkspace(target)?.space(space.id)?.tabs?.takeIf { tabs -> tabs.none { adoptedPane in it.terminals } }
        }
        step()

        // App → desktop: closing the tab removes it from the shared document for good, and ends its terminals.
        container.workspaces.perform(target.id) { closeTab(space.id, second.id) }
        awaitUntil { ownSpace(target).takeIf { it.tab(second.id) == null } }
        rule.waitUntil(TIMEOUT_MILLIS) { terminalState(target, secondTerminal) != "live" }
        Thread.sleep(SETTLE_MILLIS)
        assertTrue("the desktop app put the closed tab back", ownSpace(target).tab(second.id) == null)
        step()

        // The user's spaces are untouched.
        val others = workspace(target).spaces.filter { it.id != space.id }
        assertEquals(beforeSpaces, others)
    }

    private var before: Set<String> = emptySet()
    private var beforeSpaces: List<Space> = emptyList()

    private fun connect(): Machine {
        val auth = MachineAuth.Key(SshKeyStore.DEFAULT_ALIAS)
        assertNotNull("the app key must exist", container.keys.publicKey(SshKeyStore.DEFAULT_ALIAS))
        val added = container.machines.add(NAME, checkNotNull(host), port, checkNotNull(user), auth)
        machine = added
        container.connections.connect(added.id)
        rule.waitUntil(TIMEOUT_MILLIS) { container.connections.status(added.id) is MachineStatus.HostKeyUnconfirmed }
        val presented = (container.connections.status(added.id) as MachineStatus.HostKeyUnconfirmed).presented
        assertEquals("host key of $host", hostKey, presented.fingerprint)
        container.connections.confirmHostKey(added.id, presented)
        rule.waitUntil(TIMEOUT_MILLIS) { container.workspaces.state(added.id).value is WorkspaceState.Ready }
        spaces.record(rpc(added))
        beforeSpaces = workspace(added).spaces
        before = beforeSpaces.map { it.id }.toSet()
        return added
    }

    /** Opens a window directly on [spaceId] of [m], as "Nuova finestra" does. */
    private fun launch(m: Machine, spaceId: String) {
        val intent = MainActivity.windowIntent(app, WindowTarget(m.id, spaceId))
        val options = display?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it).toBundle() }
        scenario = ActivityScenario.launch(intent, options)
        rule.onNodeWithText(text(R.string.workspace_spaces)).assertExists()
    }

    private fun runShell(m: Machine, spaceId: String, tabId: String): String = runBlocking {
        val params = buildJsonObject {
            putJsonObject("labels") {
                put(SPACE, spaceId)
                put(TAB, tabId)
                put("herdr.seen", "0")
            }
        }
        val result = rpc(m).call("terminal.run", params) as JsonObject
        (result.getValue("address") as JsonPrimitive).content.also { started += it }
    }

    private fun tabOf(m: Machine, terminal: String): String = ownSpace(m).tabs.first { terminal in it.terminals }.id

    private fun rename(menuTag: String, newName: String) {
        rule.onNodeWithTag(menuTag).performClick()
        rule.onNodeWithText(text(R.string.workspace_rename)).performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement(newName)
        rule.onNodeWithText(text(R.string.save)).performClick()
    }

    /** Position of [spaceId] in the list of spaces, as the screen tags it. */
    private fun index(m: Machine, spaceId: String) = workspace(m).spaces.indexOfFirst { it.id == spaceId }

    private fun awaitLabel(m: Machine, terminal: String, done: (String) -> Boolean) {
        rule.waitUntil(DESKTOP_MILLIS) { labels(m, terminal)[WHERE]?.let(done) == true }
    }

    private fun labels(m: Machine, terminal: String): Map<String, String> = runBlocking {
        val record = rpc(m).call("terminal.get", buildJsonObject { put("terminal", terminal) }) as JsonObject
        (record["labels"] as? JsonObject).orEmpty().mapValues { (it.value as? JsonPrimitive)?.content.orEmpty() }
    }

    private fun terminalState(m: Machine, terminal: String): String? = runCatching {
        runBlocking {
            val record = rpc(m).call("terminal.get", buildJsonObject { put("terminal", terminal) }) as JsonObject
            (record["state"] as? JsonPrimitive)?.content
        }
    }.getOrNull()

    private fun <T : Any> awaitUntil(read: () -> T?): T {
        var value: T? = null
        rule.waitUntil(DESKTOP_MILLIS) {
            value = read()
            value != null
        }
        return checkNotNull(value)
    }

    private fun step() {
        rule.waitForIdle()
        if (pause > 0) Thread.sleep(pause)
    }

    private fun text(id: Int) = app.getString(id)

    private fun rpc(m: Machine): CoreRpc = checkNotNull(container.connections.sessions.value[m.id])

    /** The shared document as it is on the core now. */
    private fun workspace(m: Machine): Workspace = runBlocking {
        val result = rpc(m).call("doc.get", buildJsonObject { put("name", WorkspaceCodec.DOC_NAME) }) as JsonObject
        WorkspaceCodec.decode(result["value"].takeUnless { it == JsonNull })
    }

    private fun ownSpace(m: Machine): Space = checkNotNull(workspace(m).spaces.firstOrNull { it.id !in before })

    /** What the app shows for [m]: the open screen rereads the core every 5 s, nothing else is triggered. */
    private fun readyWorkspace(m: Machine): Workspace? =
        (container.workspaces.state(m.id).value as? WorkspaceState.Ready)?.workspace

    private companion object {
        const val NAME = "e2e-shared"
        const val SPACE = "herdr.space"
        const val TAB = "herdr.tab"
        const val WHERE = "where"
        const val TIMEOUT_MILLIS = 20_000L
        const val DESKTOP_MILLIS = 60_000L
        const val SETTLE_MILLIS = 5_000L
    }
}
