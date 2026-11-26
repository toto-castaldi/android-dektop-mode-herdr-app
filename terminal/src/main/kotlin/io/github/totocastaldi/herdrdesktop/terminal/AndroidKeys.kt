package io.github.totocastaldi.herdrdesktop.terminal

import android.view.KeyCharacterMap
import android.view.KeyEvent

/**
 * Translates Android key events of a physical keyboard into [KeyInput] for [TerminalEmulator.encodeKey].
 * Works on the plain values of the event, so that it is testable without Android.
 */
object AndroidKeys {
    private const val META_SHIFT = KeyEvent.META_SHIFT_ON
    private const val META_CTRL = KeyEvent.META_CTRL_ON
    private const val META_ALT = KeyEvent.META_ALT_ON
    private const val META_META = KeyEvent.META_META_ON
    private const val TEXT_MODIFIERS = META_CTRL or META_ALT or META_META or
        KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK or KeyEvent.META_META_MASK
    private const val FIRST_PRINTABLE = 0x20
    private const val DELETE_CHAR = 0x7F
    private const val LAST_DIGIT = 9
    private const val FUNCTION_KEYS = 12

    private val keys: Map<Int, TerminalKey> = buildMap {
        val letters = TerminalKey.entries.filter { it.name.length == 1 }
        letters.forEachIndexed { i, key -> put(KeyEvent.KEYCODE_A + i, key) }
        for (digit in 0..LAST_DIGIT) {
            put(KeyEvent.KEYCODE_0 + digit, TerminalKey.valueOf("DIGIT_$digit"))
            put(KeyEvent.KEYCODE_NUMPAD_0 + digit, TerminalKey.valueOf("NUMPAD_$digit"))
        }
        for (n in 1..FUNCTION_KEYS) put(KeyEvent.KEYCODE_F1 + n - 1, TerminalKey.valueOf("F$n"))
        putAll(
            mapOf(
                KeyEvent.KEYCODE_DPAD_UP to TerminalKey.ARROW_UP,
                KeyEvent.KEYCODE_DPAD_DOWN to TerminalKey.ARROW_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT to TerminalKey.ARROW_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT to TerminalKey.ARROW_RIGHT,
                KeyEvent.KEYCODE_ENTER to TerminalKey.ENTER,
                KeyEvent.KEYCODE_TAB to TerminalKey.TAB,
                KeyEvent.KEYCODE_SPACE to TerminalKey.SPACE,
                KeyEvent.KEYCODE_DEL to TerminalKey.BACKSPACE,
                KeyEvent.KEYCODE_FORWARD_DEL to TerminalKey.DELETE,
                KeyEvent.KEYCODE_ESCAPE to TerminalKey.ESCAPE,
                KeyEvent.KEYCODE_MOVE_HOME to TerminalKey.HOME,
                KeyEvent.KEYCODE_MOVE_END to TerminalKey.END,
                KeyEvent.KEYCODE_PAGE_UP to TerminalKey.PAGE_UP,
                KeyEvent.KEYCODE_PAGE_DOWN to TerminalKey.PAGE_DOWN,
                KeyEvent.KEYCODE_INSERT to TerminalKey.INSERT,
                KeyEvent.KEYCODE_GRAVE to TerminalKey.BACKQUOTE,
                KeyEvent.KEYCODE_MINUS to TerminalKey.MINUS,
                KeyEvent.KEYCODE_EQUALS to TerminalKey.EQUAL,
                KeyEvent.KEYCODE_LEFT_BRACKET to TerminalKey.BRACKET_LEFT,
                KeyEvent.KEYCODE_RIGHT_BRACKET to TerminalKey.BRACKET_RIGHT,
                KeyEvent.KEYCODE_BACKSLASH to TerminalKey.BACKSLASH,
                KeyEvent.KEYCODE_SEMICOLON to TerminalKey.SEMICOLON,
                KeyEvent.KEYCODE_APOSTROPHE to TerminalKey.QUOTE,
                KeyEvent.KEYCODE_SLASH to TerminalKey.SLASH,
                KeyEvent.KEYCODE_COMMA to TerminalKey.COMMA,
                KeyEvent.KEYCODE_PERIOD to TerminalKey.PERIOD,
                KeyEvent.KEYCODE_NUMPAD_DIVIDE to TerminalKey.NUMPAD_DIVIDE,
                KeyEvent.KEYCODE_NUMPAD_MULTIPLY to TerminalKey.NUMPAD_MULTIPLY,
                KeyEvent.KEYCODE_NUMPAD_SUBTRACT to TerminalKey.NUMPAD_SUBTRACT,
                KeyEvent.KEYCODE_NUMPAD_ADD to TerminalKey.NUMPAD_ADD,
                KeyEvent.KEYCODE_NUMPAD_DOT to TerminalKey.NUMPAD_DECIMAL,
                KeyEvent.KEYCODE_NUMPAD_COMMA to TerminalKey.NUMPAD_COMMA,
                KeyEvent.KEYCODE_NUMPAD_ENTER to TerminalKey.NUMPAD_ENTER,
                KeyEvent.KEYCODE_NUMPAD_EQUALS to TerminalKey.NUMPAD_EQUAL,
                KeyEvent.KEYCODE_SHIFT_LEFT to TerminalKey.SHIFT_LEFT,
                KeyEvent.KEYCODE_SHIFT_RIGHT to TerminalKey.SHIFT_RIGHT,
                KeyEvent.KEYCODE_CTRL_LEFT to TerminalKey.CONTROL_LEFT,
                KeyEvent.KEYCODE_CTRL_RIGHT to TerminalKey.CONTROL_RIGHT,
                KeyEvent.KEYCODE_ALT_LEFT to TerminalKey.ALT_LEFT,
                KeyEvent.KEYCODE_ALT_RIGHT to TerminalKey.ALT_RIGHT,
                KeyEvent.KEYCODE_META_LEFT to TerminalKey.META_LEFT,
                KeyEvent.KEYCODE_META_RIGHT to TerminalKey.META_RIGHT,
                KeyEvent.KEYCODE_CAPS_LOCK to TerminalKey.CAPS_LOCK
            )
        )
    }

    /** Physical key of an Android key code, [TerminalKey.UNIDENTIFIED] if it has none. */
    fun key(keyCode: Int): TerminalKey = keys[keyCode] ?: TerminalKey.UNIDENTIFIED

    /** Modifiers held according to an Android meta state. */
    fun modifiers(metaState: Int): KeyModifiers {
        var mods = KeyModifiers.NONE
        if (metaState and META_SHIFT != 0) mods += KeyModifiers.SHIFT
        if (metaState and META_CTRL != 0) mods += KeyModifiers.CTRL
        if (metaState and META_ALT != 0) mods += KeyModifiers.ALT
        if (metaState and META_META != 0) mods += KeyModifiers.SUPER
        if (metaState and KeyEvent.META_CAPS_LOCK_ON != 0) mods += KeyModifiers.CAPS_LOCK
        if (metaState and KeyEvent.META_NUM_LOCK_ON != 0) mods += KeyModifiers.NUM_LOCK
        return mods
    }

    /**
     * Key input of an Android event, or null for a key the terminal does not know. [unicodeChar] is
     * `KeyEvent.getUnicodeChar(metaState)`: the text is taken without Ctrl, Alt and Meta, so that the
     * encoder applies them itself (Ctrl+A is the key A with text `a`).
     */
    fun input(keyCode: Int, metaState: Int, action: KeyAction, unicodeChar: (Int) -> Int): KeyInput? {
        val key = key(keyCode)
        if (key == TerminalKey.UNIDENTIFIED) return null
        val textMeta = metaState and TEXT_MODIFIERS.inv()
        val codepoint = printable(unicodeChar(textMeta))
        val unshifted = printable(unicodeChar(0))
        val text = if (codepoint != 0) String(Character.toChars(codepoint)) else ""
        val consumed = if (text.isNotEmpty() && codepoint != unshifted && metaState and META_SHIFT != 0) {
            KeyModifiers.SHIFT
        } else {
            KeyModifiers.NONE
        }
        return KeyInput(key, action, modifiers(metaState), text, unshifted, consumed)
    }

    /** The codepoint if it is text, 0 for control characters and dead keys. */
    private fun printable(codepoint: Int): Int = when {
        codepoint and KeyCharacterMap.COMBINING_ACCENT != 0 -> 0
        codepoint < FIRST_PRINTABLE || codepoint == DELETE_CHAR -> 0
        else -> codepoint
    }
}
