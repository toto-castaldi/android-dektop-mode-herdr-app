package io.github.totocastaldi.herdrdesktop.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotDecoderTest {
    private val red = 0xCD0000
    private val defaultFg = 0xFFFFFF
    private val defaultBg = 0x000000

    private fun cell(fg: Int, bg: Int, flags: Int, vararg codepoints: Int): IntArray =
        intArrayOf(fg, bg, flags, codepoints.size, *codepoints)

    @Test
    fun decodesHeaderCellsAndGraphemes() {
        val bold = 1
        val wide = 1 shl 16
        val tail = 2 shl 16
        val data = intArrayOf(3, 2, defaultFg, defaultBg, 1, 2, 1, 1) +
            cell(red, -1, bold, 'a'.code) +
            cell(-1, -1, wide, 0x6F22) +
            cell(-1, -1, tail) +
            cell(-1, 0x010203, 0, 0x1F44D, 0x1F3FD) +
            cell(-1, -1, 0) +
            cell(-1, -1, 0, 'z'.code)

        val snapshot = SnapshotDecoder.decode(data)

        assertEquals(TerminalSize(3, 2), snapshot.size)
        assertEquals(defaultFg, snapshot.foreground)
        assertEquals(Cursor(column = 2, row = 1, visible = true, style = CursorStyle.BLOCK), snapshot.cursor)
        val a = snapshot.cell(0, 0)
        assertEquals("a", a.text)
        assertEquals(red, a.foreground)
        assertNull(a.background)
        assertTrue(a.attributes.bold)
        assertFalse(a.attributes.italic)
        assertEquals(CellWidth.WIDE, snapshot.cell(1, 0).width)
        assertEquals(CellWidth.SPACER_TAIL, snapshot.cell(2, 0).width)
        assertEquals("a漢", snapshot.rowText(0))
        assertEquals("👍🏽", snapshot.cell(0, 1).text)
        assertEquals(0x010203, snapshot.cell(0, 1).background)
        assertEquals("👍🏽 z", snapshot.rowText(1))
    }

    @Test
    fun cursorOutsideTheViewportIsNull() {
        val data = intArrayOf(1, 1, defaultFg, defaultBg, 0, -1, -1, 0) + cell(-1, -1, 0)

        assertNull(SnapshotDecoder.decode(data).cursor)
    }

    @Test
    fun decodesUnderlineStyleAndOtherAttributes() {
        val curly = 3 shl 8
        val flags = curly or (1 shl 1) or (1 shl 6)
        val data = intArrayOf(1, 1, defaultFg, defaultBg, 0, 0, 0, 0) + cell(-1, -1, flags, 'x'.code)

        val attributes = SnapshotDecoder.decode(data).cell(0, 0).attributes

        assertEquals(3, attributes.underline)
        assertTrue(attributes.italic)
        assertTrue(attributes.strikethrough)
        assertFalse(attributes.bold)
    }

    @Test
    fun decodesTheSelectedBit() {
        val selected = 1 shl 18
        val inverse = 1 shl 4
        val data = intArrayOf(2, 1, defaultFg, defaultBg, 0, 0, 0, 0) +
            cell(-1, -1, selected or inverse, 'a'.code) +
            cell(-1, -1, 0, 'b'.code)

        val snapshot = SnapshotDecoder.decode(data)

        assertTrue(snapshot.cell(0, 0).selected)
        assertTrue(snapshot.cell(0, 0).attributes.inverse)
        assertEquals(CellWidth.NARROW, snapshot.cell(0, 0).width)
        assertFalse(snapshot.cell(1, 0).selected)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAMissingCell() {
        SnapshotDecoder.decode(intArrayOf(2, 1, defaultFg, defaultBg, 0, 0, 0, 0) + cell(-1, -1, 0))
    }
}
