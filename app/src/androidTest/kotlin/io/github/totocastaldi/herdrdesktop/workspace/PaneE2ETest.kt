package io.github.totocastaldi.herdrdesktop.workspace

import android.app.ActivityOptions
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.swipeRight
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
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.Axis
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.PaneLayout
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.PaneNode
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.Tab
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.WorkspaceClient
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.WorkspaceState
import io.github.totocastaldi.herdrdesktop.terminal.CellWidth
import io.github.totocastaldi.herdrdesktop.terminal.FrameObserver
import io.github.totocastaldi.herdrdesktop.terminal.ScreenSnapshot
import io.github.totocastaldi.herdrdesktop.terminal.TerminalSession
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
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
 * Panes with interactive terminals against a real herdr-core (REQ-WS-002, REQ-NFR-003), with the same
 * arguments and test key as [WorkspaceE2ETest]: `-e wsHost <host> -e wsUser <user> [-e wsPort <port>]
 * [-e wsDisplay <id>] [-e wsPauseMillis <ms>] [-e wsHostKey <SHA256 fingerprint>]`; skipped
 * without them. The measurements of REQ-NFR-003 are in [TerminalPerformanceE2ETest]. The test removes
 * the terminals it started and empties the document.
 */
@RunWith(AndroidJUnit4::class)
class PaneE2ETest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val host: String? = args.getString("wsHost")
    private val user: String? = args.getString("wsUser")
    private val port = args.getString("wsPort")?.toInt() ?: Machine.DEFAULT_PORT
    private val pause = args.getString("wsPauseMillis")?.toLong() ?: 0L
    private val display = args.getString("wsDisplay")?.toInt()
    private val app = instrumentation.targetContext.applicationContext as HerdrApp
    private val container get() = app.container
    private var scenario: ActivityScenario<MainActivity>? = null
    private var machine: Machine? = null

    /** Spaces this test created: the only ones it closes. */
    private val ownSpaces = mutableSetOf<String>()

    /** True when the core had no document of the app before the test, so that none is left after it. */
    private var startedWithoutDocument = false

    /** Last frame drawn for each terminal session, as seen by the app's frame observer. */
    private val frames = ConcurrentHashMap<TerminalSession, Pair<Long, Long>>()

    @After
    fun cleanUp() {
        container.frameObserver = null
        machine?.let { m ->
            container.connections.sessions.value[m.id]?.let { rpc ->
                runBlocking { E2eCleanup.closeOwn(rpc, ownSpaces, startedWithoutDocument) }
            }
            container.connections.forget(m.id)
            container.machines.remove(m.id)
        }
        scenario?.close()
    }

    @Test
    fun splitResizeFocusAndClosePanesWithLiveTerminals() {
        assumeTrue("wsHost and wsUser not given", host != null && user != null)
        val target = connect()
        rule.onNodeWithText(text(R.string.workspace_new_space)).performClick()
        awaitTab(target) { PaneLayout.leaves(it.root).size == 1 }
        val first = PaneLayout.leaves(tab(target).root).single()
        val s1 = awaitSession { it.screenText().isNotBlank() }
        shellColorsUnicodeAndScrollback(target, first, s1)
        val (second, s2) = splitRightAndMoveFocus(target, first, s1)
        val third = splitDownAndDragDivider(target, second, s1, s2)
        closePane(target, second, listOf(first, third))
        resizeWindow(target, first, s1)
        agentInPane(first, s1)
    }

    /**
     * The mouse in a pane (REQ-DM-003): dragging selects text, the context menu copies it to the clipboard and
     * pastes the clipboard into the program, its pane actions split and close the pane, a click moves the focus,
     * and a program that turned mouse reporting on gets clicks and the wheel.
     */
    @Test
    fun mouseSelectsCopiesPastesAndReportsToPrograms() {
        assumeTrue("wsHost and wsUser not given", host != null && user != null)
        val target = connect()
        rule.onNodeWithText(text(R.string.workspace_new_space)).performClick()
        awaitTab(target) { PaneLayout.leaves(it.root).size == 1 }
        val first = PaneLayout.leaves(tab(target).root).single()
        val tag = "terminal-${first.id}"
        val s1 = awaitSession { it.screenText().isNotBlank() }
        rule.onNodeWithTag(tag).performClick()
        type("clear; echo us010-copia\n")
        rule.waitUntil(TIMEOUT_MILLIS) { s1.snapshot().findLine("us010-copia") != null }
        step()

        selectAndCopy(tag, s1)
        pasteFromClipboard(target, tag, first.terminal!!, s1)
        menuSplitsAndCloses(target, first, s1)
        mouseReporting(target, tag, first.terminal!!, s1)
    }

    /** A drag over a word selects it; Copia in the context menu puts it in the clipboard. */
    @OptIn(ExperimentalTestApi::class)
    private fun selectAndCopy(tag: String, session: TerminalSession) {
        val (column, row) = session.snapshot().findLine("us010-copia")!!
        // As in Ghostty, a drag takes a cell at either end only past its middle: from the left edge of the first
        // cell to the right edge of the last.
        val start = cellCenter(tag, session, column, row, CELL_LEFT_EDGE)
        val end = cellCenter(tag, session, column + "us010-copia".length - 1, row, CELL_RIGHT_EDGE)
        rule.onNodeWithTag(tag).performMouseInput {
            moveTo(start)
            press()
            moveTo(Offset((start.x + end.x) / 2, start.y))
            moveTo(end)
            release()
        }
        rule.waitUntil(TIMEOUT_MILLIS) { session.selectionText() == "us010-copia" }
        assertTrue(session.snapshot().cell(column, row).selected)
        step()

        rule.onNodeWithTag(tag).performMouseInput { rightClick(end) }
        rule.onNodeWithTag("terminal-menu-copy").performClick()
        rule.waitUntil(TIMEOUT_MILLIS) { clipboardText() == "us010-copia" }
        step()
    }

    /** Incolla in the context menu sends the clipboard's text to the program, as if typed. */
    @OptIn(ExperimentalTestApi::class)
    private fun pasteFromClipboard(target: Machine, tag: String, address: String, session: TerminalSession) {
        instrumentation.runOnMainSync {
            app.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("e2e", "echo us010-incol\"\"la"))
        }
        rule.onNodeWithTag(tag).performMouseInput { rightClick(center) }
        rule.onNodeWithTag("terminal-menu-paste").performClick()
        rule.waitUntil(TIMEOUT_MILLIS) { session.screenText().contains("echo us010-incol\"\"la") }
        // Until its closing animation ends the menu's window has the keyboard: Enter would paste again.
        awaitMenuClosed()
        type("\n")
        awaitScreen(target, address, "us010-incolla")
        rule.waitUntil(TIMEOUT_MILLIS) { session.snapshot().findLine("us010-incolla") != null }
        step()
    }

    /** The context menu has the pane's actions; a mouse click moves the keyboard focus between panes. */
    @OptIn(ExperimentalTestApi::class)
    private fun menuSplitsAndCloses(target: Machine, first: PaneNode.Leaf, s1: TerminalSession) {
        rule.onNodeWithTag("terminal-${first.id}").performMouseInput { rightClick(center) }
        rule.onNodeWithTag("terminal-menu-split-right").performClick()
        awaitTab(target) { PaneLayout.leaves(it.root).size == 2 }
        val second = PaneLayout.leaves(tab(target).root)[1]
        val s2 = awaitSession { it !== s1 && it.screenText().isNotBlank() }
        step()

        awaitMenuClosed()
        rule.onNodeWithTag("terminal-${first.id}").performMouseInput { click(center) }
        type("echo us010-fuoco\n")
        awaitScreen(target, first.terminal!!, "us010-fuoco")
        assertFalse(s2.screenText().contains("us010-fuoco"))
        step()

        rule.onNodeWithTag("terminal-${second.id}").performMouseInput { rightClick(center) }
        rule.onNodeWithTag("terminal-menu-close").performClick()
        rule.onAllNodesWithText(text(R.string.workspace_close)).onLast().performClick()
        awaitTab(target) { PaneLayout.leaves(it.root).map { l -> l.id } == listOf(first.id) }
        rule.waitUntil(TIMEOUT_MILLIS) { second.terminal!! !in terminals(target) }
        step()
    }

    /** With mouse reporting on (SGR), a click and the wheel reach the program: `cat -v` shows them. */
    @OptIn(ExperimentalTestApi::class)
    private fun mouseReporting(target: Machine, tag: String, address: String, session: TerminalSession) {
        rule.onNodeWithTag(tag).performMouseInput { click(center) }
        type("clear; printf '\\033[?1000h\\033[?1006h'; cat -v\n")
        rule.waitUntil(TIMEOUT_MILLIS) { session.mouseTracking }
        val cell = cellCenter(tag, session, 2, 4)
        rule.onNodeWithTag(tag).performMouseInput { click(cell) }
        awaitScreen(target, address, "^[[<0;3;5M^[[<0;3;5m")
        wheel(tag, 1)
        awaitScreen(target, address, "^[[<64;")
        // The selection is not touched by a click that went to the program.
        assertEquals(null, session.selectionText())
        step()
        key(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
        type("printf '\\033[?1000l\\033[?1006l'\n")
        rule.waitUntil(TIMEOUT_MILLIS) { !session.mouseTracking }
    }

    /**
     * A point of the cell at [column], [row] of [session]'s grid, in pixels of the node [tag]: at [across] of its
     * width (0.5 the center) and half its height.
     */
    private fun cellCenter(tag: String, session: TerminalSession, column: Int, row: Int, across: Float = 0.5f): Offset {
        val bounds = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        // The grid is the whole cells that fit: a cell is at most a fraction of a pixel narrower than this.
        val cellWidth = (bounds.width / session.size.columns).toInt()
        val cellHeight = (bounds.height / session.size.rows).toInt()
        return Offset((column + across) * cellWidth, (row + 0.5f) * cellHeight)
    }

    private fun awaitMenuClosed() {
        rule.waitUntil(TIMEOUT_MILLIS) { rule.onAllNodesWithTag("terminal-menu-paste").fetchSemanticsNodes().isEmpty() }
        rule.waitForIdle()
    }

    private fun clipboardText(): String? {
        var text: String? = null
        instrumentation.runOnMainSync {
            val clip = app.getSystemService(ClipboardManager::class.java).primaryClip
            text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        }
        return text
    }

    /** The shell draws its prompt and gets what is typed; colors, wide characters, box drawing, scrollback. */
    private fun shellColorsUnicodeAndScrollback(target: Machine, first: PaneNode.Leaf, s1: TerminalSession) {
        val t1 = first.terminal!!
        rule.onNodeWithTag("terminal-${first.id}").performClick()
        type("echo us005-uno\n")
        awaitScreen(target, t1, "us005-uno")
        rule.waitUntil(TIMEOUT_MILLIS) { s1.screenText().contains("us005-uno") }
        assertEquals(s1.size.columns to s1.size.rows, coreSize(target, t1))
        step()

        type("clear; printf '\\033[38;2;255;0;0mROSSO\\033[0m ${utf8Escaped("漢字 ┌─┐")}\\n'\n")
        rule.waitUntil(TIMEOUT_MILLIS) { s1.snapshot().find("ROSSO") != null }
        val screen = s1.snapshot()
        val (rc, rr) = screen.find("ROSSO")!!
        assertEquals(0xFF0000, screen.cell(rc, rr).foreground)
        val (kc, kr) = screen.find("漢")!!
        assertEquals(CellWidth.WIDE, screen.cell(kc, kr).width)
        assertTrue(screen.rowText(kr).contains("┌─┐"))
        step()

        type("seq 1 400\n")
        rule.waitUntil(TIMEOUT_MILLIS) { s1.screenText().contains("400") }
        wheel("terminal-${first.id}", WHEEL_NOTCHES)
        rule.waitUntil(TIMEOUT_MILLIS) { !s1.screenText().contains("400") }
        step()
        key(KeyEvent.KEYCODE_DEL, 0)
        rule.waitUntil(TIMEOUT_MILLIS) { s1.screenText().contains("400") }
    }

    /** Split right: a new shell, a narrower grid for the first terminal; the focus follows clicks. */
    private fun splitRightAndMoveFocus(
        target: Machine,
        first: PaneNode.Leaf,
        s1: TerminalSession
    ): Pair<PaneNode.Leaf, TerminalSession> {
        val t1 = first.terminal!!
        val wide = s1.size.columns
        rule.onNodeWithTag("split-right-${first.id}").performClick()
        awaitTab(target) { PaneLayout.leaves(it.root).size == 2 }
        val second = PaneLayout.leaves(tab(target).root)[1]
        val t2 = second.terminal!!
        assertEquals(Axis.ROW, (tab(target).root as PaneNode.Split).axis)
        rule.waitUntil(TIMEOUT_MILLIS) { s1.size.columns < wide && coreSize(target, t1)?.first == s1.size.columns }
        val s2 = awaitSession { it !== s1 && it.screenText().isNotBlank() }
        step()

        // The new pane has the focus; clicking the first one moves it back.
        type("echo us005-due\n")
        awaitScreen(target, t2, "us005-due")
        rule.onNodeWithTag("terminal-${first.id}").performClick()
        type("echo us005-tre\n")
        awaitScreen(target, t1, "us005-tre")
        assertFalse(read(target, t2).contains("us005-tre"))
        return second to s2
    }

    /** Split down nests a column in the row; dragging a divider writes the weights and resizes programs. */
    private fun splitDownAndDragDivider(
        target: Machine,
        second: PaneNode.Leaf,
        s1: TerminalSession,
        s2: TerminalSession
    ): PaneNode.Leaf {
        rule.onNodeWithTag("split-down-${second.id}").performClick()
        awaitTab(target) { PaneLayout.leaves(it.root).size == 3 }
        val nested = (tab(target).root as PaneNode.Split).children[1] as PaneNode.Split
        assertEquals(Axis.COLUMN, nested.axis)
        val third = PaneLayout.leaves(tab(target).root)[2]
        val t2 = second.terminal!!
        rule.waitUntil(TIMEOUT_MILLIS) { s2.size.rows < s1.size.rows && coreSize(target, t2)?.second == s2.size.rows }
        step()

        val t1 = PaneLayout.leaves(tab(target).root)[0].terminal!!
        val before = s1.size.columns
        val root = tab(target).root as PaneNode.Split
        rule.onNodeWithTag("divider-${root.id}-0").performTouchInput { swipeRight(centerX, centerX + DRAG_PX) }
        awaitTab(target) { t -> (t.root as PaneNode.Split).children.let { it[0].weight != it[1].weight } }
        rule.waitUntil(TIMEOUT_MILLIS) { s1.size.columns > before && coreSize(target, t1)?.first == s1.size.columns }
        step()
        return third
    }

    /** Closing a pane ends its terminal on the core, and only that one. */
    private fun closePane(target: Machine, pane: PaneNode.Leaf, rest: List<PaneNode.Leaf>) {
        rule.onNodeWithTag("close-${pane.id}").performClick()
        rule.onAllNodesWithText(text(R.string.workspace_close)).onLast().performClick()
        awaitTab(target) { PaneLayout.leaves(it.root).map { l -> l.id } == rest.map { it.id } }
        rule.waitUntil(TIMEOUT_MILLIS) { pane.terminal!! !in terminals(target) }
        assertTrue(terminals(target).containsAll(rest.map { it.terminal!! }))
        step()
    }

    /** Resizing the window resizes the grid of the pane and the program's terminal on the core. */
    private fun resizeWindow(target: Machine, pane: PaneNode.Leaf, session: TerminalSession) {
        val before = session.size
        var task = 0
        scenario!!.onActivity { task = it.taskId }
        instrumentation.uiAutomation
            .executeShellCommand("am task resize $task $WINDOW_LEFT $WINDOW_TOP $WINDOW_RIGHT $WINDOW_BOTTOM")
            .close()
        rule.waitUntil(TIMEOUT_MILLIS) {
            session.size != before && coreSize(target, pane.terminal!!) == (session.size.columns to session.size.rows)
        }
        assertTrue("narrower window, fewer columns", session.size.columns < before.columns)
        step()
    }

    /** A TUI agent: Claude Code draws its screen in the pane; then it is left with Ctrl+C. */
    private fun agentInPane(pane: PaneNode.Leaf, session: TerminalSession) {
        rule.onNodeWithTag("terminal-${pane.id}").performClick()
        type("claude\n")
        rule.waitUntil(AGENT_TIMEOUT_MILLIS) { session.screenText().contains("Claude") }
        assertTrue(session.snapshot().rows().any { row -> row.any { it in "╭╮╰╯│─" } })
        step()
        repeat(2) {
            key(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
            Thread.sleep(SHORT_PAUSE_MILLIS)
        }
    }

    private fun observe() {
        container.frameObserver = FrameObserver { session, vsync, version ->
            frames[session] = vsync to version
        }
    }

    private fun awaitSession(match: (TerminalSession) -> Boolean): TerminalSession {
        var found: TerminalSession? = null
        rule.waitUntil(TIMEOUT_MILLIS) {
            found = frames.keys.firstOrNull { runCatching { match(it) }.getOrDefault(false) }
            found != null
        }
        return found!!
    }

    /** Types ASCII [text] as key events of a virtual keyboard into the focused window. */
    private fun type(text: String) {
        val map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        val events = checkNotNull(map.getEvents(text.toCharArray())) { "not typeable: $text" }
        events.forEach {
            instrumentation.uiAutomation.injectInputEvent(
                KeyEvent.changeTimeRepeat(it, SystemClock.uptimeMillis(), 0),
                true
            )
        }
        rule.waitForIdle()
    }

    /** [text] as `printf` escapes of its UTF-8 bytes, to type characters a keyboard does not have. */
    private fun utf8Escaped(text: String) = text.toByteArray().joinToString("") { "\\x%02x".format(it) }

    /**
     * Turns the mouse wheel [notches] times away from the user over the node [tag], through the system
     * input of the app's display (Compose's own mouse injection does not deliver wheel events here).
     */
    private fun wheel(tag: String, notches: Int) {
        val bounds = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
        val origin = IntArray(2)
        scenario!!.onActivity { it.window.decorView.getLocationOnScreen(origin) }
        val x = (origin[0] + bounds.center.x).toInt()
        val y = (origin[1] + bounds.center.y).toInt()
        val displayArg = display?.let { "-d $it " }.orEmpty()
        instrumentation.uiAutomation
            .executeShellCommand("input mouse ${displayArg}scroll $x $y --axis VSCROLL,$notches")
            .close()
        rule.waitForIdle()
    }

    /** Presses and releases one key; returns the down event's time (`uptimeMillis`). */
    private fun key(code: Int, meta: Int): Long {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0)
        instrumentation.uiAutomation.injectInputEvent(down, true)
        instrumentation.uiAutomation.injectInputEvent(KeyEvent.changeAction(down, KeyEvent.ACTION_UP), true)
        return now
    }

    private fun launch() {
        val intent = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = display?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it).toBundle() }
        scenario = ActivityScenario.launch(intent, options)
    }

    private fun connect(): Machine {
        observe()
        launch()
        val key = container.keys.publicKey(KEY_ALIAS) ?: container.keys.generate(KEY_ALIAS)
        File(app.getExternalFilesDir(null), "workspace-e2e.pub").writeText(key.authorizedKeysLine() + "\n")
        // Machines left by an interrupted run of this test.
        container.machines.machines.value.filter { it.name == NAME }.forEach {
            container.connections.forget(it.id)
            container.machines.remove(it.id)
        }
        val added = container.machines.add(
            NAME,
            checkNotNull(host),
            port,
            checkNotNull(user),
            MachineAuth.Key(KEY_ALIAS)
        )
        machine = added
        container.connections.connect(added.id)
        rule.waitUntil(TIMEOUT_MILLIS) { container.connections.status(added.id) is MachineStatus.HostKeyUnconfirmed }
        val presented = (container.connections.status(added.id) as MachineStatus.HostKeyUnconfirmed).presented
        // `-e wsHostKey SHA256:…`: the host key is confirmed only if it is the expected one.
        args.getString("wsHostKey")?.let { assertEquals(it, presented.fingerprint) }
        container.connections.confirmHostKey(added.id, presented)
        rule.waitUntil(TIMEOUT_MILLIS) { container.workspaces.state(added.id).value is WorkspaceState.Ready }
        // Opening the screen attaches to the terminals of the first space: a core with spaces of someone
        // else is not touched.
        startedWithoutDocument = E2eCleanup.requireNoSpaces(rpc(added))
        rule.onNodeWithTag("open-${added.id}").performClick()
        rule.onNodeWithText(text(R.string.workspace_spaces)).assertExists()
        return added
    }

    private fun step() {
        rule.waitForIdle()
        if (pause > 0) Thread.sleep(pause)
    }

    private fun text(id: Int) = app.getString(id)

    private fun rpc(m: Machine): CoreRpc = checkNotNull(container.connections.sessions.value[m.id])

    /** The only tab of the only space (created by this test), as written in the core document. */
    private fun tab(m: Machine): Tab {
        val space = runBlocking { WorkspaceClient(rpc(m)).document.load().workspace }.spaces.single()
        ownSpaces += space.id
        return space.tabs.single()
    }

    private fun awaitTab(m: Machine, done: (Tab) -> Boolean) {
        rule.waitUntil(TIMEOUT_MILLIS) {
            val shown = (container.workspaces.state(m.id).value as? WorkspaceState.Ready)?.workspace
            val onCore = runCatching { tab(m) }.getOrNull()
            shown?.spaces?.singleOrNull()?.tabs?.singleOrNull()?.let(done) == true && onCore != null && done(onCore)
        }
    }

    private fun terminals(m: Machine): Set<String> = runBlocking { WorkspaceClient(rpc(m)).terminals.list().keys }

    /** Grid of the terminal as the core knows it. */
    private fun coreSize(m: Machine, address: String): Pair<Int, Int>? = runBlocking {
        val list = rpc(m).call("terminal.list", null) as JsonObject
        (list["terminals"] as JsonArray).map { it as JsonObject }
            .firstOrNull { (it["address"] as JsonPrimitive).content == address }
            ?.let { it.getValue("cols").jsonPrimitive.int to it.getValue("rows").jsonPrimitive.int }
    }

    /** The terminal's screen and history as text, read from the core. */
    private fun read(m: Machine, address: String): String = runBlocking {
        val result = rpc(m).call("terminal.read", buildJsonObject { put("terminal", address) }) as JsonObject
        result.values.joinToString("\n") { it.toString() }
    }

    private fun awaitScreen(m: Machine, address: String, text: String) {
        rule.waitUntil(TIMEOUT_MILLIS) { read(m, address).contains(text) }
    }

    private fun TerminalSession.screenText(): String = snapshot().rows().joinToString("\n")

    private fun ScreenSnapshot.rows(): List<String> = (0 until size.rows).map(::rowText)

    /** Column and row where [text] starts on screen, null if it is not there. */
    private fun ScreenSnapshot.find(text: String): Pair<Int, Int>? {
        for (row in 0 until size.rows) {
            val index = rowText(row).indexOf(text)
            if (index >= 0) return columnOf(row, index) to row
        }
        return null
    }

    /** Column and row of a row that is exactly [text] (an output line, not the command that printed it). */
    private fun ScreenSnapshot.findLine(text: String): Pair<Int, Int>? =
        (0 until size.rows).firstOrNull { rowText(it).trim() == text }?.let { row -> rowText(row).indexOf(text) to row }

    /** Grid column of the [index]-th character of [rowText], counting wide cells as two. */
    private fun ScreenSnapshot.columnOf(row: Int, index: Int): Int {
        var chars = 0
        for (column in 0 until size.columns) {
            val cell = cell(column, row)
            if (cell.width == CellWidth.SPACER_TAIL || cell.width == CellWidth.SPACER_HEAD) continue
            if (chars == index) return column
            chars += cell.text.ifEmpty { " " }.length
        }
        return size.columns - 1
    }

    private companion object {
        const val NAME = "pane-e2e"
        const val KEY_ALIAS = "e2e-workspace"
        const val TIMEOUT_MILLIS = 40_000L
        const val AGENT_TIMEOUT_MILLIS = 60_000L
        const val SHORT_PAUSE_MILLIS = 500L
        const val DRAG_PX = 300f

        /** Points near the left and right edge of a cell, as fractions of its width. */
        const val CELL_LEFT_EDGE = 0.1f
        const val CELL_RIGHT_EDGE = 0.9f

        /** Window bounds set by the test, in pixels of the display: smaller than the whole monitor. */
        const val WINDOW_LEFT = 200
        const val WINDOW_TOP = 100
        const val WINDOW_RIGHT = 1800
        const val WINDOW_BOTTOM = 900

        /** Wheel notches towards the scrollback: 3 rows each. */
        const val WHEEL_NOTCHES = 40
    }
}
