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

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveSize() {
        TerminalSize(columns = 0, rows = 24)
    }
}
