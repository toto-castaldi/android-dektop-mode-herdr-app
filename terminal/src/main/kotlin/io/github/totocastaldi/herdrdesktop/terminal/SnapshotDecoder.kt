package io.github.totocastaldi.herdrdesktop.terminal

/**
 * Decodes the int array produced by `nativeSnapshot` in `ghostty_jni.c`:
 *
 * - header of [HEADER_INTS] ints: columns, rows, default foreground, default background, cursor
 *   visible (0/1), cursor column and row (-1 if the cursor is not on the visible screen), cursor
 *   style;
 * - then, for each cell row by row: foreground, background (-1 for the default), style flags
 *   with the cell width in bits 16..17 and bit 18 set for a selected cell (whose inverse flag is already
 *   swapped), number of codepoints `n`, then `n` codepoints.
 */
internal object SnapshotDecoder {
    const val HEADER_INTS = 8
    private const val COLUMNS = 0
    private const val ROWS = 1
    private const val FOREGROUND = 2
    private const val BACKGROUND = 3
    private const val CURSOR_VISIBLE = 4
    private const val CURSOR_COLUMN = 5
    private const val CURSOR_ROW = 6
    private const val CURSOR_STYLE = 7

    private const val CELL_FOREGROUND = 0
    private const val CELL_BACKGROUND = 1
    private const val CELL_FLAGS = 2
    private const val CELL_COUNT = 3
    private const val CELL_FIXED_INTS = 4

    private const val NO_COLOR = -1
    private const val WIDTH_SHIFT = 16
    private const val FLAGS_MASK = 0xFFFF
    private const val WIDTH_MASK = 0x3
    private const val SELECTED_BIT = 1 shl 18

    fun decode(data: IntArray): ScreenSnapshot {
        require(data.size >= HEADER_INTS) { "snapshot too short: ${data.size}" }
        val size = TerminalSize(columns = data[COLUMNS], rows = data[ROWS])
        val cursor = if (data[CURSOR_COLUMN] >= 0 && data[CURSOR_ROW] >= 0) {
            Cursor(
                column = data[CURSOR_COLUMN],
                row = data[CURSOR_ROW],
                visible = data[CURSOR_VISIBLE] != 0,
                style = CursorStyle.entries.getOrElse(data[CURSOR_STYLE]) { CursorStyle.BLOCK }
            )
        } else {
            null
        }
        val cells = ArrayList<Cell>(size.columns * size.rows)
        var i = HEADER_INTS
        while (i < data.size) {
            cells += decodeCell(data, i)
            i += CELL_FIXED_INTS + data[i + CELL_COUNT]
        }
        return ScreenSnapshot(
            size = size,
            foreground = data[FOREGROUND],
            background = data[BACKGROUND],
            cursor = cursor,
            cells = cells
        )
    }

    private fun decodeCell(data: IntArray, start: Int): Cell {
        val count = data[start + CELL_COUNT]
        val text = buildString {
            for (k in 0 until count) appendCodePoint(data[start + CELL_FIXED_INTS + k])
        }
        val flags = data[start + CELL_FLAGS]
        return Cell(
            text = text,
            foreground = data[start + CELL_FOREGROUND].takeUnless { it == NO_COLOR },
            background = data[start + CELL_BACKGROUND].takeUnless { it == NO_COLOR },
            attributes = CellAttributes(flags and FLAGS_MASK),
            width = CellWidth.entries[(flags shr WIDTH_SHIFT) and WIDTH_MASK],
            selected = flags and SELECTED_BIT != 0
        )
    }
}
