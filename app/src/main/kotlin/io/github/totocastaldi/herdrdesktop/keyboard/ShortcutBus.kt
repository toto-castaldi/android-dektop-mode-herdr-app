package io.github.totocastaldi.herdrdesktop.keyboard

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Shortcuts of one window, from the window's key handler to the screen that carries them out. */
class ShortcutBus {
    private val flow = MutableSharedFlow<Shortcut>(extraBufferCapacity = BUFFER)

    /** What the screens collect; a shortcut sent while no screen listens is dropped. */
    val shortcuts: SharedFlow<Shortcut> = flow

    fun send(shortcut: Shortcut) {
        flow.tryEmit(shortcut)
    }

    private companion object {
        const val BUFFER = 16
    }
}
