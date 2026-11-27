@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.totocastaldi.herdrdesktop.terminal

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first

/**
 * Called on the UI thread after every frame drawn for [session], with the frame time given by the
 * Choreographer (`System.nanoTime()` clock, the vsync of `FrameMetrics`) and the [TerminalSession.version]
 * the frame shows; used by the instrumented tests to measure latency and frame rate (REQ-NFR-003).
 */
fun interface FrameObserver {
    fun onFrame(session: TerminalSession, vsyncNanos: Long, version: Long)

    /** A key pressed in [session], with its `KeyEvent.getEventTime()` (`uptimeMillis` clock). */
    fun onKeyDown(session: TerminalSession, eventTimeMillis: Long) = Unit
}

/** Cell size of the terminal font, in pixels. */
data class CellMetrics(val widthPx: Int, val heightPx: Int)

/** Size of the terminal font. */
private val FONT_SIZE = 14.sp

/** Nanoseconds in a millisecond, for the time of pointer events. */
private const val NANOS_PER_MILLI = 1_000_000L

/**
 * Pane showing [session]: draws its screen once per vsync when it changed, sizes the grid to the pane and
 * sends the physical keyboard's keys to the program while focused. With the mouse (REQ-DM-003, [MouseRouter])
 * a click takes the focus, the left button selects text, the wheel scrolls the scrollback and the right button
 * calls [onContextMenu] with the position in the pane; when the program turned mouse reporting on, clicks,
 * motion and the wheel are reported to it instead, unless Shift is held.
 */
@Composable
@Suppress("LongParameterList")
fun TerminalView(
    session: TerminalSession,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() },
    onFocusChanged: (Boolean) -> Unit = {},
    onContextMenu: (Offset) -> Unit = {},
    frameObserver: FrameObserver? = null
) {
    val contextMenu by rememberUpdatedState(onContextMenu)
    val density = LocalDensity.current
    val assets = LocalContext.current.assets
    val painter = remember(session, density) {
        CellPainter(with(density) { FONT_SIZE.toPx() }, TerminalFonts.get(assets))
    }
    var frame by remember(session) { mutableLongStateOf(0L) }
    var focused by remember(session) { mutableStateOf(false) }

    // One draw per vsync at most, and only when the screen changed (SPIKE-003); no frame is awaited while
    // nothing changes, so an idle terminal costs nothing.
    LaunchedEffect(session) {
        var drawn = -1L
        while (true) {
            session.changes.first { it != drawn }
            withFrameNanos { now ->
                drawn = session.version
                frame = now
            }
        }
    }

    val content = modifier
        .onSizeChanged { size ->
            val m = painter.metrics
            session.resize(TerminalSize.fit(size.width, size.height, m.widthPx, m.heightPx), m.widthPx, m.heightPx)
        }
        .focusRequester(focusRequester)
        .onFocusChanged {
            focused = it.isFocused
            onFocusChanged(it.isFocused)
        }
        // Before the input method: a physical keyboard's keys go straight to the program, without the round
        // trip through the IME process that would add to the key-to-echo latency (REQ-NFR-003).
        .onInterceptKeyBeforeSoftKeyboard { event ->
            if (event.type == KeyEventType.KeyDown) frameObserver?.onKeyDown(session, event.nativeKeyEvent.eventTime)
            sendKey(session, event.nativeKeyEvent, event.type)
        }
        .focusable()
        .pointerHoverIcon(PointerIcon.Text)
        .pointerInput(session) {
            val router = MouseRouter()
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type == PointerEventType.Press) focusRequester.requestFocus()
                    val sample = event.sample() ?: continue
                    val commands = router.route(sample, session.mouseTracking)
                    commands.forEach { session.run(it, size) { at -> contextMenu(at) } }
                    if (commands.isNotEmpty()) event.changes.forEach { it.consume() }
                }
            }
        }
        // The canvas is the window's: without the clip a pane would paint over the whole screen.
        .clipToBounds()
        .drawBehind {
            val vsync = frame
            val version = session.version
            val screen = session.screen()
            drawIntoCanvas { painter.draw(it.nativeCanvas, screen, focused, size.width, size.height) }
            frameObserver?.onFrame(session, vsync, version)
        }
    Box(content)
}

/** Carries out [command] in a pane of [size]; [contextMenu] opens the menu at a position of the pane. */
private fun TerminalSession.run(command: MouseCommand, size: IntSize, contextMenu: (Offset) -> Unit) {
    when (command) {
        is MouseCommand.Report -> mouse(command.input, size.width, size.height)
        is MouseCommand.Select -> select(command.action, command.x, command.y, command.timeNanos, size.height)
        is MouseCommand.Scroll -> scroll(command.rows)
        is MouseCommand.ContextMenu -> contextMenu(Offset(command.x, command.y))
    }
}

private val POINTER_KINDS = mapOf(
    PointerEventType.Press to PointerKind.PRESS,
    PointerEventType.Release to PointerKind.RELEASE,
    PointerEventType.Move to PointerKind.MOVE,
    PointerEventType.Scroll to PointerKind.SCROLL
)

/** The event as [MouseRouter] reads it; null for touch and stylus, which only take the focus. */
private fun PointerEvent.sample(): PointerSample? {
    val change = changes.firstOrNull()?.takeIf { it.type == PointerType.Mouse }
    val kind = POINTER_KINDS[type]
    if (change == null || kind == null) return null
    var held = 0
    if (buttons.isPrimaryPressed) held = held or HeldButtons.PRIMARY
    if (buttons.isSecondaryPressed) held = held or HeldButtons.SECONDARY
    if (buttons.isTertiaryPressed) held = held or HeldButtons.TERTIARY
    var modifiers = KeyModifiers.NONE
    if (keyboardModifiers.isShiftPressed) modifiers += KeyModifiers.SHIFT
    if (keyboardModifiers.isCtrlPressed) modifiers += KeyModifiers.CTRL
    if (keyboardModifiers.isAltPressed) modifiers += KeyModifiers.ALT
    if (keyboardModifiers.isMetaPressed) modifiers += KeyModifiers.SUPER
    return PointerSample(
        kind,
        change.position.x,
        change.position.y,
        held,
        modifiers,
        change.scrollDelta.y,
        change.uptimeMillis * NANOS_PER_MILLI
    )
}

private fun sendKey(session: TerminalSession, event: AndroidKeyEvent, type: KeyEventType): Boolean {
    val action = when {
        type == KeyEventType.KeyUp -> KeyAction.RELEASE
        event.repeatCount > 0 -> KeyAction.REPEAT
        else -> KeyAction.PRESS
    }
    val input = AndroidKeys.input(event.keyCode, event.metaState, action) { event.getUnicodeChar(it) } ?: return false
    session.key(input)
    // Releases and modifier-only keys send nothing but still belong to the terminal.
    return true
}
