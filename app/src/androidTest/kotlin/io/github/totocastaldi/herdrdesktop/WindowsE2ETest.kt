package io.github.totocastaldi.herdrdesktop

import android.app.Activity
import android.app.ActivityOptions
import android.app.Application
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.totocastaldi.herdrdesktop.coreclient.connection.CoreRpc
import io.github.totocastaldi.herdrdesktop.coreclient.connection.MachineStatus
import io.github.totocastaldi.herdrdesktop.coreclient.connection.status
import io.github.totocastaldi.herdrdesktop.coreclient.machine.Machine
import io.github.totocastaldi.herdrdesktop.coreclient.machine.MachineAuth
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.Workspace
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.WorkspaceClient
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.WorkspaceState
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Windows of the app in Desktop Mode on an external display (REQ-DM-001), driven through the system:
 * resize (`am task resize`), maximize (Meta+=), move to the next display (Meta+Ctrl+D) and density
 * (`wm density`). Each window must keep its activity instance, hence its state, its layout and the core's
 * terminals. Needs an external display in Desktop Mode (`-e winDisplay <id>` to choose it); skipped without.
 * With `-e wsHost <host> -e wsUser <user> [-e wsPort <port>]` (same key and setup as WorkspaceE2ETest) it also
 * opens two windows on two spaces of a real core; `-e wsKey write` only creates the key to authorize.
 */
@RunWith(AndroidJUnit4::class)
class WindowsE2ETest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val app = instrumentation.targetContext.applicationContext as HerdrApp
    private val container get() = app.container
    private val created = mutableListOf<Activity>()
    private val destroyed = mutableListOf<Activity>()
    private var scenario: ActivityScenario<MainActivity>? = null
    private var machine: Machine? = null
    private var display: Int? = null

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (activity is MainActivity) synchronized(created) { created += activity }
        }

        override fun onActivityDestroyed(activity: Activity) {
            if (activity is MainActivity) synchronized(destroyed) { destroyed += activity }
        }

        override fun onActivityStarted(activity: Activity) = Unit

        override fun onActivityResumed(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivityStopped(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }

    @Before
    fun setUp() {
        display = args.getString("winDisplay")?.toInt()
            ?: app.getSystemService(DisplayManager::class.java).displays
                .firstOrNull {
                    it.displayId != Display.DEFAULT_DISPLAY && it.flags and Display.FLAG_PRESENTATION != 0
                }?.displayId
        app.registerActivityLifecycleCallbacks(callbacks)
    }

    @After
    fun tearDown() {
        shell("wm density reset -d ${display ?: Display.DEFAULT_DISPLAY}")
        machine?.let { m ->
            container.connections.sessions.value[m.id]?.let { rpc ->
                runBlocking {
                    WorkspaceClient(rpc).let { c -> c.document.load().workspace.spaces.forEach { c.closeSpace(it.id) } }
                }
            }
            container.connections.forget(m.id)
            container.machines.remove(m.id)
        }
        synchronized(created) {
            created.toList()
        }.forEach { a -> instrumentation.runOnMainSync { a.finishAndRemoveTask() } }
        scenario?.close()
        app.unregisterActivityLifecycleCallbacks(callbacks)
    }

    @Test
    fun windowKeepsItsStateWhenResizedMaximizedMovedAndRescaled() {
        val external = assumeExternalDisplay()
        launch(external)
        // State held only in memory (plain remember): it would be lost if the activity were recreated.
        rule.onNodeWithText(text(R.string.machine_add)).performClick()
        rule.onNode(hasSetTextAction() and hasText(text(R.string.field_name))).performTextInput(DRAFT)
        val window = windows().single()

        val before = width(window)
        shell("am task resize ${window.taskId} 100 100 1100 700")
        rule.waitUntil(TIMEOUT_MILLIS) { width(window) != before }
        assertSameWindows(window)

        val small = width(window)
        shell("input keycombination -t 200 KEYCODE_META_LEFT KEYCODE_EQUALS")
        rule.waitUntil(TIMEOUT_MILLIS) { width(window) > small }
        assertSameWindows(window)

        val dpi = densityDpi(window)
        shell("wm density ${dpi * 3 / 2} -d $external")
        rule.waitUntil(TIMEOUT_MILLIS) { densityDpi(window) != dpi }
        shell("wm density reset -d $external")
        rule.waitUntil(TIMEOUT_MILLIS) { densityDpi(window) == dpi }
        assertSameWindows(window)

        moveToNextDisplay(window, external)
        assertSameWindows(window)
    }

    /** `-e wsKey write`: only creates the test key and writes its public part, to authorize it on the host. */
    @Test
    fun writesTheTestKey() {
        assumeTrue("wsKey not given", args.getString("wsKey") == "write")
        writeTestKey()
    }

    @Test
    fun twoWindowsShowTwoSpacesOfTheCoreAndKeepItsTerminals() {
        val external = assumeExternalDisplay()
        val host = args.getString("wsHost")
        val user = args.getString("wsUser")
        assumeTrue("wsHost and wsUser not given", host != null && user != null)
        launch(external)
        val target = connect(checkNotNull(host), checkNotNull(user))
        assertTrue("the e2e core must start with no spaces", workspace(target).spaces.isEmpty())
        repeat(2) { container.workspaces.perform(target.id) { createSpace() } }
        rule.waitUntil(TIMEOUT_MILLIS) { shown(target)?.spaces?.size == 2 }
        val (first, second) = workspace(target).spaces
        val terminals = terminalIds(target)
        rule.onNodeWithText(terminalText(target, first.tabs.single().terminals.single())).assertExists()

        // A second window opens on the other space; both show the same core state.
        rule.onNodeWithText(text(R.string.workspace_new_window)).performClick()
        rule.waitUntil(TIMEOUT_MILLIS) { windows().size == 2 }
        rule.waitUntil(TIMEOUT_MILLIS) {
            rule.onAllNodesWithText(terminalText(target, second.tabs.single().terminals.single()))
                .fetchSemanticsNodes().size == 1
        }
        container.workspaces.perform(target.id) { renameSpace(first.id, SPACE_NAME) }
        rule.waitUntil(TIMEOUT_MILLIS) { rule.onAllNodesWithText(SPACE_NAME).fetchSemanticsNodes().size == 2 }
        val (one, two) = windows()
        assertNotEquals(one.taskId, two.taskId)

        // The new window is focused: maximize, rescale and move it; the core's terminals stay the same.
        shell("input keycombination -t 200 KEYCODE_META_LEFT KEYCODE_EQUALS")
        val dpi = densityDpi(two)
        shell("wm density ${dpi * 3 / 2} -d $external")
        rule.waitUntil(TIMEOUT_MILLIS) { densityDpi(two) != dpi }
        shell("wm density reset -d $external")
        moveToNextDisplay(two, external)
        assertEquals(listOf(one, two), windows())
        assertTrue(destroyed.isEmpty())
        assertEquals(terminals, terminalIds(target))
        assertEquals(
            1,
            rule.onAllNodesWithText(
                terminalText(target, first.tabs.single().terminals.single())
            ).fetchSemanticsNodes().size
        )
        assertEquals(
            1,
            rule.onAllNodesWithText(
                terminalText(target, second.tabs.single().terminals.single())
            ).fetchSemanticsNodes().size
        )
    }

    private fun assumeExternalDisplay(): Int {
        assumeTrue("no external display", display != null)
        return checkNotNull(display)
    }

    private fun launch(displayId: Int) {
        val intent = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        scenario = ActivityScenario.launch(intent, ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle())
        rule.waitUntil(TIMEOUT_MILLIS) { windows().isNotEmpty() }
    }

    /** Meta+Ctrl+D moves the focused window to the next display, then back to the external one. */
    private fun moveToNextDisplay(window: Activity, external: Int) {
        moveUntil(window) { it != external }
        moveUntil(window) { it == external }
    }

    /** The shortcut goes to the focused window, which may not have its focus back yet: it is sent again. */
    private fun moveUntil(window: Activity, moved: (Int) -> Boolean) {
        repeat(MOVE_ATTEMPTS) {
            shell("input keycombination -t 200 KEYCODE_META_LEFT KEYCODE_CTRL_LEFT KEYCODE_D")
            val deadline = System.currentTimeMillis() + MOVE_WAIT_MILLIS
            while (System.currentTimeMillis() < deadline) {
                if (moved(displayOf(window))) return
                Thread.sleep(POLL_MILLIS)
            }
        }
        error("the window did not move to the next display")
    }

    /** No window was recreated: the same instances are alive and the unsaved draft is still in its field. */
    private fun assertSameWindows(window: Activity) {
        rule.waitForIdle()
        assertEquals(listOf(window), windows())
        assertTrue(destroyed.isEmpty())
        rule.onNodeWithText(DRAFT).assertExists()
    }

    private fun windows(): List<Activity> = synchronized(created) { created.toList() }

    private fun width(a: Activity): Int = onMain { a.window.decorView.width }

    private fun densityDpi(a: Activity): Int = onMain { a.resources.configuration.densityDpi }

    private fun displayOf(a: Activity): Int = onMain { a.display?.displayId ?: -1 }

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { FileInputStream(it.fileDescriptor).readBytes() }
    }

    private fun writeTestKey() {
        val key = container.keys.publicKey(KEY_ALIAS) ?: container.keys.generate(KEY_ALIAS)
        File(app.getExternalFilesDir(null), "workspace-e2e.pub").writeText(key.authorizedKeysLine() + "\n")
    }

    private fun connect(host: String, user: String): Machine {
        writeTestKey()
        val port = args.getString("wsPort")?.toInt() ?: Machine.DEFAULT_PORT
        val added = container.machines.add(NAME, host, port, user, MachineAuth.Key(KEY_ALIAS))
        machine = added
        container.connections.connect(added.id)
        rule.waitUntil(TIMEOUT_MILLIS) { container.connections.status(added.id) is MachineStatus.HostKeyUnconfirmed }
        val presented = (container.connections.status(added.id) as MachineStatus.HostKeyUnconfirmed).presented
        container.connections.confirmHostKey(added.id, presented)
        rule.waitUntil(TIMEOUT_MILLIS) { container.workspaces.state(added.id).value is WorkspaceState.Ready }
        rule.onNode(hasText(text(R.string.machine_open)) and hasAnyAncestor(hasAnyDescendant(hasText(NAME))))
            .performClick()
        return added
    }

    private fun rpc(m: Machine): CoreRpc = checkNotNull(container.connections.sessions.value[m.id])

    private fun workspace(m: Machine): Workspace = runBlocking { WorkspaceClient(rpc(m)).document.load().workspace }

    private fun shown(m: Machine): Workspace? =
        (container.workspaces.state(m.id).value as? WorkspaceState.Ready)?.workspace

    private fun terminalIds(m: Machine): Set<String> = runBlocking { WorkspaceClient(rpc(m)).terminals.list().keys }

    private fun terminalText(m: Machine, terminal: String): String {
        val cwd = (container.workspaces.state(m.id).value as WorkspaceState.Ready).cwdOf(terminal)
        return app.getString(R.string.workspace_terminal, terminal, cwd)
    }

    private fun text(id: Int) = app.getString(id)

    private companion object {
        const val DRAFT = "bozza-finestra"
        const val NAME = "windows-e2e"
        const val SPACE_NAME = "Finestre"
        const val KEY_ALIAS = "e2e-workspace"
        const val TIMEOUT_MILLIS = 20_000L
        const val MOVE_ATTEMPTS = 5
        const val MOVE_WAIT_MILLIS = 3_000L
        const val POLL_MILLIS = 100L
    }
}
