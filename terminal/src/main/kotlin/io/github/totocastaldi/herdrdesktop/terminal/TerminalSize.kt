package io.github.totocastaldi.herdrdesktop.terminal

/**
 * Size of a terminal grid in character cells, exchanged between the renderer and the core.
 */
data class TerminalSize(val columns: Int, val rows: Int) {
    init {
        require(columns > 0) { "columns must be positive: $columns" }
        require(rows > 0) { "rows must be positive: $rows" }
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
