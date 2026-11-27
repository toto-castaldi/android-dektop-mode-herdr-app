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
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.CoreDocument
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.CoreTerminals
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.PaneLayout
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.Space
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.Workspace
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.WorkspaceCodec
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.WorkspaceEdits
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spaces of the app separate from those of the desktop app (REQ-WS-001, REQ-AGT-001, US-014) on the real core
 * the desktop app is connected to: `-e swHost <host> -e swUser <user> -e swHostKey SHA256:… [-e swPort <port>]
 * [-e swDisplay <id>] [-e swPauseMillis <ms>]`; skipped without them. It uses the app's own key
 * ([SshKeyStore.DEFAULT_ALIAS]), which must reach the core session of the desktop app.
 *
 * The desktop app is observed through the core, without driving its window: it writes on every terminal it
 * shows the label `where` and adopts live terminals labelled `herdr.space`. The test puts in the desktop app's
 * document only one space of its own, made as the app made them before US-014, and checks that it moves to the
 * app; it then works only in spaces it creates and closes them at the end. The user's spaces are never changed.
 */
@RunWith(AndroidJUnit4::class)
class SeparateWorkspaceE2ETest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val args = InstrumentationRegistry.getArguments()
    private val host: String? = args.getString("swHost")
    private val user: String? = args.getString("swUser")
    private val hostKey: String? = args.getString("swHostKey")
    private val port = args.getString("swPort")?.toInt() ?: Machine.DEFAULT_PORT
    private val pause = args.getString("swPauseMillis")?.toLong() ?: 0L
    private val display = args.getString("swDisplay")?.toInt()
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HerdrApp
    private val container get() = app.container
    private var scenario: ActivityScenario<MainActivity>? = null
    private var machine: Machine? = null
    private val spaces = E2eSpaces()

    /** Terminals the test started on the core outside the app. */
    private val started = mutableListOf<String>()

    /** The space the test wrote in the desktop app's document, removed from it at the end if still there. */
    private var seeded: String? = null

    @After
    fun cleanUp() {
        machine?.let { m ->
            container.connections.sessions.value[m.id]?.let { rpc ->
                runBlocking {
                    seeded?.let { id ->
                        runCatching { desktopDocument(rpc).update { ws -> WorkspaceEdits.removeSpace(ws, id) } }
                    }
                    spaces.cleanUp(rpc)
                    val ended = CoreTerminals(rpc)
                    started.forEach { runCatching { ended.end(it) } }
                }
            }
            container.connections.forget(m.id)
            container.machines.remove(m.id)
        }
        scenario?.close()
    }

    @Test
    fun appAndDesktopAppKeepTheirOwnSpaces() {
        assumeTrue("swHost, swUser and swHostKey not given", host != null && user != null && hostKey != null)
        val target = connect()
        val suffix = System.currentTimeMillis() % 100_000

        val oldSpace = moveBack(target, suffix)
        launch(target, oldSpace)
        step()

        // A new space: only in the app's document, with the app's labels; the desktop app does not adopt it.
        container.workspaces.perform(target.id) { createSpace() }
        val space = awaitUntil { readyWorkspace(target)?.spaces?.firstOrNull { it.id != oldSpace && it.id !in before } }
        spaces.own(space.id)
        val first = space.tabs.single().terminals.single()
        val firstLabels = labels(target, first)
        assertEquals(space.id, firstLabels[SPACE])
        assertEquals(APP_VALUE, firstLabels[APP])
        assertFalse(firstLabels.containsKey(DESK_SPACE) || firstLabels.containsKey(DESK_TAB))
        step()

        // Renamed, a new tab and a split pane: nothing of it reaches the desktop app.
        rename("space-${index(target, space.id)}-menu", "e2e-separato-$suffix")
        rule.onNodeWithText(text(R.string.workspace_new_tab)).performClick()
        val second = awaitUntil { ownSpace(target, space.id).tabs.getOrNull(1) }
        val pane = PaneLayout.leaves(second.root).single()
        container.workspaces.perform(target.id) { splitPane(space.id, second.id, pane.id, Axis.ROW, null) }
        awaitUntil { ownSpace(target, space.id).tab(second.id)?.terminals?.getOrNull(1) }
        Thread.sleep(SETTLE_MILLIS)
        val mine = ownSpace(target, space.id).tabs.flatMap { it.terminals }
        assertDesktopKnowsNothingOf(target, setOf(oldSpace, space.id), mine)
        mine.forEach { assertNull("the desktop app shows $it", labels(target, it)[WHERE]) }
        step()

        // The sidebar lists only agents of the app's spaces: neither the desktop app's nor a terminal outside
        // every space that reports its status.
        val outside =
            runShell(target, emptyMap(), line = "printf '\\033]7501;state=working:app=e2e-fuori\\007'; sleep 600")
        Thread.sleep(SETTLE_MILLIS)
        val shown = container.agents.agents.value[target.id].orEmpty().map { it.address }.toSet()
        assertFalse("$outside is in the sidebar", outside in shown)
        val desktopTerminals = desktop(target).spaces.flatMap { s -> s.tabs.flatMap { it.terminals } }.toSet()
        assertTrue(
            "desktop agents in the sidebar: ${shown intersect desktopTerminals}",
            (
                shown intersect
                    desktopTerminals
                ).isEmpty()
        )
        // And the app does not show the desktop app's spaces.
        val desktopSpaces = desktop(target).spaces.map { it.id }.toSet()
        assertTrue(checkNotNull(readyWorkspace(target)).spaces.none { it.id in desktopSpaces })
        step()

        // Closing the tab and the space ends only the app's terminals; the desktop app's document never had them.
        container.workspaces.perform(target.id) { closeTab(space.id, second.id) }
        awaitUntil { ownSpace(target, space.id).takeIf { it.tab(second.id) == null } }
        container.workspaces.perform(target.id) { closeSpace(space.id) }
        awaitUntil { readyWorkspace(target)?.takeIf { it.space(space.id) == null } }
        assertDesktopKnowsNothingOf(target, setOf(oldSpace, space.id), mine)
        step()
    }

    /**
     * Writes in the desktop app's document a space as the app made them before US-014 and checks that it comes
     * back to the app at the next session and leaves the desktop app's document.
     */
    private fun moveBack(target: Machine, suffix: Long): String {
        // A space the app wrote in the desktop app's document before US-014 comes back to the app at the next
        // session, and leaves the desktop app's document.
        val oldSpace = "spe2e$suffix"
        val oldTab = "tbe2e$suffix"
        val oldTerminal = runShell(target, mapOf(APP to APP_VALUE, DESK_SPACE to oldSpace, DESK_TAB to oldTab))
        seeded = oldSpace
        runBlocking {
            desktopDocument(rpc(target)).update { ws ->
                val tab = WorkspaceEdits.singlePaneTab(oldTab, "pe2e$suffix", oldTerminal)
                WorkspaceEdits.addSpace(ws, Space(oldSpace, name = "e2e-vecchio-$suffix", tabs = listOf(tab)))
            }
        }
        spaces.own(oldSpace)
        reconnect(target)
        awaitUntil { readyWorkspace(target)?.space(oldSpace) }
        assertNull(desktop(target).space(oldSpace))
        val moved = labels(target, oldTerminal)
        assertEquals(oldSpace, moved[SPACE])
        assertEquals(oldTab, moved[TAB])
        assertFalse(moved.containsKey(DESK_SPACE) || moved.containsKey(DESK_TAB))
        return oldSpace
    }

    private var before: Set<String> = emptySet()

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
        before = checkNotNull(readyWorkspace(added)).spaces.map { it.id }.toSet()
        return added
    }

    /** A new session of [m], whose first read moves the app's spaces. */
    private fun reconnect(m: Machine) {
        val old = rpc(m)
        container.connections.disconnect(m.id)
        rule.waitUntil(TIMEOUT_MILLIS) { container.connections.sessions.value[m.id] == null }
        container.connections.connect(m.id)
        rule.waitUntil(TIMEOUT_MILLIS) {
            val rpc = container.connections.sessions.value[m.id]
            rpc != null && rpc !== old && container.workspaces.state(m.id).value is WorkspaceState.Ready
        }
    }

    /** Opens a window directly on [spaceId] of [m], as "Nuova finestra" does. */
    private fun launch(m: Machine, spaceId: String) {
        val intent = MainActivity.windowIntent(app, WindowTarget(m.id, spaceId))
        val options = display?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it).toBundle() }
        scenario = ActivityScenario.launch(intent, options)
        rule.onNodeWithText(text(R.string.workspace_spaces)).assertExists()
    }

    private fun runShell(m: Machine, labels: Map<String, String>, line: String? = null): String = runBlocking {
        val params = buildJsonObject {
            line?.let { put("line", it) }
            putJsonObject("labels") { labels.forEach { (k, v) -> put(k, v) } }
        }
        val result = rpc(m).call("terminal.run", params) as JsonObject
        (result.getValue("address") as JsonPrimitive).content.also { started += it }
    }

    private fun assertDesktopKnowsNothingOf(m: Machine, spaceIds: Set<String>, terminals: List<String>) {
        val ws = desktop(m)
        assertTrue("app spaces in herdr-app", ws.spaces.none { it.id in spaceIds })
        assertTrue("app terminals in herdr-app", terminals.none { ws.shows(it) })
    }

    private fun rename(menuTag: String, newName: String) {
        rule.onNodeWithTag(menuTag).performClick()
        rule.onNodeWithText(text(R.string.workspace_rename)).performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement(newName)
        rule.onNodeWithText(text(R.string.save)).performClick()
    }

    /** Position of [spaceId] in the list of spaces, as the screen tags it. */
    private fun index(m: Machine, spaceId: String) =
        checkNotNull(readyWorkspace(m)).spaces.indexOfFirst { it.id == spaceId }

    private fun labels(m: Machine, terminal: String): Map<String, String> = runBlocking {
        val record = rpc(m).call("terminal.get", buildJsonObject { put("terminal", terminal) }) as JsonObject
        (record["labels"] as? JsonObject).orEmpty().mapValues { (it.value as? JsonPrimitive)?.content.orEmpty() }
    }

    private fun <T : Any> awaitUntil(read: () -> T?): T {
        var value: T? = null
        rule.waitUntil(TIMEOUT_MILLIS) {
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

    private fun desktopDocument(rpc: CoreRpc) = CoreDocument(rpc, WorkspaceCodec.DESKTOP_DOC_NAME)

    /** The desktop app's document as it is on the core now. */
    private fun desktop(m: Machine): Workspace = runBlocking {
        val params = buildJsonObject { put("name", WorkspaceCodec.DESKTOP_DOC_NAME) }
        val result = rpc(m).call("doc.get", params) as JsonObject
        WorkspaceCodec.decode(result["value"].takeUnless { it == JsonNull })
    }

    /** The app's document as it is on the core now: [spaceId] in it. */
    private fun ownSpace(m: Machine, spaceId: String): Space = runBlocking {
        checkNotNull(CoreDocument(rpc(m)).load().workspace.space(spaceId))
    }

    /** What the app shows for [m]. */
    private fun readyWorkspace(m: Machine): Workspace? =
        (container.workspaces.state(m.id).value as? WorkspaceState.Ready)?.workspace

    private companion object {
        const val NAME = "e2e-separate"
        const val APP = "app"
        const val APP_VALUE = "herdr-android"
        const val SPACE = "herdr-desktop-android.space"
        const val TAB = "herdr-desktop-android.tab"
        const val DESK_SPACE = "herdr.space"
        const val DESK_TAB = "herdr.tab"
        const val WHERE = "where"
        const val TIMEOUT_MILLIS = 30_000L
        const val SETTLE_MILLIS = 6_000L
    }
}
