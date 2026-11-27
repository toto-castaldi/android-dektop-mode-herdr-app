@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.totocastaldi.herdrdesktop.keyboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** A window's keyboard shortcuts ([Shortcut]) as its screens see them, and how the help panel opens. */
class KeyboardLink(val shortcuts: Flow<Shortcut> = emptyFlow(), val onHelp: (() -> Unit)? = null)

/**
 * The keyboard of one window (REQ-DM-002): whether the agents sidebar and the help panel are shown, and the
 * shortcuts for the screens, which carry out those on spaces, tabs and panes.
 */
class WindowKeyboard(sidebarState: MutableState<Boolean>, helpState: MutableState<Boolean>) {
    var sidebar by sidebarState
        private set
    var help by helpState
        private set
    private val bus = ShortcutBus()
    private var sidebarFocus by mutableIntStateOf(0)

    /** Attached to the sidebar: showing it from the keyboard moves the keyboard to its agents. */
    val sidebarRequester = FocusRequester()

    val link = KeyboardLink(bus.shortcuts) { help = true }

    fun onShortcut(shortcut: Shortcut) {
        when (shortcut) {
            Shortcut.HELP -> help = true

            Shortcut.TOGGLE_SIDEBAR -> {
                sidebar = !sidebar
                if (sidebar) sidebarFocus++ else bus.send(Shortcut.REFOCUS)
            }

            else -> bus.send(shortcut)
        }
    }

    /** The help panel, when shown, and the keyboard moving to the sidebar. */
    @Composable
    fun Overlays() {
        LaunchedEffect(sidebarFocus) { if (sidebarFocus > 0) runCatching { sidebarRequester.requestFocus() } }
        if (help) {
            ShortcutHelpDialog {
                help = false
                bus.send(Shortcut.REFOCUS)
            }
        }
    }
}

@Composable
fun rememberWindowKeyboard(): WindowKeyboard {
    val sidebar = rememberSaveable { mutableStateOf(true) }
    val help = rememberSaveable { mutableStateOf(false) }
    return remember { WindowKeyboard(sidebar, help) }
}

/** Shortcuts are taken before the focused terminal sees the key; every other key reaches the terminal. */
fun Modifier.shortcuts(keyboard: WindowKeyboard) = onPreInterceptKeyBeforeSoftKeyboard { event ->
    val native = event.nativeKeyEvent
    val owned = Shortcuts.owns(native.keyCode, native.metaState)
    if (owned && event.type == KeyEventType.KeyDown) {
        Shortcuts.match(native.keyCode, native.metaState, native.repeatCount)?.let(keyboard::onShortcut)
    }
    owned
}
