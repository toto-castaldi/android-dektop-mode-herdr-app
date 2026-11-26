package io.github.totocastaldi.herdrdesktop.terminal

/**
 * What the renderer reads of a screen, cell by cell: implemented by [ScreenSnapshot] and, without
 * allocating a cell object per cell, by [RawScreen]. Colors are `0xRRGGBB`, [NO_COLOR] for the default.
 */
interface ScreenRows {
    val size: TerminalSize
    val foreground: Int
    val background: Int

    /** Cursor on the visible screen, null if it is not there. */
    val cursor: Cursor?

    fun foregroundAt(column: Int, row: Int): Int

    fun backgroundAt(column: Int, row: Int): Int

    fun attributesAt(column: Int, row: Int): CellAttributes

    fun widthAt(column: Int, row: Int): CellWidth

    /** The grapheme of the cell, empty if blank. */
    fun textAt(column: Int, row: Int): String

    companion object {
        const val NO_COLOR = -1
    }
}

/**
 * A screen in the int layout of `nativeSnapshot` (see [SnapshotDecoder]), read in place: the renderer
 * builds strings only for the text it draws, and compares rows by their ints to reuse what it drew.
 * [startsBuffer], when large enough, is reused for the cell offsets instead of a new array.
 */
class RawScreen(private val data: IntArray, startsBuffer: IntArray? = null) : ScreenRows {
    override val size = TerminalSize(data[COLUMNS], data[ROWS])
    override val foreground: Int get() = data[FOREGROUND]
    override val background: Int get() = data[BACKGROUND]

    /** Start of every cell in [data], plus the end of the last one. */
    internal val starts: IntArray = (
        startsBuffer?.takeIf { it.size > size.columns * size.rows } ?: IntArray(size.columns * size.rows + 1)
        ).also { starts ->
        var i = SnapshotDecoder.HEADER_INTS
        for (cell in 0 until size.columns * size.rows) {
            starts[cell] = i
            i += CELL_FIXED_INTS + data[i + CELL_COUNT]
        }
        starts[size.columns * size.rows] = i
    }

    override val cursor: Cursor? get() = if (data[CURSOR_COLUMN] >= 0 && data[CURSOR_ROW] >= 0) {
        Cursor(
            data[CURSOR_COLUMN],
            data[CURSOR_ROW],
            data[CURSOR_VISIBLE] != 0,
            CursorStyle.entries.getOrElse(data[CURSOR_STYLE]) { CursorStyle.BLOCK }
        )
    } else {
        null
    }

    private fun start(column: Int, row: Int) = starts[row * size.columns + column]

    override fun foregroundAt(column: Int, row: Int) = data[start(column, row) + CELL_FOREGROUND]

    override fun backgroundAt(column: Int, row: Int) = data[start(column, row) + CELL_BACKGROUND]

    override fun attributesAt(column: Int, row: Int) =
        CellAttributes(data[start(column, row) + CELL_FLAGS] and FLAGS_MASK)

    override fun widthAt(column: Int, row: Int) =
        CellWidth.entries[(data[start(column, row) + CELL_FLAGS] shr WIDTH_SHIFT) and WIDTH_MASK]

    override fun textAt(column: Int, row: Int): String {
        val s = start(column, row)
        val count = data[s + CELL_COUNT]
        val first = if (count > 0) data[s + CELL_FIXED_INTS] else 0
        return when {
            count == 0 -> ""
            count == 1 && first < ASCII.size -> ASCII[first]
            else -> buildString { for (k in 0 until count) appendCodePoint(data[s + CELL_FIXED_INTS + k]) }
        }
    }

    /** Hash of [row]'s ints and of the default colors it is drawn with, computed in place. */
    fun rowHash(row: Int): Int {
        var hash = HASH_SEED * foreground + background
        for (i in starts[row * size.columns] until starts[(row + 1) * size.columns]) hash = HASH_PRIME * hash + data[i]
        return hash
    }

    /** True if [ints] (from [rowInts]) is what [row] holds now. */
    fun rowEquals(row: Int, ints: IntArray): Boolean {
        val from = starts[row * size.columns]
        val length = starts[(row + 1) * size.columns] - from
        val sameColors = ints.size == length + 2 && ints[0] == foreground && ints[1] == background
        return sameColors && (0 until length).all { ints[it + 2] == data[from + it] }
    }

    /** Copy of [row]'s ints with the default colors, to recognise the row in a later screen. */
    fun rowInts(row: Int): IntArray {
        val from = starts[row * size.columns]
        val length = starts[(row + 1) * size.columns] - from
        val ints = IntArray(length + 2)
        ints[0] = foreground
        ints[1] = background
        System.arraycopy(data, from, ints, 2, length)
        return ints
    }

    private companion object {
        const val COLUMNS = 0
        const val ROWS = 1
        const val FOREGROUND = 2
        const val BACKGROUND = 3
        const val CURSOR_VISIBLE = 4
        const val CURSOR_COLUMN = 5
        const val CURSOR_ROW = 6
        const val CURSOR_STYLE = 7
        const val CELL_FOREGROUND = 0
        const val CELL_BACKGROUND = 1
        const val CELL_FLAGS = 2
        const val CELL_COUNT = 3
        const val CELL_FIXED_INTS = 4
        const val WIDTH_SHIFT = 16
        const val FLAGS_MASK = 0xFFFF
        const val WIDTH_MASK = 0x3
        const val ASCII_COUNT = 128
        const val HASH_SEED = 31
        const val HASH_PRIME = 31

        /** One shared string per ASCII character, so that plain text allocates nothing. */
        val ASCII = Array(ASCII_COUNT) { it.toChar().toString() }
    }
}
