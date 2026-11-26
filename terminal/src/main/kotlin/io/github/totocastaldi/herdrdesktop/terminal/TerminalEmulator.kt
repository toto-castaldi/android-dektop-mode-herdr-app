package io.github.totocastaldi.herdrdesktop.terminal

/**
 * Terminal emulator of one pane: parses the bytes of the program running on the core, keeps the
 * screen and the scrollback, and encodes the user's keys for the program.
 *
 * The bytes travel through `core-client`: what [write] returns and what [encodeKey] produces must
 * be sent to the program, in order. Implementations are safe to call from several threads, one
 * call at a time; [close] releases the emulator, after which every call fails.
 */
interface TerminalEmulator : AutoCloseable {
    /** Current grid size. */
    val size: TerminalSize

    /**
     * Feeds [length] bytes of program output starting at [offset] and returns the replies the
     * terminal sends back on its own (device status, mode and attribute reports), empty if none.
     */
    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): ByteArray

    /** Resizes the grid to [size], with cells of [cellWidthPx] x [cellHeightPx] pixels. */
    fun resize(size: TerminalSize, cellWidthPx: Int, cellHeightPx: Int)

    /**
     * Moves the visible screen by [rows] rows into the scrollback (negative, older lines) or back towards
     * the active area (positive); 0 returns to the active area.
     */
    fun scroll(rows: Int)

    /** Copy of the visible screen, to draw a frame. */
    fun snapshot(): ScreenSnapshot

    /** The visible screen as the renderer reads it; by default [snapshot]. */
    fun screen(): ScreenRows = snapshot()

    /** Bytes for [input] with the terminal's current keyboard modes, empty if the key sends none. */
    fun encodeKey(input: KeyInput): ByteArray
}
