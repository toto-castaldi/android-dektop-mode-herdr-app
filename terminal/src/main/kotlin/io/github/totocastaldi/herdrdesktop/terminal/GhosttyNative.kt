package io.github.totocastaldi.herdrdesktop.terminal

/** JNI entry points of `libherdrterminal.so` (`src/main/cpp/ghostty_jni.c`). */
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
}
