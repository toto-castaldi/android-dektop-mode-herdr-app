package io.github.totocastaldi.herdrdesktop.terminal

/**
 * Visible screen of a [TerminalEmulator] at one moment: [cells] holds `size.rows` rows of
 * `size.columns` cells, row by row. Colors are `0xRRGGBB`.
 */
data class ScreenSnapshot(
    val size: TerminalSize,
    val foreground: Int,
    val background: Int,
    val cursor: Cursor?,
    val cells: List<Cell>
) {
    init {
        require(cells.size == size.columns * size.rows) {
            "expected ${size.columns * size.rows} cells, got ${cells.size}"
        }
    }

    /** Cell at [column], [row], both from 0. */
    fun cell(column: Int, row: Int): Cell = cells[row * size.columns + column]

    /** Text of [row], one character per cell, with blank cells as spaces and wide tails skipped. */
    fun rowText(row: Int): String = buildString {
        for (column in 0 until size.columns) {
            val cell = cell(column, row)
            when {
                cell.width == CellWidth.SPACER_TAIL || cell.width == CellWidth.SPACER_HEAD -> Unit
                cell.text.isEmpty() -> append(' ')
                else -> append(cell.text)
            }
        }
    }
}

/** Cursor position on the visible screen, from 0, and its shape. */
data class Cursor(val column: Int, val row: Int, val visible: Boolean, val style: CursorStyle)

/** Cursor shapes, with the codes of `GhosttyCursorStyle`. */
enum class CursorStyle {
    BAR,
    BLOCK,
    UNDERLINE,
    BLOCK_HOLLOW
}

/**
 * One grid cell.
 *
 * @property text the grapheme cluster of the cell, empty if the cell is blank.
 * @property foreground the cell's own text color, `null` for the default foreground.
 * @property background the cell's own background color, `null` for the default background.
 */
data class Cell(
    val text: String,
    val foreground: Int?,
    val background: Int?,
    val attributes: CellAttributes,
    val width: CellWidth
)

/** How many columns a cell takes, with the codes of `GhosttyCellWide`. */
enum class CellWidth {
    /** One column. */
    NARROW,

    /** First of the two columns of a wide character. */
    WIDE,

    /** Second column of a wide character, drawn by the cell before it. */
    SPACER_TAIL,

    /** Empty last column of a row whose wide character wrapped to the next row. */
    SPACER_HEAD
}

/** Text attributes of a cell, with the bits of `GhosttyStyleFlags`. */
@JvmInline
value class CellAttributes(val bits: Int) {
    val bold: Boolean get() = bits and BOLD != 0
    val italic: Boolean get() = bits and ITALIC != 0
    val faint: Boolean get() = bits and FAINT != 0
    val blink: Boolean get() = bits and BLINK != 0
    val inverse: Boolean get() = bits and INVERSE != 0
    val invisible: Boolean get() = bits and INVISIBLE != 0
    val strikethrough: Boolean get() = bits and STRIKETHROUGH != 0
    val overline: Boolean get() = bits and OVERLINE != 0

    /** Underline style: 0 none, 1 single, 2 double, 3 curly, 4 dotted, 5 dashed. */
    val underline: Int get() = (bits shr UNDERLINE_SHIFT) and UNDERLINE_MASK

    private companion object {
        const val BOLD = 1 shl 0
        const val ITALIC = 1 shl 1
        const val FAINT = 1 shl 2
        const val BLINK = 1 shl 3
        const val INVERSE = 1 shl 4
        const val INVISIBLE = 1 shl 5
        const val STRIKETHROUGH = 1 shl 6
        const val OVERLINE = 1 shl 7
        const val UNDERLINE_SHIFT = 8
        const val UNDERLINE_MASK = 0x7
    }
}
