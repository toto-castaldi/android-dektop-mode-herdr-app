package io.github.totocastaldi.herdrdesktop.terminal

/**
 * Terminal emulator of one pane: parses the bytes of the program running on the core, keeps the
 * screen and the scrollback, and encodes the user's keys for the program.
 *
 * The bytes travel through `core-client`: what [write] returns and what [encodeKey] produces must
 * be sent to the program, in order. Implementations are safe to call from several threads, one
 * call at a time; [close] releases the emulator, after which every call fails.
 */
// One function per operation of the terminal: keys, mouse, selection, paste, screen.
@Suppress("TooManyFunctions")
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

    /** Whether the program turned on mouse reporting (X10, normal, button-event or any-event tracking). */
    val mouseTracking: Boolean get() = false

    /**
     * Bytes reporting [input] to the program with its current tracking mode and format, empty if the event is
     * not reported; the pane is [widthPx] x [heightPx] pixels.
     */
    fun encodeMouse(input: MouseInput, widthPx: Int, heightPx: Int): ByteArray = ByteArray(0)

    /**
     * Applies a step of a mouse selection at [x], [y] pixels of a pane [heightPx] pixels high, at
     * [timeNanos] (monotonic clock, for double and triple clicks); true if the selection changed.
     */
    fun select(action: SelectionAction, x: Float, y: Float, timeNanos: Long, heightPx: Int): Boolean = false

    /** The selected text, null if nothing is selected. */
    fun selectionText(): String? = null

    /** Removes the selection. */
    fun clearSelection() = Unit

    /** Bytes that paste [text] into the program, framed for bracketed paste when the program asked for it. */
    fun encodePaste(text: String): ByteArray = ByteArray(0)
}
