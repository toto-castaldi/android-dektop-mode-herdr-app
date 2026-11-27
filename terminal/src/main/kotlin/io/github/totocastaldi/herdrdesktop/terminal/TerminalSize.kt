package io.github.totocastaldi.herdrdesktop.terminal

import kotlin.math.floor

/**
 * Size of a terminal grid in character cells, exchanged between the renderer and the core.
 */
data class TerminalSize(val columns: Int, val rows: Int) {
    init {
        require(columns > 0) { "columns must be positive: $columns" }
        require(rows > 0) { "rows must be positive: $rows" }
    }

    /**
     * Column and row of the cell at [xPx], [yPx] pixels from the top left corner of the grid, with cells of
     * [cellWidthPx] x [cellHeightPx] pixels; positions outside the grid give the nearest cell.
     */
    fun cellAt(xPx: Float, yPx: Float, cellWidthPx: Int, cellHeightPx: Int): Pair<Int, Int> {
        require(cellWidthPx > 0 && cellHeightPx > 0) { "cell size must be positive" }
        val column = floor(xPx / cellWidthPx).toInt().coerceIn(0, columns - 1)
        val row = floor(yPx / cellHeightPx).toInt().coerceIn(0, rows - 1)
        return column to row
    }

    companion object {
        /**
         * Number of whole cells that fit in a viewport of [widthPx] x [heightPx] pixels with cells of
         * [cellWidthPx] x [cellHeightPx] pixels, never less than one cell per side.
         */
        fun fit(widthPx: Int, heightPx: Int, cellWidthPx: Int, cellHeightPx: Int): TerminalSize {
            require(cellWidthPx > 0 && cellHeightPx > 0) { "cell size must be positive" }
            return TerminalSize(
                columns = (widthPx / cellWidthPx).coerceAtLeast(1),
                rows = (heightPx / cellHeightPx).coerceAtLeast(1)
            )
        }
    }
}
