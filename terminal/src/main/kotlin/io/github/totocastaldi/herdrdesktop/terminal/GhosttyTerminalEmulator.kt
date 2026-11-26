package io.github.totocastaldi.herdrdesktop.terminal

/**
 * [TerminalEmulator] backed by libghostty-vt (ADR-001) through `libherdrterminal.so`, available
 * only on arm64-v8a Android devices.
 */
class GhosttyTerminalEmulator(initialSize: TerminalSize, scrollbackBytes: Long = DEFAULT_SCROLLBACK_BYTES) :
    TerminalEmulator {
    private val lock = Any()
    private var handle: Long = GhosttyNative.nativeCreate(initialSize.columns, initialSize.rows, scrollbackBytes)

    init {
        check(handle != 0L) { "libghostty-vt terminal could not be created" }
    }

    override var size: TerminalSize = initialSize
        private set

    override fun write(data: ByteArray, offset: Int, length: Int): ByteArray {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) {
            "range $offset+$length outside 0..${data.size}"
        }
        return withHandle { GhosttyNative.nativeWrite(it, data, offset, length) }
    }

    override fun resize(size: TerminalSize, cellWidthPx: Int, cellHeightPx: Int) {
        require(cellWidthPx > 0 && cellHeightPx > 0) { "cell size must be positive" }
        withHandle {
            val result = GhosttyNative.nativeResize(it, size.columns, size.rows, cellWidthPx, cellHeightPx)
            check(result == 0) { "resize failed with libghostty-vt result $result" }
            this.size = size
        }
    }

    override fun scroll(rows: Int) {
        withHandle {
            val result = GhosttyNative.nativeScroll(it, rows)
            check(result == 0) { "scroll failed with libghostty-vt result $result" }
        }
    }

    override fun snapshot(): ScreenSnapshot = SnapshotDecoder.decode(raw())

    /** Arrays reused by [screen] across frames. */
    private var screenBuffer = IntArray(0)
    private var startsBuffer = IntArray(0)

    /**
     * Read in place, without a cell object per cell, in arrays reused across calls: the result is valid
     * until the next call.
     */
    override fun screen(): RawScreen = withHandle {
        var length = GhosttyNative.nativeSnapshotInto(it, screenBuffer)
        if (length < -1) {
            screenBuffer = IntArray(-length + -length / GROWTH_DIVISOR)
            length = GhosttyNative.nativeSnapshotInto(it, screenBuffer)
        }
        check(length >= 0) { "snapshot failed" }
        RawScreen(screenBuffer, startsBuffer).also { screen -> startsBuffer = screen.starts }
    }

    private fun raw(): IntArray = withHandle { checkNotNull(GhosttyNative.nativeSnapshot(it)) { "snapshot failed" } }

    override fun encodeKey(input: KeyInput): ByteArray = withHandle {
        GhosttyNative.nativeEncodeKey(
            it,
            input.action.code,
            input.key.code,
            input.modifiers.bits,
            input.consumedModifiers.bits,
            input.unshiftedCodepoint,
            input.text.takeIf { text -> text.isNotEmpty() }?.encodeToByteArray()
        )
    }

    override fun close() {
        synchronized(lock) {
            if (handle != 0L) {
                GhosttyNative.nativeDestroy(handle)
                handle = 0L
            }
        }
    }

    private inline fun <T> withHandle(block: (Long) -> T): T = synchronized(lock) {
        check(handle != 0L) { "terminal closed" }
        block(handle)
    }

    companion object {
        /** Scrollback memory limit per terminal. */
        const val DEFAULT_SCROLLBACK_BYTES: Long = 10L * 1024 * 1024

        /** A grown snapshot buffer gets a quarter more than needed, for the next frames. */
        private const val GROWTH_DIVISOR = 4
    }
}
