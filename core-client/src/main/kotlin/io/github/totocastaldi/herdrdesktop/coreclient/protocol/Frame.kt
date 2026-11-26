package io.github.totocastaldi.herdrdesktop.coreclient.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Frame kinds of the herdr-core framed mode. */
enum class FrameKind(val code: Int) {
    DATA(0),
    CREDIT(1),
    CLOSE(2),
    RESIZE(3),
    FOCUS(4),
    FACTS(5),
    AUTOMATIC_INPUT(6)
    ;

    companion object {
        fun of(code: Int): FrameKind? = entries.firstOrNull { it.code == code }
    }
}

/** One frame: `[channel u32 LE][kind u8][len u32 LE][payload]`. Channel 0 carries JSON-RPC. */
class Frame(val channel: Int, val kind: FrameKind, val payload: ByteArray) {
    fun encode(): ByteArray = ByteBuffer.allocate(HEADER_SIZE + payload.size)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(channel)
        .put(kind.code.toByte())
        .putInt(payload.size)
        .put(payload)
        .array()

    companion object {
        const val HEADER_SIZE = 9
        const val MAX_PAYLOAD = 16 * 1024 * 1024
        const val RPC_CHANNEL = 0
    }
}

class ProtocolException(message: String) : Exception(message)

/** Incremental decoder: feed arbitrary chunks, get complete frames. */
class FrameDecoder {
    private var buffer = ByteArray(0)

    fun feed(chunk: ByteArray): List<Frame> {
        buffer += chunk
        val frames = mutableListOf<Frame>()
        while (buffer.size >= Frame.HEADER_SIZE) {
            val header = ByteBuffer.wrap(buffer, 0, Frame.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val channel = header.int
            val code = header.get().toUByte().toInt()
            val length = header.int
            if (length < 0 || length > Frame.MAX_PAYLOAD) throw ProtocolException("Frame too large: $length")
            val kind = FrameKind.of(code) ?: throw ProtocolException("Unknown frame kind: $code")
            if (buffer.size < Frame.HEADER_SIZE + length) break
            frames += Frame(channel, kind, buffer.copyOfRange(Frame.HEADER_SIZE, Frame.HEADER_SIZE + length))
            buffer = buffer.copyOfRange(Frame.HEADER_SIZE + length, buffer.size)
        }
        return frames
    }
}

/** Splits a byte stream into newline-terminated lines, keeping the bytes that follow. */
class LineSplitter {
    private var buffer = ByteArray(0)

    /** Returns the first complete line (without `\n`) and the bytes after it, or null if incomplete. */
    fun feed(chunk: ByteArray): Pair<String, ByteArray>? {
        buffer += chunk
        val newline = buffer.indexOf('\n'.code.toByte())
        if (newline < 0) {
            if (buffer.size > Frame.MAX_PAYLOAD) throw ProtocolException("Line too long")
            return null
        }
        val line = String(buffer, 0, newline, Charsets.UTF_8)
        val rest = buffer.copyOfRange(newline + 1, buffer.size)
        buffer = ByteArray(0)
        return line to rest
    }
}
