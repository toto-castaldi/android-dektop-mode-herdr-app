package io.github.totocastaldi.herdrdesktop.workspace

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.totocastaldi.herdrdesktop.terminal.FrameObserver
import io.github.totocastaldi.herdrdesktop.terminal.GhosttyTerminalEmulator
import io.github.totocastaldi.herdrdesktop.terminal.TerminalIo
import io.github.totocastaldi.herdrdesktop.terminal.TerminalSession
import io.github.totocastaldi.herdrdesktop.terminal.TerminalSize
import io.github.totocastaldi.herdrdesktop.terminal.TerminalView
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The last block of output is drawn without waiting for more output (BUG-003). */
@RunWith(AndroidJUnit4::class)
class TerminalViewDrawTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val session = TerminalSession(
        GhosttyTerminalEmulator(TerminalSize(80, 24)),
        object : TerminalIo {
            override fun input(bytes: ByteArray) = Unit

            override fun reply(bytes: ByteArray) = Unit

            override fun resize(size: TerminalSize, cellWidthPx: Int, cellHeightPx: Int) = Unit
        }
    )

    /** Version of the screen in the last frame the pane drew. */
    @Volatile
    private var drawnVersion = -1L

    private val frames = FrameObserver { _, _, version -> drawnVersion = version }

    @After
    fun close() = session.close()

    /**
     * Output written from another thread, as the connection does, with nothing after it; returns once the pane
     * has drawn a frame of the screen with it. `waitForIdle` does not wait for that frame: it waits for the test
     * frame clock, recomposition and layout, not for the draw that follows from the output thread, so a capture
     * taken right after it could show the screen before the output (TEC-007).
     */
    private fun output(text: String) {
        val thread = Thread { session.output(text.encodeToByteArray()) }
        thread.start()
        thread.join()
        val written = session.version
        rule.waitUntil("frame with the output not drawn", FRAME_TIMEOUT_MILLIS) { drawnVersion >= written }
    }

    /** Pixels of the top part of the pane that differ from its top-right corner (the background). */
    private fun ImageBitmap.inkOnTop(): Int {
        val pixels = toPixelMap()
        val background = pixels[width - 1, 0]
        var ink = 0
        for (y in 0 until height / TOP_FRACTION) for (x in 0 until width) if (pixels[x, y] != background) ink++
        return ink
    }

    private fun ink() = rule.onNodeWithTag("pane").captureToImage().inkOnTop()

    /**
     * A row that comes back with content already drawn before (a prompt, an echo) and nothing after it:
     * the row recorded the first time was left out of a frame in between, which frees its display list.
     */
    @Test
    fun rowShownAgainIsDrawn() {
        rule.setContent { TerminalView(session, Modifier.size(400.dp, 300.dp).testTag("pane"), frameObserver = frames) }
        // The cursor stays on the bottom row: only the text can draw on the top rows.
        output("\u001b[999;1H")
        assertTrue("top rows not blank", ink() == 0)

        output(PROMPT)
        assertTrue("prompt not drawn", ink() > 0)

        output("\u001b[H\u001b[2K\u001b[999;1H")
        assertTrue("row not cleared", ink() == 0)

        output(PROMPT)
        assertTrue("prompt drawn again not shown", ink() > 0)
    }

    private companion object {
        const val TOP_FRACTION = 10
        const val FRAME_TIMEOUT_MILLIS = 5_000L
        const val PROMPT = "\u001b[Huser@host:~$ \u001b[999;1H"
    }
}
