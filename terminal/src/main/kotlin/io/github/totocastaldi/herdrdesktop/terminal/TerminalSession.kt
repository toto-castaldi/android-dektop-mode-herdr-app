package io.github.totocastaldi.herdrdesktop.terminal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Where a terminal's bytes and sizes go: implemented outside this module, by the connection to the core.
 * Calls come from the UI thread or the output thread and must not block.
 */
interface TerminalIo {
    /** Bytes the user typed, already encoded for the program. */
    fun input(bytes: ByteArray)

    /** Replies the terminal sends on its own (device status, mode and attribute reports). */
    fun reply(bytes: ByteArray)

    /** New grid size, to pass on to the program, with the cell size in pixels. */
    fun resize(size: TerminalSize, cellWidthPx: Int, cellHeightPx: Int)
}

/**
 * One pane's terminal: feeds the program's output into [emulator], sends keys and sizes to [io] and counts
 * changes in [version], so that the renderer draws a frame only when something changed.
 */
class TerminalSession(private val emulator: TerminalEmulator, private val io: TerminalIo) : AutoCloseable {
    private val changeCount = MutableStateFlow(0L)

    @Volatile
    private var scrolled = false

    @Volatile
    private var sized = false

    /** Grows at every change of the screen; the renderer waits on it instead of on every vsync. */
    val changes: StateFlow<Long> = changeCount.asStateFlow()

    /** Grows at every change of the screen. */
    val version: Long get() = changeCount.value

    val size: TerminalSize get() = emulator.size

    /** Writes program output and sends the terminal's automatic replies. */
    fun output(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        val replies = emulator.write(bytes, offset, length)
        changed()
        if (replies.isNotEmpty()) io.reply(replies)
    }

    /** Sends a key to the program and brings the screen back to the active area; false if it sends nothing. */
    fun key(input: KeyInput): Boolean {
        val bytes = emulator.encodeKey(input)
        if (bytes.isEmpty()) return false
        if (scrolled) {
            emulator.scroll(0)
            scrolled = false
            changed()
        }
        io.input(bytes)
        return true
    }

    /**
     * Resizes the grid to the pane and tells [io]: the first time always (the pane now has a size), then
     * only when the size changes.
     */
    fun resize(size: TerminalSize, cellWidthPx: Int, cellHeightPx: Int) {
        if (sized && size == emulator.size) return
        sized = true
        emulator.resize(size, cellWidthPx, cellHeightPx)
        changed()
        io.resize(size, cellWidthPx, cellHeightPx)
    }

    /** Scrolls the visible screen by [rows] (negative: into the scrollback). */
    fun scroll(rows: Int) {
        if (rows == 0) return
        emulator.scroll(rows)
        scrolled = true
        changed()
    }

    private fun changed() = changeCount.update { it + 1 }

    fun snapshot(): ScreenSnapshot = emulator.snapshot()

    /** The visible screen for the renderer, cheaper than [snapshot]. */
    fun screen(): ScreenRows = emulator.screen()

    override fun close() = emulator.close()
}
