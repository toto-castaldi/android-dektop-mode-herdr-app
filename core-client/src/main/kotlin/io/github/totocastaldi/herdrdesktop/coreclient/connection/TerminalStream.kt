package io.github.totocastaldi.herdrdesktop.coreclient.connection

import io.github.totocastaldi.herdrdesktop.coreclient.protocol.Frame
import io.github.totocastaldi.herdrdesktop.coreclient.protocol.FrameKind
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** What a viewer looks like, so that the core answers the program's display queries: colors are `0xRRGGBB`. */
data class DisplayFacts(val foreground: Int, val background: Int, val cellWidthPx: Int, val cellHeightPx: Int) {
    fun toJson(): JsonObject = buildJsonObject {
        put("fg", hex(foreground))
        put("bg", hex(background))
        putJsonArray("cell") {
            add(JsonPrimitive(cellWidthPx))
            add(JsonPrimitive(cellHeightPx))
        }
    }

    private fun hex(rgb: Int) = "#%06x".format(rgb and RGB_MASK)

    private companion object {
        const val RGB_MASK = 0xFFFFFF
    }
}

/** `terminal.attach` parameters: the terminal, the viewer's grid, the scrollback lines to show first. */
data class AttachRequest(
    val terminal: String,
    val columns: Int,
    val rows: Int,
    val history: Int = 0,
    val display: DisplayFacts? = null
)

/** What arrives on an attach stream. */
sealed interface StreamEvent {
    /** Bytes of the program (the first paint first); acknowledge them with [TerminalStream.consumed]. */
    class Output(val bytes: ByteArray) : StreamEvent

    /** The stream is over: the program ended ([reason], [exitCode]) or the viewer detached (both null). */
    data class Closed(val reason: String?, val exitCode: Int?) : StreamEvent
}

/**
 * Raw stream of one terminal on a framed connection (docs/design/integrations.md, I/O dei terminali).
 * [events] ends after [StreamEvent.Closed] or when the session ends.
 */
interface TerminalStream {
    val events: ReceiveChannel<StreamEvent>

    /** True when both the core and the attach path accept `AUTOMATIC_INPUT` frames. */
    val automaticInput: Boolean

    /** Sends bytes the user typed. */
    suspend fun input(bytes: ByteArray)

    /** Sends the emulator's own replies: `AUTOMATIC_INPUT` when negotiated, otherwise `DATA`. */
    suspend fun reply(bytes: ByteArray)

    suspend fun resize(columns: Int, rows: Int)

    suspend fun focus(focused: Boolean)

    suspend fun facts(display: DisplayFacts)

    /** Returns credit for [bytes] bytes of output written into the emulator. */
    suspend fun consumed(bytes: Int)

    /** Detaches the viewer; the terminal keeps running on the core. */
    suspend fun close()
}

/** Encoding of the control frames of an attach stream (schema/README.md of herdr-core, Framed mode). */
internal object StreamFrames {
    fun resize(channel: Int, columns: Int, rows: Int) = Frame(
        channel,
        FrameKind.RESIZE,
        ByteBuffer.allocate(RESIZE_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(columns.toShort()).putShort(rows.toShort()).array()
    )

    fun credit(channel: Int, bytes: Int) = Frame(
        channel,
        FrameKind.CREDIT,
        ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes).array()
    )

    fun focus(channel: Int, focused: Boolean) = Frame(channel, FrameKind.FOCUS, byteArrayOf(if (focused) 1 else 0))

    fun close(channel: Int) = Frame(channel, FrameKind.CLOSE, ByteArray(0))

    /** The reason of a `CLOSE` from the core; an empty payload answers the viewer's own `CLOSE`. */
    fun closed(payload: ByteArray): StreamEvent.Closed {
        val json = runCatching { Json.parseToJsonElement(payload.decodeToString()) as? JsonObject }.getOrNull()
        val reason = (json?.get("reason") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val exit = (json?.get("exit_code") as? JsonPrimitive)?.intOrNull
        return StreamEvent.Closed(reason, exit)
    }

    private const val RESIZE_SIZE = 4
}
