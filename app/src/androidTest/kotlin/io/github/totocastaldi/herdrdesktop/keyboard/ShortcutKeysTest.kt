package io.github.totocastaldi.herdrdesktop.keyboard

import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.totocastaldi.herdrdesktop.terminal.GhosttyTerminalEmulator
import io.github.totocastaldi.herdrdesktop.terminal.TerminalIo
import io.github.totocastaldi.herdrdesktop.terminal.TerminalSession
import io.github.totocastaldi.herdrdesktop.terminal.TerminalSize
import io.github.totocastaldi.herdrdesktop.terminal.TerminalView
import java.util.Collections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Keys injected into the window as a physical keyboard sends them: shortcuts go to the app, everything else to
 * the focused terminal (REQ-DM-002).
 */
@RunWith(AndroidJUnit4::class)
class ShortcutKeysTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val sent = Collections.synchronizedList(mutableListOf<String>())
    private val shortcuts = Collections.synchronizedList(mutableListOf<Shortcut>())

    private val session = TerminalSession(
        GhosttyTerminalEmulator(TerminalSize(80, 24)),
        object : TerminalIo {
            override fun input(bytes: ByteArray) {
                sent += bytes.decodeToString().replace("\u001b", "ESC")
            }

            override fun reply(bytes: ByteArray) = Unit

            override fun resize(size: TerminalSize, cellWidthPx: Int, cellHeightPx: Int) = Unit
        }
    )

    @After
    fun close() = session.close()

    private fun show() {
        val requester = FocusRequester()
        rule.setContent {
            MaterialTheme {
                val keyboard = rememberWindowKeyboard()
                LaunchedEffect(keyboard) { keyboard.link.shortcuts.collect { shortcuts += it } }
                Box(Modifier.shortcuts(keyboard)) {
                    TerminalView(session, Modifier.size(400.dp, 300.dp).testTag("terminal"), requester)
                }
                keyboard.Overlays()
                LaunchedEffect(Unit) { requester.requestFocus() }
            }
        }
        rule.waitForIdle()
    }

    private fun key(keyCode: Int, meta: Int = 0) {
        val now = SystemClock.uptimeMillis()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
        rule.waitForIdle()
    }

    @Test
    fun terminalKeysReachTheTerminalAndShortcutsTheApp() {
        show()
        key(KeyEvent.KEYCODE_C, CTRL)
        key(KeyEvent.KEYCODE_D, CTRL)
        key(KeyEvent.KEYCODE_Z, CTRL)
        key(KeyEvent.KEYCODE_R, CTRL)
        key(KeyEvent.KEYCODE_ESCAPE)
        key(KeyEvent.KEYCODE_TAB)
        key(KeyEvent.KEYCODE_TAB, SHIFT)
        key(KeyEvent.KEYCODE_F5)
        key(KeyEvent.KEYCODE_DPAD_UP, CTRL)
        key(KeyEvent.KEYCODE_DPAD_LEFT, ALT)
        key(KeyEvent.KEYCODE_DPAD_RIGHT, CTRL or SHIFT)
        key(KeyEvent.KEYCODE_B, ALT)
        key(KeyEvent.KEYCODE_T, CTRL or SHIFT)
        key(KeyEvent.KEYCODE_RIGHT_BRACKET, CTRL or SHIFT)

        assertEquals(
            listOf(
                "\u0003", "\u0004", "\u001a", "\u0012", "ESC", "\t", "ESC[Z", "ESC[15~",
                "ESC[1;5A", "ESC[1;3D", "ESC[1;6C", "ESCb"
            ),
            sent.toList()
        )
        assertEquals(listOf(Shortcut.NEW_TAB, Shortcut.NEXT_PANE), shortcuts.toList())
    }

    @Test
    fun helpShortcutOpensThePanel() {
        show()
        key(KeyEvent.KEYCODE_SLASH, CTRL or SHIFT)
        rule.onNodeWithTag("shortcut-help").assertExists()
        assertEquals(emptyList<String>(), sent.toList())
    }

    private companion object {
        const val CTRL = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        const val SHIFT = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        const val ALT = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
    }
}
