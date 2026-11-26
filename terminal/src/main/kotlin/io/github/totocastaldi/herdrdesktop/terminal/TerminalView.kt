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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
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

/** Rows scrolled by one notch of the mouse wheel. */
private const val WHEEL_ROWS = 3

/**
 * Pane showing [session]: draws its screen once per vsync when it changed, sizes the grid to the pane,
 * sends the physical keyboard's keys to the program while focused and scrolls the scrollback with the
 * mouse wheel.
 */
@Composable
fun TerminalView(
    session: TerminalSession,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() },
    onFocusChanged: (Boolean) -> Unit = {},
    frameObserver: FrameObserver? = null
) {
    val density = LocalDensity.current
    val painter = remember(session, density) { CellPainter(with(density) { FONT_SIZE.toPx() }) }
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
        .pointerInput(session) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    when (event.type) {
                        PointerEventType.Press -> focusRequester.requestFocus()

                        PointerEventType.Scroll -> {
                            val delta = event.changes.sumOf { it.scrollDelta.y.toDouble() }
                            session.scroll((delta * WHEEL_ROWS).roundToInt())
                            event.changes.forEach { it.consume() }
                        }

                        else -> Unit
                    }
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
