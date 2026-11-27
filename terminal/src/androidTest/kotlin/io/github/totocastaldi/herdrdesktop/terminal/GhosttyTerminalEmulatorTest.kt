package io.github.totocastaldi.herdrdesktop.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs libghostty-vt through the JNI binding on the device. */
@RunWith(AndroidJUnit4::class)
class GhosttyTerminalEmulatorTest {
    private val terminal = GhosttyTerminalEmulator(TerminalSize(columns = 20, rows = 4))

    @After
    fun close() = terminal.close()

    private fun write(text: String): ByteArray = terminal.write(text.encodeToByteArray())

    /** Escape bytes spelled out, so that failures stay readable in the XML test report. */
    private fun ByteArray.visible(): String = decodeToString().replace("\u001b", "ESC")

    @Test
    fun snapshotHasColorsAndUnicode() {
        write("\u001b[31mred\u001b[0m \u001b[1;38;2;1;2;3mrgb\u001b[0m\r\n")
        write("\u001b[48;5;21mè 漢字 👍\u001b[0m")

        val snapshot = terminal.snapshot()

        assertEquals(TerminalSize(20, 4), snapshot.size)
        assertEquals("red rgb".padEnd(20), snapshot.rowText(0))
        val r = snapshot.cell(0, 0)
        assertEquals("r", r.text)
        assertEquals(PALETTE_RED, r.foreground)
        assertNull(snapshot.cell(3, 0).foreground)
        val rgb = snapshot.cell(4, 0)
        assertEquals(0x010203, rgb.foreground)
        assertTrue(rgb.attributes.bold)

        assertEquals("è", snapshot.cell(0, 1).text)
        assertEquals(PALETTE_21, snapshot.cell(0, 1).background)
        assertEquals("漢", snapshot.cell(2, 1).text)
        assertEquals(CellWidth.WIDE, snapshot.cell(2, 1).width)
        assertEquals(CellWidth.SPACER_TAIL, snapshot.cell(3, 1).width)
        assertEquals("👍", snapshot.cell(7, 1).text)
        // Wide tails are skipped: 9 columns used, 11 blank.
        assertEquals("è 漢字 👍" + " ".repeat(11), snapshot.rowText(1))
        assertEquals(Cursor(column = 9, row = 1, visible = true, style = CursorStyle.BLOCK), snapshot.cursor)
    }

    /** Each write is in the next screen read, including the last one with nothing after it (BUG-003). */
    @Test
    fun everyWriteIsInTheNextScreen() {
        write("$ ")
        val first = terminal.snapshot()
        assertEquals("$ ".padEnd(20), first.rowText(0))
        write("l")
        assertEquals("$ l".padEnd(20), terminal.snapshot().rowText(0))
        write("s")
        val screen = terminal.screen()
        assertEquals("s", screen.textAt(3, 0))
        write("\r\nout\r\n$ ")
        val after = terminal.screen()
        assertEquals("$", after.textAt(0, 2))
    }

    @Test
    fun writeReturnsAutomaticReplies() {
        write("\u001b[2;5H")

        val reply = write("\u001b[6n").visible()

        assertEquals("ESC[2;5R", reply)
        assertEquals(0, write("plain text").size)
    }

    @Test
    fun encodesKeysWithTheTerminalModes() {
        assertArrayEquals(byteArrayOf('\r'.code.toByte()), terminal.encodeKey(KeyInput(TerminalKey.ENTER)))
        val ctrlC = KeyInput(TerminalKey.C, modifiers = KeyModifiers.CTRL, text = "c", unshiftedCodepoint = 'c'.code)
        assertArrayEquals(byteArrayOf(3), terminal.encodeKey(ctrlC))
        val a = KeyInput(TerminalKey.A, text = "a", unshiftedCodepoint = 'a'.code)
        assertEquals("a", terminal.encodeKey(a).decodeToString())
        assertEquals("ESC[A", terminal.encodeKey(KeyInput(TerminalKey.ARROW_UP)).visible())

        write("\u001b[?1h")

        assertEquals("ESCOA", terminal.encodeKey(KeyInput(TerminalKey.ARROW_UP)).visible())
    }

    @Test
    fun resizeChangesTheGrid() {
        terminal.resize(TerminalSize(columns = 10, rows = 3), cellWidthPx = 9, cellHeightPx = 18)

        write("0123456789AB")

        val snapshot = terminal.snapshot()
        assertEquals(TerminalSize(10, 3), snapshot.size)
        assertEquals("0123456789", snapshot.rowText(0))
        assertEquals("AB" + " ".repeat(8), snapshot.rowText(1))
    }

    @Test
    fun scrollShowsTheScrollbackAndZeroReturnsToTheActiveArea() {
        write((1..20).joinToString("\r\n") { "line $it" })

        terminal.scroll(-10)
        val back = terminal.snapshot()
        terminal.scroll(0)
        val active = terminal.snapshot()

        assertEquals("line 7", back.rowText(0).trim())
        assertEquals("line 20", active.rowText(3).trim())
    }

    /** Cells of 10 x 20 pixels: pixel 15, 25 is in column 1, row 1. */
    private fun cells() = terminal.resize(TerminalSize(columns = 20, rows = 4), cellWidthPx = 10, cellHeightPx = 20)

    private fun mouse(action: MouseAction, button: MouseButton?, mods: KeyModifiers = KeyModifiers.NONE): String =
        terminal.encodeMouse(MouseInput(action, button, 15f, 25f, mods, action == MouseAction.PRESS), 200, 80).visible()

    @Test
    fun mouseIsNotReportedUntilTheProgramAsksForIt() {
        cells()

        assertFalse(terminal.mouseTracking)
        assertEquals("", mouse(MouseAction.PRESS, MouseButton.LEFT))
    }

    @Test
    fun encodesMouseEventsInSgrFormat() {
        cells()
        write("\u001b[?1000h\u001b[?1006h")

        assertTrue(terminal.mouseTracking)
        assertEquals("ESC[<0;2;2M", mouse(MouseAction.PRESS, MouseButton.LEFT))
        assertEquals("ESC[<0;2;2m", mouse(MouseAction.RELEASE, MouseButton.LEFT))
        assertEquals("ESC[<2;2;2M", mouse(MouseAction.PRESS, MouseButton.RIGHT))
        assertEquals("ESC[<16;2;2M", mouse(MouseAction.PRESS, MouseButton.LEFT, KeyModifiers.CTRL))
        assertEquals("ESC[<64;2;2M", mouse(MouseAction.PRESS, MouseButton.WHEEL_UP))
        assertEquals("ESC[<65;2;2M", mouse(MouseAction.PRESS, MouseButton.WHEEL_DOWN))
        // Normal tracking reports no motion.
        assertEquals("", mouse(MouseAction.MOTION, null))
    }

    @Test
    fun encodesMouseEventsInX10FormatAndMotionInAnyEventMode() {
        cells()
        write("\u001b[?1003h")

        // X10: button 0 + 32, then column and row from 1, + 32.
        assertEquals("ESC[M \"\"", mouse(MouseAction.PRESS, MouseButton.LEFT))
        write("\u001b[?1006h")
        assertEquals("ESC[<35;2;2M", mouse(MouseAction.MOTION, null))
        write("\u001b[?1003l")
        assertFalse(terminal.mouseTracking)
    }

    @Test
    fun selectsWithTheMouseAndCopiesTheText() {
        cells()
        write("hello world\r\nsecond line")

        assertTrue(terminal.select(SelectionAction.PRESS, 2f, 5f, 0, 80))
        assertTrue(terminal.select(SelectionAction.DRAG, 46f, 5f, 0, 80))
        terminal.select(SelectionAction.RELEASE, 46f, 5f, 0, 80)

        assertEquals("hello", terminal.selectionText())
        val snapshot = terminal.snapshot()
        assertTrue(snapshot.cell(0, 0).selected)
        assertTrue(snapshot.cell(4, 0).attributes.inverse)
        assertFalse(snapshot.cell(5, 0).selected)

        // A single click clears the selection.
        terminal.select(SelectionAction.PRESS, 2f, 25f, SECOND, 80)
        terminal.select(SelectionAction.RELEASE, 2f, 25f, SECOND, 80)
        assertNull(terminal.selectionText())
    }

    @Test
    fun aSelectionAcrossRowsKeepsTheLineBreak() {
        cells()
        write("hello world\r\nsecond line")

        terminal.select(SelectionAction.PRESS, 62f, 5f, 0, 80)
        terminal.select(SelectionAction.DRAG, 56f, 25f, 0, 80)

        assertEquals("world\nsecond", terminal.selectionText())
    }

    @Test
    fun doubleClickSelectsAWordAndClearRemovesIt() {
        cells()
        write("hello world")

        terminal.select(SelectionAction.PRESS, 72f, 5f, 0, 80)
        terminal.select(SelectionAction.RELEASE, 72f, 5f, 0, 80)
        terminal.select(SelectionAction.PRESS, 72f, 5f, DOUBLE_CLICK_GAP, 80)
        terminal.select(SelectionAction.RELEASE, 72f, 5f, DOUBLE_CLICK_GAP, 80)

        assertEquals("world", terminal.selectionText())
        terminal.clearSelection()
        assertNull(terminal.selectionText())
    }

    @Test
    fun encodesPasteWithAndWithoutBracketedPaste() {
        assertEquals("ls\rpwd", terminal.encodePaste("ls\npwd").decodeToString())

        write("\u001b[?2004h")

        assertEquals("ESC[200~ls\npwdESC[201~", terminal.encodePaste("ls\npwd").visible())
    }

    private companion object {
        const val SECOND = 1_000_000_000L
        const val DOUBLE_CLICK_GAP = 100_000_000L
        const val PALETTE_RED = 0xCC6666
        const val PALETTE_21 = 0x0000FF
    }
}
