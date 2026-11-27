package io.github.totocastaldi.herdrdesktop.keyboard

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutsTest {
    private val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
    private val shift = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
    private val alt = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
    private val meta = KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
    private val ctrlShift = ctrl or shift

    @Test
    fun `ctrl shift letters reach the app actions`() {
        assertEquals(Shortcut.NEW_SPACE, Shortcuts.match(KeyEvent.KEYCODE_N, ctrlShift))
        assertEquals(Shortcut.CLOSE_SPACE, Shortcuts.match(KeyEvent.KEYCODE_X, ctrlShift))
        assertEquals(Shortcut.NEW_TAB, Shortcuts.match(KeyEvent.KEYCODE_T, ctrlShift))
        assertEquals(Shortcut.CLOSE_TAB, Shortcuts.match(KeyEvent.KEYCODE_Q, ctrlShift))
        assertEquals(Shortcut.SPLIT_RIGHT, Shortcuts.match(KeyEvent.KEYCODE_D, ctrlShift))
        assertEquals(Shortcut.SPLIT_DOWN, Shortcuts.match(KeyEvent.KEYCODE_E, ctrlShift))
        assertEquals(Shortcut.CLOSE_PANE, Shortcuts.match(KeyEvent.KEYCODE_W, ctrlShift))
        assertEquals(Shortcut.NEXT_PANE, Shortcuts.match(KeyEvent.KEYCODE_RIGHT_BRACKET, ctrlShift))
        assertEquals(Shortcut.PREVIOUS_PANE, Shortcuts.match(KeyEvent.KEYCODE_LEFT_BRACKET, ctrlShift))
        assertEquals(Shortcut.NEXT_TAB, Shortcuts.match(KeyEvent.KEYCODE_PERIOD, ctrlShift))
        assertEquals(Shortcut.PREVIOUS_TAB, Shortcuts.match(KeyEvent.KEYCODE_COMMA, ctrlShift))
        assertEquals(Shortcut.NEXT_SPACE, Shortcuts.match(KeyEvent.KEYCODE_EQUALS, ctrlShift))
        assertEquals(Shortcut.PREVIOUS_SPACE, Shortcuts.match(KeyEvent.KEYCODE_MINUS, ctrlShift))
        assertEquals(Shortcut.TOGGLE_SIDEBAR, Shortcuts.match(KeyEvent.KEYCODE_A, ctrlShift))
        assertEquals(Shortcut.HELP, Shortcuts.match(KeyEvent.KEYCODE_SLASH, ctrlShift))
    }

    @Test
    fun `the keys programs need in the terminal are never taken`() {
        for (code in listOf(KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_R)) {
            assertFalse(Shortcuts.owns(code, ctrl))
        }
        for (mods in listOf(0, ctrl, shift, alt, ctrlShift, ctrl or alt, ctrlShift or alt, meta)) {
            assertFalse(Shortcuts.owns(KeyEvent.KEYCODE_ESCAPE, mods))
            assertFalse(Shortcuts.owns(KeyEvent.KEYCODE_TAB, mods))
            assertFalse(Shortcuts.owns(KeyEvent.KEYCODE_ENTER, mods))
            for (n in 0 until 12) assertFalse(Shortcuts.owns(KeyEvent.KEYCODE_F1 + n, mods))
            for (code in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT) assertFalse(Shortcuts.owns(code, mods))
        }
    }

    @Test
    fun `no letter is taken without both ctrl and shift, or with alt or meta too`() {
        for (code in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
            for (mods in listOf(0, ctrl, shift, alt, ctrl or alt, ctrlShift or alt, ctrlShift or meta)) {
                assertNull(Shortcuts.match(code, mods))
            }
        }
    }

    @Test
    fun `every shortcut is ctrl shift with a letter or punctuation key`() {
        val arrowsAndControls = setOf(
            KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_DEL
        )
        assertEquals(Shortcut.entries.size - 1, Shortcuts.all.size)
        for ((_, code) in Shortcuts.all) {
            assertTrue(code !in arrowsAndControls && code !in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12)
            assertTrue(Shortcuts.label(code).startsWith("Ctrl+Shift+"))
        }
    }

    @Test
    fun `a repeat does not trigger the shortcut again but still belongs to it`() {
        assertNull(Shortcuts.match(KeyEvent.KEYCODE_T, ctrlShift, repeatCount = 1))
        assertTrue(Shortcuts.owns(KeyEvent.KEYCODE_T, ctrlShift))
    }

    @Test
    fun `labels name the key`() {
        assertEquals("Ctrl+Shift+T", Shortcuts.label(KeyEvent.KEYCODE_T))
        assertEquals("Ctrl+Shift+]", Shortcuts.label(KeyEvent.KEYCODE_RIGHT_BRACKET))
        assertEquals("Ctrl+Shift+/", Shortcuts.label(KeyEvent.KEYCODE_SLASH))
    }

    @Test
    fun `cycle moves through the entries and wraps around`() {
        val ids = listOf("a", "b", "c")
        assertEquals("b", cycle(ids, "a", 1))
        assertEquals("a", cycle(ids, "c", 1))
        assertEquals("c", cycle(ids, "a", -1))
        assertEquals("a", cycle(ids, "missing", 1))
        assertNull(cycle(emptyList<String>(), null, 1))
    }
}
