package io.github.totocastaldi.herdrdesktop.terminal

/** Whether a mouse button goes down, goes up or the pointer moves, with the codes of `GhosttyMouseAction`. */
enum class MouseAction(val code: Int) {
    PRESS(0),
    RELEASE(1),
    MOTION(2)
}

/** Mouse buttons, with the codes of `GhosttyMouseButton`: the wheel is buttons 4 (up) and 5 (down). */
enum class MouseButton(val code: Int) {
    LEFT(1),
    RIGHT(2),
    MIDDLE(3),
    WHEEL_UP(4),
    WHEEL_DOWN(5)
}

/**
 * One mouse event for [TerminalEmulator.encodeMouse], at [x], [y] pixels from the top left corner of the grid.
 *
 * @property button the button that changed, null for a motion with no button held.
 * @property anyPressed whether any button is still held, which decides the motion reports of the
 *   button-event tracking mode.
 */
data class MouseInput(
    val action: MouseAction,
    val button: MouseButton?,
    val x: Float,
    val y: Float,
    val modifiers: KeyModifiers = KeyModifiers.NONE,
    val anyPressed: Boolean = false
)

/** Steps of a text selection with the mouse, with the event types of `ghostty_jni.c`. */
enum class SelectionAction(val code: Int) {
    /** The left button goes down: clears the selection, or selects a word or a line on a double or triple click. */
    PRESS(0),

    /** The pointer moves with the left button held: extends the selection. */
    DRAG(1),

    /** The left button goes up. */
    RELEASE(2)
}
