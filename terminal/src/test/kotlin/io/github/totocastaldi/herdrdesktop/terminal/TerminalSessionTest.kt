package io.github.totocastaldi.herdrdesktop.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalSessionTest {
    private class FakeEmulator : TerminalEmulator {
        override var size = TerminalSize(80, 24)
        val written = StringBuilder()
        val scrolls = mutableListOf<Int>()
        var replies = ByteArray(0)
        var closed = false

        override fun write(data: ByteArray, offset: Int, length: Int): ByteArray {
            written.append(String(data, offset, length))
            return replies
        }

        override fun resize(size: TerminalSize, cellWidthPx: Int, cellHeightPx: Int) {
            this.size = size
        }

        override fun scroll(rows: Int) {
            scrolls += rows
        }

        override fun snapshot(): ScreenSnapshot = ScreenSnapshot(
            TerminalSize(1, 1),
            0,
            0,
            null,
            listOf(Cell("", null, null, CellAttributes(0), CellWidth.NARROW))
        )

        override fun encodeKey(input: KeyInput): ByteArray = input.text.toByteArray()

        override fun close() {
            closed = true
        }
    }

    private class RecordingIo : TerminalIo {
        val inputs = mutableListOf<String>()
        val replies = mutableListOf<String>()
        val sizes = mutableListOf<TerminalSize>()

        override fun input(bytes: ByteArray) {
            inputs += bytes.decodeToString()
        }

        override fun reply(bytes: ByteArray) {
            replies += bytes.decodeToString()
        }

        override fun resize(size: TerminalSize, cellWidthPx: Int, cellHeightPx: Int) {
            sizes += size
        }
    }

    private val emulator = FakeEmulator()
    private val io = RecordingIo()
    private val session = TerminalSession(emulator, io)

    @Test
    fun `output goes into the emulator, its replies to the program, and changes the version`() {
        emulator.replies = "\u001b[0n".toByteArray()

        session.output("hello".toByteArray())

        assertEquals("hello", emulator.written.toString())
        assertEquals(listOf("\u001b[0n"), io.replies)
        assertEquals(1L, session.version)
    }

    @Test
    fun `no reply is sent when the emulator has none`() {
        session.output("x".toByteArray())

        assertTrue(io.replies.isEmpty())
    }

    @Test
    fun `a key is sent to the program, a key without bytes is not`() {
        assertTrue(session.key(KeyInput(TerminalKey.A, text = "a")))
        assertFalse(session.key(KeyInput(TerminalKey.SHIFT_LEFT)))

        assertEquals(listOf("a"), io.inputs)
    }

    @Test
    fun `typing after scrolling back returns to the active area`() {
        session.scroll(-3)
        session.key(KeyInput(TerminalKey.A, text = "a"))
        session.key(KeyInput(TerminalKey.B, text = "b"))

        assertEquals(listOf(-3, 0), emulator.scrolls)
    }

    @Test
    fun `the first size is always reported, later ones only when they change`() {
        session.resize(TerminalSize(80, 24), 9, 18)
        session.resize(TerminalSize(80, 24), 9, 18)
        session.resize(TerminalSize(100, 30), 9, 18)

        assertEquals(listOf(TerminalSize(80, 24), TerminalSize(100, 30)), io.sizes)
        assertEquals(TerminalSize(100, 30), session.size)
    }

    @Test
    fun `closing the session closes the emulator`() {
        session.close()

        assertTrue(emulator.closed)
    }
}
