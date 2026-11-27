package io.github.totocastaldi.herdrdesktop.terminal

import io.github.totocastaldi.herdrdesktop.terminal.MouseCommand.ContextMenu
import io.github.totocastaldi.herdrdesktop.terminal.MouseCommand.Report
import io.github.totocastaldi.herdrdesktop.terminal.MouseCommand.Scroll
import io.github.totocastaldi.herdrdesktop.terminal.MouseCommand.Select
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MouseRouterTest {
    private val router = MouseRouter()

    @Suppress("LongParameterList")
    private fun route(
        kind: PointerKind,
        buttons: Int = 0,
        tracking: Boolean = false,
        modifiers: KeyModifiers = KeyModifiers.NONE,
        scrollY: Float = 0f,
        x: Float = 15f,
        y: Float = 25f
    ) = router.route(PointerSample(kind, x, y, buttons, modifiers, scrollY, timeNanos = 7), tracking)

    private val left = HeldButtons.PRIMARY
    private val right = HeldButtons.SECONDARY

    @Test
    fun `without mouse reporting the left button selects text`() {
        assertEquals(listOf(Select(SelectionAction.PRESS, 15f, 25f, 7)), route(PointerKind.PRESS, left))
        assertEquals(listOf(Select(SelectionAction.DRAG, 40f, 25f, 7)), route(PointerKind.MOVE, left, x = 40f))
        assertEquals(listOf(Select(SelectionAction.RELEASE, 40f, 25f, 7)), route(PointerKind.RELEASE, x = 40f))
        // Moving with no button held does nothing.
        assertTrue(route(PointerKind.MOVE).isEmpty())
    }

    @Test
    fun `without mouse reporting the right button opens the context menu`() {
        assertEquals(listOf(ContextMenu(15f, 25f)), route(PointerKind.PRESS, right))
        assertTrue(route(PointerKind.RELEASE).isEmpty())
    }

    @Test
    fun `without mouse reporting the wheel scrolls three rows per notch`() {
        assertEquals(listOf(Scroll(-3)), route(PointerKind.SCROLL, scrollY = -1f))
        assertEquals(listOf(Scroll(6)), route(PointerKind.SCROLL, scrollY = 2f))
        assertTrue(route(PointerKind.SCROLL, scrollY = 0f).isEmpty())
    }

    @Test
    fun `with mouse reporting clicks go to the program`() {
        val press = route(PointerKind.PRESS, left, tracking = true)
        val release = route(PointerKind.RELEASE, tracking = true)

        val down = MouseInput(MouseAction.PRESS, MouseButton.LEFT, 15f, 25f, anyPressed = true)
        assertEquals(listOf(Report(down)), press)
        assertEquals(listOf(Report(MouseInput(MouseAction.RELEASE, MouseButton.LEFT, 15f, 25f))), release)
    }

    @Test
    fun `with mouse reporting the right and middle buttons go to the program`() {
        assertEquals(
            listOf(Report(MouseInput(MouseAction.PRESS, MouseButton.RIGHT, 15f, 25f, anyPressed = true))),
            route(PointerKind.PRESS, right, tracking = true)
        )
        route(PointerKind.RELEASE, tracking = true)
        assertEquals(
            listOf(Report(MouseInput(MouseAction.PRESS, MouseButton.MIDDLE, 15f, 25f, anyPressed = true))),
            route(PointerKind.PRESS, HeldButtons.TERTIARY, tracking = true)
        )
    }

    @Test
    fun `with mouse reporting the wheel sends one button 4 or 5 press per notch`() {
        val up = Report(MouseInput(MouseAction.PRESS, MouseButton.WHEEL_UP, 15f, 25f))
        val down = Report(MouseInput(MouseAction.PRESS, MouseButton.WHEEL_DOWN, 15f, 25f))

        assertEquals(listOf(up), route(PointerKind.SCROLL, tracking = true, scrollY = -1f))
        assertEquals(listOf(down, down), route(PointerKind.SCROLL, tracking = true, scrollY = 2f))
        // A fractional notch of a touchpad still sends one.
        assertEquals(listOf(down), route(PointerKind.SCROLL, tracking = true, scrollY = 0.2f))
    }

    @Test
    fun `with mouse reporting motion is reported with the held button or none`() {
        assertEquals(
            listOf(Report(MouseInput(MouseAction.MOTION, null, 15f, 25f))),
            route(PointerKind.MOVE, tracking = true)
        )
        route(PointerKind.PRESS, left, tracking = true)
        assertEquals(
            listOf(Report(MouseInput(MouseAction.MOTION, MouseButton.LEFT, 30f, 25f, anyPressed = true))),
            route(PointerKind.MOVE, left, tracking = true, x = 30f)
        )
    }

    @Test
    fun `modifiers are reported with the event`() {
        val ctrl = KeyModifiers.CTRL

        val press = route(PointerKind.PRESS, left, tracking = true, modifiers = ctrl)

        assertEquals(listOf(Report(MouseInput(MouseAction.PRESS, MouseButton.LEFT, 15f, 25f, ctrl, true))), press)
    }

    @Test
    fun `shift keeps selection, scrolling and the menu while the program reports the mouse`() {
        val shift = KeyModifiers.SHIFT

        assertEquals(
            listOf(Select(SelectionAction.PRESS, 15f, 25f, 7)),
            route(PointerKind.PRESS, left, tracking = true, modifiers = shift)
        )
        route(PointerKind.RELEASE, tracking = true, modifiers = shift)
        assertEquals(listOf(Scroll(-3)), route(PointerKind.SCROLL, tracking = true, modifiers = shift, scrollY = -1f))
        assertEquals(listOf(ContextMenu(15f, 25f)), route(PointerKind.PRESS, right, tracking = true, modifiers = shift))
    }

    @Test
    fun `a press sequence keeps its destination until every button is up`() {
        route(PointerKind.PRESS, left)
        // Reporting turned on while selecting: the drag and the release still belong to the selection.
        assertEquals(listOf(Select(SelectionAction.DRAG, 15f, 25f, 7)), route(PointerKind.MOVE, left, tracking = true))
        assertEquals(listOf(Select(SelectionAction.RELEASE, 15f, 25f, 7)), route(PointerKind.RELEASE, tracking = true))
        // The next press follows the new mode.
        assertTrue(route(PointerKind.PRESS, left, tracking = true).single() is Report)
    }

    @Test
    fun `a second button pressed while one is held reports only the new one`() {
        route(PointerKind.PRESS, left, tracking = true)

        val second = route(PointerKind.PRESS, left or right, tracking = true)
        val firstUp = route(PointerKind.RELEASE, right, tracking = true)

        val rightDown = MouseInput(MouseAction.PRESS, MouseButton.RIGHT, 15f, 25f, anyPressed = true)
        val leftUp = MouseInput(MouseAction.RELEASE, MouseButton.LEFT, 15f, 25f, anyPressed = true)
        assertEquals(listOf(Report(rightDown)), second)
        assertEquals(listOf(Report(leftUp)), firstUp)
    }
}
