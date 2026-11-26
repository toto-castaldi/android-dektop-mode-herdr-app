package io.github.totocastaldi.herdrdesktop.terminal

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RenderNode
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Draws screens on an Android canvas with the system monospace font. On a hardware canvas every row is
 * recorded once in a [RenderNode] kept by its content ([RawScreen.rowHash]): a frame records only the rows
 * that changed, and rows that only moved (scrolling output) are reused.
 */
internal class CellPainter(textSizePx: Float) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = textSizePx
    }
    private val fill = Paint()
    private val ascent: Float

    /** Shift of the second drawing of bold text, about a sixteenth of the font size. */
    private val boldOffset = (textSizePx / BOLD_DIVISOR).coerceAtLeast(1f)
    private val cellAdvance: Float
    val metrics: CellMetrics

    /** Whether a glyph advances by exactly one cell, so that it can join a grouped text run. */
    private val fits = HashMap<String, Boolean>()

    /** A recorded row and the content it was recorded from. */
    private class RowEntry(val ints: IntArray, val node: RenderNode)

    /** Recorded rows by content hash, least recently drawn first; a hash collision replaces the entry. */
    private val rows = object : LinkedHashMap<Int, RowEntry>(INITIAL_ROWS, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, RowEntry>): Boolean {
            val evict = size > capacity
            if (evict) eldest.value.node.discardDisplayList()
            return evict
        }
    }
    private var capacity = INITIAL_ROWS

    /** Row hashes of the last frame, its rows that were not in the frame before, and how many rows it had. */
    private var shown: Set<Int> = emptySet()
    private var misses = 0
    private var lastRows = 0

    init {
        val advance = paint.measureText("M")
        val font = paint.fontMetrics
        metrics = CellMetrics(
            ceil(advance).toInt().coerceAtLeast(1),
            ceil(font.descent - font.ascent).toInt().coerceAtLeast(1)
        )
        ascent = -font.ascent
        // Grouped runs advance exactly one cell per character.
        paint.letterSpacing = (metrics.widthPx - advance) / textSizePx
        cellAdvance = paint.measureText("M")
    }

    fun draw(canvas: Canvas, screen: ScreenRows, focused: Boolean, width: Float, height: Float) {
        fill.color = OPAQUE or screen.background
        canvas.drawRect(0f, 0f, width, height, fill)
        val ch = metrics.heightPx.toFloat()
        // Rows are recorded for reuse unless most of them were not on screen in the last frame (continuous
        // output), where recording would only add work.
        val cached = screen is RawScreen && canvas.isHardwareAccelerated && misses * 2 <= lastRows
        misses = 0
        lastRows = screen.size.rows
        val hashes = HashSet<Int>(screen.size.rows * 2)
        capacity = (screen.size.rows * ROWS_KEPT).coerceAtLeast(INITIAL_ROWS)
        for (row in 0 until screen.size.rows) {
            val top = row * ch
            if (screen is RawScreen) {
                val hash = screen.rowHash(row)
                hashes += hash
                if (hash !in shown) misses++
            }
            if (cached) {
                val node = rowNode(screen as RawScreen, row)
                canvas.save()
                canvas.translate(0f, top)
                canvas.drawRenderNode(node)
                canvas.restore()
            } else {
                drawRow(canvas, screen, row, top)
            }
        }
        shown = hashes
        screen.cursor?.takeIf { it.visible }?.let { drawCursor(canvas, screen, it, focused) }
    }

    private fun rowNode(screen: RawScreen, row: Int): RenderNode {
        val key = screen.rowHash(row)
        rows[key]?.takeIf { screen.rowEquals(row, it.ints) }?.let { return it.node }
        val node = RenderNode("terminal-row")
        val width = screen.size.columns * metrics.widthPx
        node.setPosition(0, 0, width, metrics.heightPx)
        val recording = node.beginRecording(width, metrics.heightPx)
        try {
            drawRow(recording, screen, row, 0f)
        } finally {
            node.endRecording()
        }
        rows.put(key, RowEntry(screen.rowInts(row), node))?.node?.discardDisplayList()
        return node
    }

    private fun drawRow(canvas: Canvas, screen: ScreenRows, row: Int, top: Float) {
        val runs = RowRunsBuilder.build(screen, row, ::fitsCell)
        val cw = metrics.widthPx.toFloat()
        for (bg in runs.backgrounds) {
            fill.color = OPAQUE or bg.color
            canvas.drawRect(bg.column * cw, top, (bg.column + bg.width) * cw, top + metrics.heightPx, fill)
        }
        for (text in runs.texts) drawText(canvas, text, text.column * cw, top)
    }

    private fun fitsCell(text: String): Boolean {
        if (fits.size > MAX_GLYPHS) fits.clear()
        return fits.getOrPut(text) { abs(paint.measureText(text) - cellAdvance) < FIT_TOLERANCE_PX }
    }

    private fun drawText(canvas: Canvas, run: TextRun, x: Float, top: Float) {
        val style = run.style
        paint.color = (if (style.faint) FAINT else OPAQUE) or style.foreground
        val width = run.cells * metrics.widthPx.toFloat()
        if (run.text.isNotBlank()) {
            canvas.drawText(run.text, x, top + ascent, paint)
            // Bold as overstrike: the same cached glyphs drawn again, shifted. Skia's synthetic bold
            // (`isFakeBoldText`) costs the render thread too much for continuous output (REQ-NFR-003).
            if (style.bold) canvas.drawText(run.text, x + boldOffset, top + ascent, paint)
        }
        fill.color = paint.color
        val ch = metrics.heightPx.toFloat()
        val line = (ch / LINE_DIVISOR).coerceAtLeast(1f)
        if (style.underline != 0) canvas.drawRect(x, top + ch - line * 2, x + width, top + ch - line, fill)
        if (style.strikethrough) canvas.drawRect(x, top + ch / 2, x + width, top + ch / 2 + line, fill)
        if (style.overline) canvas.drawRect(x, top, x + width, top + line, fill)
    }

    private fun drawCursor(canvas: Canvas, s: ScreenRows, cursor: Cursor, focused: Boolean) {
        if (cursor.row >= s.size.rows || cursor.column >= s.size.columns) return
        val cw = metrics.widthPx.toFloat()
        val ch = metrics.heightPx.toFloat()
        val x = cursor.column * cw
        val y = cursor.row * ch
        val line = (ch / LINE_DIVISOR).coerceAtLeast(1f)
        fill.color = OPAQUE or s.foreground
        when (if (focused) cursor.style else CursorStyle.BLOCK_HOLLOW) {
            CursorStyle.BAR -> canvas.drawRect(x, y, x + line * 2, y + ch, fill)

            CursorStyle.UNDERLINE -> canvas.drawRect(x, y + ch - line * 2, x + cw, y + ch, fill)

            CursorStyle.BLOCK -> {
                canvas.drawRect(x, y, x + cw, y + ch, fill)
                val text = s.textAt(cursor.column, cursor.row)
                if (text.isNotBlank()) {
                    paint.color = OPAQUE or s.background
                    canvas.drawText(text, x, y + ascent, paint)
                }
            }

            CursorStyle.BLOCK_HOLLOW -> {
                fill.style = Paint.Style.STROKE
                fill.strokeWidth = line
                canvas.drawRect(x + line / 2, y + line / 2, x + cw - line / 2, y + ch - line / 2, fill)
                fill.style = Paint.Style.FILL
            }
        }
    }

    private companion object {
        const val OPAQUE = 0xFF000000.toInt()
        const val FAINT = 0x99000000.toInt()
        const val LINE_DIVISOR = 16f
        const val BOLD_DIVISOR = 16f
        const val INITIAL_ROWS = 64
        const val ROWS_KEPT = 3
        const val LOAD_FACTOR = 0.75f
        const val MAX_GLYPHS = 4_096
        const val FIT_TOLERANCE_PX = 0.5f
    }
}
