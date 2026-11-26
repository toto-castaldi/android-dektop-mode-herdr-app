package io.github.totocastaldi.herdrdesktop.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyInputTest {
    @Test
    fun modifiersCombine() {
        val mods = KeyModifiers.CTRL + KeyModifiers.SHIFT

        assertEquals(0b11, mods.bits)
        assertTrue(KeyModifiers.CTRL in mods)
        assertFalse(KeyModifiers.ALT in mods)
    }

    @Test
    fun keyCodesFollowLibghosttyVt() {
        assertEquals(20, TerminalKey.A.code)
        assertEquals(58, TerminalKey.ENTER.code)
        assertEquals(78, TerminalKey.ARROW_UP.code)
        assertEquals(120, TerminalKey.ESCAPE.code)
        assertEquals(TerminalKey.TAB, TerminalKey.fromCode(64))
        assertEquals(TerminalKey.UNIDENTIFIED, TerminalKey.fromCode(9999))
    }
}
