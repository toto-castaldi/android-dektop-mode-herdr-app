package io.github.totocastaldi.herdrdesktop.terminal

/** Colors and style a piece of text is drawn with; colors are `0xRRGGBB`. */
data class TextStyle(
    val foreground: Int,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val faint: Boolean = false,
    val underline: Int = 0,
    val strikethrough: Boolean = false,
    val overline: Boolean = false
)

/** Background of [width] cells from [column]. */
data class BackgroundRun(val column: Int, val width: Int, val color: Int)

/**
 * Text drawn from [column] over [cells] cells. A [grouped] run holds narrow cells whose glyphs advance by
 * exactly one cell, drawn with one call; other text is one grapheme per run, drawn at its own cell.
 */
data class TextRun(val column: Int, val cells: Int, val text: String, val style: TextStyle, val grouped: Boolean)

/** What the renderer draws for one row of a screen. */
data class RowRuns(val backgrounds: List<BackgroundRun>, val texts: List<TextRun>)

/**
 * Splits a screen row into background and text runs, so that a frame needs few draw calls: neighbouring
 * cells with the same background share one rectangle, neighbouring narrow cells with the same style one
 * text, if their glyphs fit the cell ([fitsCell], always true for ASCII). Backgrounds equal to the default
 * are left out (the whole pane is cleared with it).
 */
object RowRunsBuilder {
    private const val ASCII_LAST = 0x7E

    fun build(screen: ScreenRows, row: Int, fitsCell: (String) -> Boolean = { false }): RowRuns {
        val backgrounds = ArrayList<BackgroundRun>()
        val texts = TextRuns()
        val columns = screen.size.columns
        var column = 0
        while (column < columns) {
            val width = screen.widthAt(column, row)
            val cells = if (width == CellWidth.WIDE) 2.coerceAtMost(columns - column) else 1
            if (width != CellWidth.SPACER_TAIL) addCell(screen, column, row, cells, backgrounds, texts, fitsCell)
            column += cells
        }
        return RowRuns(backgrounds, texts.runs())
    }

    @Suppress("LongParameterList")
    private fun addCell(
        screen: ScreenRows,
        column: Int,
        row: Int,
        cells: Int,
        backgrounds: MutableList<BackgroundRun>,
        texts: TextRuns,
        fitsCell: (String) -> Boolean
    ) {
        val attributes = screen.attributesAt(column, row)
        val fg = color(screen.foregroundAt(column, row), screen.foreground)
        val bg = color(screen.backgroundAt(column, row), screen.background)
        addBackground(backgrounds, column, cells, if (attributes.inverse) fg else bg, screen.background)
        val text = screen.textAt(column, row)
        if (!drawn(text, attributes)) return
        // Compared as a number: a style object is made only when a run starts.
        val styleKey = ((if (attributes.inverse) bg else fg).toLong() shl STYLE_SHIFT) or
            (attributes.bits and STYLE_BITS).toLong()
        val shown = if (text.isEmpty() || text == " " || attributes.invisible) " " else text
        texts.add(column, cells, shown, styleKey, cells == 1 && (isAscii(shown) || fitsCell(shown)))
    }

    /** The style of a key made by `addCell`. */
    private fun styleOf(key: Long): TextStyle {
        val a = CellAttributes((key and STYLE_BITS.toLong()).toInt())
        return TextStyle(
            (key shr STYLE_SHIFT).toInt(),
            a.bold,
            a.italic,
            a.faint,
            a.underline,
            a.strikethrough,
            a.overline
        )
    }

    /** Attribute bits that change how text is drawn, once inverse and invisible are resolved. */
    private const val STYLE_BITS = 0x7C7
    private const val STYLE_SHIFT = 16

    private fun color(value: Int, default: Int) = if (value == ScreenRows.NO_COLOR) default else value

    /** Visible text, or a blank that carries a line. */
    private fun drawn(text: String, a: CellAttributes): Boolean {
        val visible = text.isNotEmpty() && text != " " && !a.invisible
        return visible || a.underline != 0 || a.strikethrough || a.overline
    }

    private fun isAscii(text: String) = text.length == 1 && text[0].code <= ASCII_LAST

    private fun addBackground(runs: MutableList<BackgroundRun>, column: Int, cells: Int, color: Int, default: Int) {
        if (color == default) return
        val last = runs.lastOrNull()
        if (last != null && last.color == color && last.column + last.width == column) {
            runs[runs.lastIndex] = last.copy(width = last.width + cells)
        } else {
            runs += BackgroundRun(column, cells, color)
        }
    }

    /** Text runs being built: the open grouped run grows in a builder instead of by concatenation. */
    internal class TextRuns {
        private val done = ArrayList<TextRun>()
        private var open: StringBuilder? = null
        private var openColumn = 0
        private var openCells = 0
        private var openKey = 0L
        private var lastKey = -1L
        private var lastStyle: TextStyle? = null

        fun add(column: Int, cells: Int, text: String, styleKey: Long, grouped: Boolean) {
            val builder = open
            if (grouped && builder != null && extends(styleKey, column)) {
                builder.append(text)
                openCells += cells
                return
            }
            close()
            if (grouped) {
                open = StringBuilder(text)
                openColumn = column
                openCells = cells
                openKey = styleKey
            } else {
                done += TextRun(column, cells, text, style(styleKey), grouped = false)
            }
        }

        private fun extends(styleKey: Long, column: Int) = openKey == styleKey && openColumn + openCells == column

        /** Neighbouring runs often share a style: the last one is reused. */
        private fun style(key: Long): TextStyle {
            lastStyle?.takeIf { lastKey == key }?.let { return it }
            return styleOf(key).also {
                lastKey = key
                lastStyle = it
            }
        }

        fun runs(): List<TextRun> {
            close()
            return done
        }

        private fun close() {
            val builder = open ?: return
            done += TextRun(openColumn, openCells, builder.toString(), style(openKey), grouped = true)
            open = null
        }
    }
}
