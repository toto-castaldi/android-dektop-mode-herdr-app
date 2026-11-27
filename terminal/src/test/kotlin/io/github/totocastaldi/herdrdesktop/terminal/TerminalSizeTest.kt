package io.github.totocastaldi.herdrdesktop.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalSizeTest {
    @Test
    fun fitKeepsOnlyWholeCells() {
        assertEquals(TerminalSize(columns = 80, rows = 24), TerminalSize.fit(805, 490, 10, 20))
    }

    @Test
    fun fitNeverReturnsLessThanOneCell() {
        assertEquals(TerminalSize(columns = 1, rows = 1), TerminalSize.fit(3, 0, 10, 20))
    }

    @Test
    fun cellAtMapsPixelsToTheCellUnderThem() {
        val size = TerminalSize(columns = 80, rows = 24)

        assertEquals(0 to 0, size.cellAt(0f, 0f, 10, 20))
        assertEquals(1 to 0, size.cellAt(10f, 19.9f, 10, 20))
        assertEquals(79 to 23, size.cellAt(799.5f, 479f, 10, 20))
    }

    @Test
    fun cellAtClampsPositionsOutsideTheGrid() {
        val size = TerminalSize(columns = 80, rows = 24)

        assertEquals(0 to 0, size.cellAt(-5f, -30f, 10, 20))
        assertEquals(79 to 23, size.cellAt(2000f, 900f, 10, 20))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveSize() {
        TerminalSize(columns = 0, rows = 24)
    }
}
