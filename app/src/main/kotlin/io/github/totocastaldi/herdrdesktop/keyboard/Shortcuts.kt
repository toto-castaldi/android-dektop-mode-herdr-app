package io.github.totocastaldi.herdrdesktop.keyboard

import android.view.KeyEvent

/** An action of the app reachable from the physical keyboard (REQ-DM-002). */
enum class Shortcut {
    NEW_SPACE,
    CLOSE_SPACE,
    NEXT_SPACE,
    PREVIOUS_SPACE,
    NEW_TAB,
    CLOSE_TAB,
    NEXT_TAB,
    PREVIOUS_TAB,
    SPLIT_RIGHT,
    SPLIT_DOWN,
    CLOSE_PANE,
    NEXT_PANE,
    PREVIOUS_PANE,
    TOGGLE_SIDEBAR,
    HELP,

    /** Not a key: the keyboard goes back to the focused pane, for example after the help panel closes. */
    REFOCUS
}

/**
 * The app's keyboard shortcuts. All of them are Ctrl+Shift with a letter or a punctuation key, and nothing else
 * held: in the legacy encoding Ctrl+Shift+letter sends the same byte as Ctrl+letter, so programs cannot tell
 * them apart anyway, while Ctrl+letter (Ctrl+C, Ctrl+D, Ctrl+Z, Ctrl+R...), Alt, Esc, Tab, function keys and
 * arrows with any modifier always reach the terminal.
 */
object Shortcuts {
    private val bindings: Map<Int, Shortcut> = mapOf(
        KeyEvent.KEYCODE_N to Shortcut.NEW_SPACE,
        KeyEvent.KEYCODE_X to Shortcut.CLOSE_SPACE,
        KeyEvent.KEYCODE_EQUALS to Shortcut.NEXT_SPACE,
        KeyEvent.KEYCODE_MINUS to Shortcut.PREVIOUS_SPACE,
        KeyEvent.KEYCODE_T to Shortcut.NEW_TAB,
        KeyEvent.KEYCODE_Q to Shortcut.CLOSE_TAB,
        KeyEvent.KEYCODE_PERIOD to Shortcut.NEXT_TAB,
        KeyEvent.KEYCODE_COMMA to Shortcut.PREVIOUS_TAB,
        KeyEvent.KEYCODE_D to Shortcut.SPLIT_RIGHT,
        KeyEvent.KEYCODE_E to Shortcut.SPLIT_DOWN,
        KeyEvent.KEYCODE_W to Shortcut.CLOSE_PANE,
        KeyEvent.KEYCODE_RIGHT_BRACKET to Shortcut.NEXT_PANE,
        KeyEvent.KEYCODE_LEFT_BRACKET to Shortcut.PREVIOUS_PANE,
        KeyEvent.KEYCODE_A to Shortcut.TOGGLE_SIDEBAR,
        KeyEvent.KEYCODE_SLASH to Shortcut.HELP
    )

    private const val REQUIRED = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON
    private const val FORBIDDEN = KeyEvent.META_ALT_ON or KeyEvent.META_META_ON

    /** Every shortcut with its key code, in the order of the help panel. */
    val all: List<Pair<Shortcut, Int>> = bindings.entries.map { it.value to it.key }.sortedBy { it.first.ordinal }

    /**
     * The shortcut of a key going down with [keyCode] and Android [metaState], or null when the key belongs to
     * the terminal. Repeats do not trigger it again ([repeatCount] > 0).
     */
    fun match(keyCode: Int, metaState: Int, repeatCount: Int = 0): Shortcut? {
        val held = metaState and REQUIRED == REQUIRED && metaState and FORBIDDEN == 0
        return if (held && repeatCount == 0) bindings[keyCode] else null
    }

    /** Whether the key belongs to a shortcut, also while it repeats or goes up: then it never reaches the terminal. */
    fun owns(keyCode: Int, metaState: Int): Boolean = match(keyCode, metaState) != null

    /** The key combination as the help panel shows it, for example `Ctrl+Shift+T`. */
    fun label(keyCode: Int): String = "Ctrl+Shift+" + when (keyCode) {
        KeyEvent.KEYCODE_EQUALS -> "="
        KeyEvent.KEYCODE_MINUS -> "-"
        KeyEvent.KEYCODE_PERIOD -> "."
        KeyEvent.KEYCODE_COMMA -> ","
        KeyEvent.KEYCODE_RIGHT_BRACKET -> "]"
        KeyEvent.KEYCODE_LEFT_BRACKET -> "["
        KeyEvent.KEYCODE_SLASH -> "/"
        else -> ('A' + (keyCode - KeyEvent.KEYCODE_A)).toString()
    }
}

/** The entry [step] places after [current] in [entries], wrapping around; the first one when [current] is absent. */
fun <T> cycle(entries: List<T>, current: T?, step: Int): T? {
    val index = entries.indexOf(current)
    return when {
        entries.isEmpty() -> null
        index < 0 -> entries.first()
        else -> entries[Math.floorMod(index + step, entries.size)]
    }
}
