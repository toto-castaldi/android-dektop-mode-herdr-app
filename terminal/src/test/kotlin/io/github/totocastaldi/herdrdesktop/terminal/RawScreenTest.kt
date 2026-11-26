package io.github.totocastaldi.herdrdesktop.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RawScreenTest {
    private val fg = 0xFFFFFF
    private val bg = 0x000000

    private fun cell(fg: Int, bg: Int, flags: Int, vararg codepoints: Int): IntArray =
        intArrayOf(fg, bg, flags, codepoints.size, *codepoints)

    /** 3x2: `a漢` / ` 👍🏽z`-like rows, cursor at (2, 1). */
    private fun data(defaultFg: Int = fg, firstRowLetter: Int = 'a'.code) =
        intArrayOf(3, 2, defaultFg, bg, 1, 2, 1, 1) +
            cell(0xCD0000, -1, 1, firstRowLetter) +
            cell(-1, -1, 1 shl 16, 0x6F22) +
            cell(-1, -1, 2 shl 16) +
            cell(-1, 0x010203, 0, 0x1F44D, 0x1F3FD) +
            cell(-1, -1, 0) +
            cell(-1, -1, 0, 'z'.code)

    @Test
    fun `cells are read in place like the decoded snapshot`() {
        val raw = RawScreen(data())
        val decoded = SnapshotDecoder.decode(data())

        assertEquals(decoded.size, raw.size)
        assertEquals(decoded.cursor, raw.cursor)
        for (row in 0 until 2) {
            for (column in 0 until 3) {
                assertEquals(decoded.textAt(column, row), raw.textAt(column, row))
                assertEquals(decoded.foregroundAt(column, row), raw.foregroundAt(column, row))
                assertEquals(decoded.backgroundAt(column, row), raw.backgroundAt(column, row))
                assertEquals(decoded.attributesAt(column, row), raw.attributesAt(column, row))
                assertEquals(decoded.widthAt(column, row), raw.widthAt(column, row))
            }
        }
        assertEquals(RowRunsBuilder.build(decoded, 0), RowRunsBuilder.build(raw, 0))
    }

    @Test
    fun `ascii text is shared, not allocated per cell`() {
        val raw = RawScreen(data())

        assertSame(raw.textAt(2, 1), RawScreen(data()).textAt(2, 1))
    }

    @Test
    fun `a row is recognised by its content and its default colors`() {
        val raw = RawScreen(data())
        val ints = raw.rowInts(0)

        assertTrue(raw.rowEquals(0, ints))
        assertFalse(raw.rowEquals(1, ints))
        assertFalse(RawScreen(data(firstRowLetter = 'b'.code)).rowEquals(0, ints))
        assertFalse(RawScreen(data(defaultFg = 0x123456)).rowEquals(0, ints))
        assertEquals(raw.rowHash(1), RawScreen(data(firstRowLetter = 'b'.code)).rowHash(1))
        assertNotEquals(raw.rowHash(0), RawScreen(data(defaultFg = 0x123456)).rowHash(0))
    }
}
