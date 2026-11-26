package io.github.totocastaldi.herdrdesktop.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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

    private companion object {
        const val PALETTE_RED = 0xCC6666
        const val PALETTE_21 = 0x0000FF
    }
}
