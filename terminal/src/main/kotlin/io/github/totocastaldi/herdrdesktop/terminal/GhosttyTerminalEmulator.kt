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

    override fun snapshot(): ScreenSnapshot = withHandle {
        val data = checkNotNull(GhosttyNative.nativeSnapshot(it)) { "snapshot failed" }
        SnapshotDecoder.decode(data)
    }

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
    }
}
