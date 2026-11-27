package io.github.totocastaldi.herdrdesktop.terminal

import kotlin.math.abs
import kotlin.math.roundToInt

/** Mouse buttons held, as bits of [PointerSample.buttons]. */
object HeldButtons {
    const val PRIMARY = 1
    const val SECONDARY = 2
    const val TERTIARY = 4
}

/** What happened to the pointer. */
enum class PointerKind {
    PRESS,
    RELEASE,
    MOVE,
    SCROLL
}

/**
 * One pointer event over a terminal, independent of Compose: position in pixels of the pane, the buttons
 * held after the event ([HeldButtons] bits), the keyboard modifiers and, for [PointerKind.SCROLL], the
 * wheel notches (negative: up, towards older lines).
 */
data class PointerSample(
    val kind: PointerKind,
    val x: Float,
    val y: Float,
    val buttons: Int = 0,
    val modifiers: KeyModifiers = KeyModifiers.NONE,
    val scrollY: Float = 0f,
    val timeNanos: Long = 0
)

/** What a pointer event does to a terminal. */
sealed interface MouseCommand {
    /** Report to the program, which turned mouse reporting on. */
    data class Report(val input: MouseInput) : MouseCommand

    /** Select text. */
    data class Select(val action: SelectionAction, val x: Float, val y: Float, val timeNanos: Long) : MouseCommand

    /** Move the visible screen into the scrollback (negative) or back. */
    data class Scroll(val rows: Int) : MouseCommand

    /** Open the context menu at the pointer. */
    data class ContextMenu(val x: Float, val y: Float) : MouseCommand
}

/**
 * Decides what the mouse does over a terminal (REQ-DM-003). When the program turned mouse reporting on,
 * clicks, motion and the wheel go to the program, unless Shift is held; otherwise the left button selects
 * text, the right button opens the context menu and the wheel scrolls the scrollback. A press sequence keeps
 * its destination until every button is up. One router per pane, called on the UI thread.
 */
class MouseRouter {
    private var held = 0
    private var reporting = false
    private var selecting = false

    fun route(sample: PointerSample, tracking: Boolean): List<MouseCommand> {
        val toProgram = tracking && KeyModifiers.SHIFT !in sample.modifiers
        return when (sample.kind) {
            PointerKind.SCROLL -> scroll(sample, toProgram)
            PointerKind.PRESS -> press(sample, toProgram)
            PointerKind.RELEASE -> release(sample)
            PointerKind.MOVE -> move(sample, toProgram)
        }
    }

    private fun scroll(sample: PointerSample, toProgram: Boolean): List<MouseCommand> = when {
        sample.scrollY == 0f -> emptyList()

        !toProgram -> listOf(MouseCommand.Scroll((sample.scrollY * WHEEL_ROWS).roundToInt()))

        else -> {
            val button = if (sample.scrollY < 0) MouseButton.WHEEL_UP else MouseButton.WHEEL_DOWN
            val notches = abs(sample.scrollY).roundToInt().coerceAtLeast(1)
            val input = MouseInput(MouseAction.PRESS, button, sample.x, sample.y, sample.modifiers, held != 0)
            List(notches) { MouseCommand.Report(input) }
        }
    }

    private fun press(sample: PointerSample, toProgram: Boolean): List<MouseCommand> {
        if (held == 0) reporting = toProgram
        val pressed = sample.buttons and held.inv()
        held = sample.buttons
        return buttonsOf(pressed).mapNotNull { button ->
            when {
                reporting -> MouseCommand.Report(
                    MouseInput(MouseAction.PRESS, button, sample.x, sample.y, sample.modifiers, anyPressed = true)
                )

                button == MouseButton.LEFT -> {
                    selecting = true
                    MouseCommand.Select(SelectionAction.PRESS, sample.x, sample.y, sample.timeNanos)
                }

                button == MouseButton.RIGHT -> MouseCommand.ContextMenu(sample.x, sample.y)

                else -> null
            }
        }
    }

    private fun release(sample: PointerSample): List<MouseCommand> {
        val released = held and sample.buttons.inv()
        held = sample.buttons
        val commands = buttonsOf(released).mapNotNull { button ->
            when {
                reporting -> MouseCommand.Report(
                    MouseInput(MouseAction.RELEASE, button, sample.x, sample.y, sample.modifiers, held != 0)
                )

                button == MouseButton.LEFT && selecting -> {
                    selecting = false
                    MouseCommand.Select(SelectionAction.RELEASE, sample.x, sample.y, sample.timeNanos)
                }

                else -> null
            }
        }
        if (held == 0) reporting = false
        return commands
    }

    private fun move(sample: PointerSample, toProgram: Boolean): List<MouseCommand> = when {
        held != 0 && reporting -> listOf(
            MouseCommand.Report(
                MouseInput(
                    MouseAction.MOTION,
                    buttonsOf(held).first(),
                    sample.x,
                    sample.y,
                    sample.modifiers,
                    anyPressed = true
                )
            )
        )

        held != 0 && selecting ->
            listOf(MouseCommand.Select(SelectionAction.DRAG, sample.x, sample.y, sample.timeNanos))

        held == 0 && toProgram -> listOf(
            MouseCommand.Report(MouseInput(MouseAction.MOTION, null, sample.x, sample.y, sample.modifiers))
        )

        else -> emptyList()
    }

    private fun buttonsOf(bits: Int): List<MouseButton> = buildList {
        if (bits and HeldButtons.PRIMARY != 0) add(MouseButton.LEFT)
        if (bits and HeldButtons.SECONDARY != 0) add(MouseButton.RIGHT)
        if (bits and HeldButtons.TERTIARY != 0) add(MouseButton.MIDDLE)
    }

    companion object {
        /** Rows scrolled by one notch of the mouse wheel. */
        const val WHEEL_ROWS = 3
    }
}
