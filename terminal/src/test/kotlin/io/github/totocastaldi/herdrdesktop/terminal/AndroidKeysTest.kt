package io.github.totocastaldi.herdrdesktop.terminal

import android.view.KeyCharacterMap
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidKeysTest {
    /** A US layout: letters and digits, upper case with Shift, nothing for control keys. */
    private fun us(keyCode: Int): (Int) -> Int = { meta ->
        val shift = meta and KeyEvent.META_SHIFT_ON != 0
        when (keyCode) {
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> (if (shift) 'A' else 'a').code + keyCode - KeyEvent.KEYCODE_A
            KeyEvent.KEYCODE_1 -> if (shift) '!'.code else '1'.code
            KeyEvent.KEYCODE_SPACE -> ' '.code
            KeyEvent.KEYCODE_ENTER -> '\n'.code
            KeyEvent.KEYCODE_GRAVE -> KeyCharacterMap.COMBINING_ACCENT or '`'.code
            else -> 0
        }
    }

    private fun input(keyCode: Int, meta: Int = 0, action: KeyAction = KeyAction.PRESS) =
        AndroidKeys.input(keyCode, meta, action, us(keyCode))

    @Test
    fun `a letter carries its text and its physical key`() {
        val a = input(KeyEvent.KEYCODE_A)!!

        assertEquals(TerminalKey.A, a.key)
        assertEquals("a", a.text)
        assertEquals('a'.code, a.unshiftedCodepoint)
        assertEquals(KeyModifiers.NONE, a.modifiers)
    }

    @Test
    fun `shift is consumed by the text it produces`() {
        val bang = input(KeyEvent.KEYCODE_1, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON)!!

        assertEquals("!", bang.text)
        assertEquals('1'.code, bang.unshiftedCodepoint)
        assertEquals(KeyModifiers.SHIFT, bang.modifiers)
        assertEquals(KeyModifiers.SHIFT, bang.consumedModifiers)
    }

    @Test
    fun `ctrl keeps the plain text so that the encoder applies it`() {
        val ctrlC = input(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)!!

        assertEquals("c", ctrlC.text)
        assertEquals(KeyModifiers.CTRL, ctrlC.modifiers)
        assertEquals(KeyModifiers.NONE, ctrlC.consumedModifiers)
    }

    @Test
    fun `alt and meta map to alt and super`() {
        val mods = AndroidKeys.modifiers(KeyEvent.META_ALT_ON or KeyEvent.META_META_ON or KeyEvent.META_CAPS_LOCK_ON)

        assertEquals(KeyModifiers.ALT + KeyModifiers.SUPER + KeyModifiers.CAPS_LOCK, mods)
    }

    @Test
    fun `control keys have no text`() {
        val enter = input(KeyEvent.KEYCODE_ENTER)!!

        assertEquals(TerminalKey.ENTER, enter.key)
        assertEquals("", enter.text)
        assertEquals(TerminalKey.BACKSPACE, AndroidKeys.key(KeyEvent.KEYCODE_DEL))
        assertEquals(TerminalKey.DELETE, AndroidKeys.key(KeyEvent.KEYCODE_FORWARD_DEL))
        assertEquals(TerminalKey.ARROW_UP, AndroidKeys.key(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(TerminalKey.F12, AndroidKeys.key(KeyEvent.KEYCODE_F12))
        assertEquals(TerminalKey.NUMPAD_7, AndroidKeys.key(KeyEvent.KEYCODE_NUMPAD_7))
    }

    @Test
    fun `every letter and digit has its key`() {
        assertEquals(TerminalKey.Z, AndroidKeys.key(KeyEvent.KEYCODE_Z))
        assertEquals(TerminalKey.DIGIT_0, AndroidKeys.key(KeyEvent.KEYCODE_0))
        assertEquals(TerminalKey.DIGIT_9, AndroidKeys.key(KeyEvent.KEYCODE_9))
    }

    @Test
    fun `a dead key gives no text, an unknown key no input`() {
        assertEquals("", input(KeyEvent.KEYCODE_GRAVE)!!.text)
        assertNull(input(KeyEvent.KEYCODE_VOLUME_UP))
    }

    @Test
    fun `the action is passed through`() {
        assertEquals(KeyAction.RELEASE, input(KeyEvent.KEYCODE_A, action = KeyAction.RELEASE)!!.action)
    }

    private val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
    private val alt = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
    private val shift = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON

    @Test
    fun `alt keeps the plain text so that the encoder prefixes it with escape`() {
        val altB = input(KeyEvent.KEYCODE_B, alt)!!

        assertEquals(TerminalKey.B, altB.key)
        assertEquals("b", altB.text)
        assertEquals(KeyModifiers.ALT, altB.modifiers)
        assertEquals(KeyModifiers.NONE, altB.consumedModifiers)
    }

    @Test
    fun `escape and tab are keys of their own, shift tab keeps shift`() {
        assertEquals(TerminalKey.ESCAPE, input(KeyEvent.KEYCODE_ESCAPE)!!.key)
        assertEquals(TerminalKey.TAB, input(KeyEvent.KEYCODE_TAB)!!.key)
        val shiftTab = input(KeyEvent.KEYCODE_TAB, shift)!!

        assertEquals(KeyModifiers.SHIFT, shiftTab.modifiers)
        assertEquals("", shiftTab.text)
        assertEquals(KeyModifiers.NONE, shiftTab.consumedModifiers)
    }

    @Test
    fun `every function key has its key`() {
        for (n in 1..12) {
            assertEquals(TerminalKey.valueOf("F$n"), input(KeyEvent.KEYCODE_F1 + n - 1)!!.key)
        }
    }

    @Test
    fun `arrows keep every modifier for the encoder`() {
        val arrows = mapOf(
            KeyEvent.KEYCODE_DPAD_UP to TerminalKey.ARROW_UP,
            KeyEvent.KEYCODE_DPAD_DOWN to TerminalKey.ARROW_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT to TerminalKey.ARROW_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT to TerminalKey.ARROW_RIGHT
        )
        for ((code, key) in arrows) {
            val all = input(code, ctrl or alt or shift)!!
            assertEquals(key, all.key)
            assertEquals(KeyModifiers.CTRL + KeyModifiers.ALT + KeyModifiers.SHIFT, all.modifiers)
            assertEquals("", all.text)
            assertEquals(KeyModifiers.NONE, all.consumedModifiers)
        }
    }

    @Test
    fun `ctrl and alt reach the encoder on function keys too`() {
        val ctrlF5 = input(KeyEvent.KEYCODE_F5, ctrl)!!
        val altF12 = input(KeyEvent.KEYCODE_F12, alt)!!

        assertEquals(KeyModifiers.CTRL, ctrlF5.modifiers)
        assertEquals(KeyModifiers.ALT, altF12.modifiers)
    }

    @Test
    fun `ctrl with the letters used by shells keeps the plain text`() {
        for (code in listOf(KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_R)) {
            val event = input(code, ctrl)!!
            assertEquals(('a'.code + code - KeyEvent.KEYCODE_A).toChar().toString(), event.text)
            assertEquals(KeyModifiers.CTRL, event.modifiers)
        }
    }
}
