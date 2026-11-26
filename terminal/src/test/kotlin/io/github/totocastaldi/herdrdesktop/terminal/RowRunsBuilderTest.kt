package io.github.totocastaldi.herdrdesktop.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RowRunsBuilderTest {
    private val fg = 0xCCCCCC
    private val bg = 0x000000

    private fun cell(
        text: String,
        fg: Int? = null,
        bg: Int? = null,
        attrs: Int = 0,
        width: CellWidth = CellWidth.NARROW
    ) = Cell(text, fg, bg, CellAttributes(attrs), width)

    private fun screen(vararg cells: Cell) = ScreenSnapshot(TerminalSize(cells.size, 1), fg, bg, null, cells.toList())

    @Test
    fun `neighbouring ascii cells of the same style share one text run`() {
        val runs = RowRunsBuilder.build(screen(cell("l"), cell("s"), cell(" "), cell("-"), cell("l")), 0)

        assertEquals(listOf("ls", "-l"), runs.texts.map { it.text })
        assertEquals(listOf(0, 3), runs.texts.map { it.column })
        assertTrue(runs.texts.all { it.grouped && it.style == TextStyle(fg) })
        assertTrue(runs.backgrounds.isEmpty())
    }

    @Test
    fun `a style change starts a new run`() {
        val runs = RowRunsBuilder.build(screen(cell("a"), cell("b", fg = 0xFF0000), cell("c", attrs = BOLD)), 0)

        assertEquals(listOf("a", "b", "c"), runs.texts.map { it.text })
        assertEquals(0xFF0000, runs.texts[1].style.foreground)
        assertTrue(runs.texts[2].style.bold)
    }

    @Test
    fun `wide characters take two cells and are drawn on their own`() {
        val runs = RowRunsBuilder.build(
            screen(cell("a"), cell("漢", width = CellWidth.WIDE), cell("", width = CellWidth.SPACER_TAIL), cell("b")),
            0
        )

        assertEquals(listOf("a", "漢", "b"), runs.texts.map { it.text })
        assertEquals(2, runs.texts[1].cells)
        assertFalse(runs.texts[1].grouped)
        assertEquals(3, runs.texts[2].column)
    }

    @Test
    fun `non ascii text is one grapheme per run, at its own cell`() {
        val runs = RowRunsBuilder.build(screen(cell("─"), cell("─"), cell("é")), 0)

        assertEquals(listOf(0, 1, 2), runs.texts.map { it.column })
        assertTrue(runs.texts.none { it.grouped })
    }

    @Test
    fun `narrow glyphs that fit the cell join the run of their style`() {
        val runs = RowRunsBuilder.build(screen(cell("─"), cell("─"), cell("a"), cell("漢", width = CellWidth.WIDE)), 0) {
            it == "─"
        }

        assertEquals(listOf("──a", "漢"), runs.texts.map { it.text })
        assertTrue(runs.texts[0].grouped)
        assertEquals(3, runs.texts[0].cells)
    }

    @Test
    fun `backgrounds merge and the default background is left out`() {
        val runs = RowRunsBuilder.build(
            screen(cell(" ", bg = 0x0000FF), cell("x", bg = 0x0000FF), cell(" "), cell(" ", bg = 0x00FF00)),
            0
        )

        assertEquals(listOf(BackgroundRun(0, 2, 0x0000FF), BackgroundRun(3, 1, 0x00FF00)), runs.backgrounds)
    }

    @Test
    fun `inverse swaps foreground and background`() {
        val runs = RowRunsBuilder.build(screen(cell("x", attrs = INVERSE)), 0)

        assertEquals(listOf(BackgroundRun(0, 1, fg)), runs.backgrounds)
        assertEquals(bg, runs.texts.single().style.foreground)
    }

    @Test
    fun `invisible text is not drawn but an underlined blank is`() {
        val runs = RowRunsBuilder.build(screen(cell("x", attrs = INVISIBLE), cell(" ", attrs = UNDERLINE_SINGLE)), 0)

        assertEquals(1, runs.texts.size)
        assertEquals(1, runs.texts.single().column)
        assertEquals(1, runs.texts.single().style.underline)
    }

    private companion object {
        const val BOLD = 1
        const val INVERSE = 1 shl 4
        const val INVISIBLE = 1 shl 5
        const val UNDERLINE_SINGLE = 1 shl 8
    }
}
