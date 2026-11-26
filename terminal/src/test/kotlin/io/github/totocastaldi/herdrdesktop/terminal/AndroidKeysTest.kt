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
}
