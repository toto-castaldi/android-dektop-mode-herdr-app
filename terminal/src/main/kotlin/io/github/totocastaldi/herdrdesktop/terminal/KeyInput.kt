package io.github.totocastaldi.herdrdesktop.terminal

/** Whether a key goes down, repeats or goes up, with the codes of `GhosttyKeyAction`. */
enum class KeyAction(val code: Int) {
    RELEASE(0),
    PRESS(1),
    REPEAT(2)
}

/** Set of modifier keys, with the bits of `GhosttyMods`. */
@JvmInline
value class KeyModifiers(val bits: Int) {
    operator fun plus(other: KeyModifiers): KeyModifiers = KeyModifiers(bits or other.bits)

    operator fun contains(other: KeyModifiers): Boolean = bits and other.bits == other.bits

    companion object {
        val NONE = KeyModifiers(0)
        val SHIFT = KeyModifiers(1 shl 0)
        val CTRL = KeyModifiers(1 shl 1)
        val ALT = KeyModifiers(1 shl 2)
        val SUPER = KeyModifiers(1 shl 3)
        val CAPS_LOCK = KeyModifiers(1 shl 4)
        val NUM_LOCK = KeyModifiers(1 shl 5)
    }
}

/**
 * One key event for [TerminalEmulator.encodeKey].
 *
 * @property key the physical key.
 * @property text the text the key produces with the current layout, before Ctrl or Alt are
 *   applied (for example `"a"` for Ctrl+A); empty for function keys and control characters.
 * @property unshiftedCodepoint the character of the key without modifiers, 0 if none.
 * @property consumedModifiers the modifiers already used to produce [text] (for example Shift
 *   for `"A"`).
 */
data class KeyInput(
    val key: TerminalKey,
    val action: KeyAction = KeyAction.PRESS,
    val modifiers: KeyModifiers = KeyModifiers.NONE,
    val text: String = "",
    val unshiftedCodepoint: Int = 0,
    val consumedModifiers: KeyModifiers = KeyModifiers.NONE
)
