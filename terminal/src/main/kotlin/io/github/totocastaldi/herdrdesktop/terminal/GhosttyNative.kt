package io.github.totocastaldi.herdrdesktop.terminal

/** JNI entry points of `libherdrterminal.so` (`src/main/cpp/ghostty_jni.c`). */
// One function per operation of the terminal: keys, mouse, selection, paste, screen.
@Suppress("TooManyFunctions")
internal object GhosttyNative {
    init {
        System.loadLibrary("herdrterminal")
    }

    @JvmStatic
    external fun nativeCreate(columns: Int, rows: Int, scrollbackBytes: Long): Long

    @JvmStatic
    external fun nativeDestroy(handle: Long)

    @JvmStatic
    external fun nativeWrite(handle: Long, data: ByteArray, offset: Int, length: Int): ByteArray

    @JvmStatic
    external fun nativeResize(handle: Long, columns: Int, rows: Int, cellWidth: Int, cellHeight: Int): Int

    @JvmStatic
    external fun nativeScroll(handle: Long, delta: Int): Int

    @JvmStatic
    external fun nativeSnapshot(handle: Long): IntArray?

    /** Fills [out] and returns the length; minus the length needed if [out] is too small, -1 on failure. */
    @JvmStatic
    external fun nativeSnapshotInto(handle: Long, out: IntArray): Int

    @Suppress("LongParameterList")
    @JvmStatic
    external fun nativeEncodeKey(
        handle: Long,
        action: Int,
        key: Int,
        modifiers: Int,
        consumedModifiers: Int,
        unshiftedCodepoint: Int,
        utf8: ByteArray?
    ): ByteArray

    @JvmStatic
    external fun nativeMouseTracking(handle: Long): Boolean

    @Suppress("LongParameterList")
    @JvmStatic
    external fun nativeEncodeMouse(
        handle: Long,
        action: Int,
        button: Int,
        modifiers: Int,
        x: Float,
        y: Float,
        width: Int,
        height: Int,
        cellWidth: Int,
        cellHeight: Int,
        anyPressed: Boolean
    ): ByteArray

    /** Returns 1 when the selection changed. */
    @Suppress("LongParameterList")
    @JvmStatic
    external fun nativeSelect(
        handle: Long,
        type: Int,
        column: Int,
        row: Int,
        x: Double,
        y: Double,
        timeNanos: Long,
        columns: Int,
        cellWidth: Int,
        screenHeight: Int
    ): Int

    @JvmStatic
    external fun nativeClearSelection(handle: Long)

    /** UTF-8 of the selected text, null if nothing is selected. */
    @JvmStatic
    external fun nativeSelectionText(handle: Long): ByteArray?

    @JvmStatic
    external fun nativeEncodePaste(handle: Long, utf8: ByteArray): ByteArray
}
