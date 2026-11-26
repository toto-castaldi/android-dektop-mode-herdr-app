package io.github.totocastaldi.herdrdesktop.coreclient.connection

import io.github.totocastaldi.herdrdesktop.coreclient.protocol.Frame
import io.github.totocastaldi.herdrdesktop.coreclient.protocol.FrameDecoder
import io.github.totocastaldi.herdrdesktop.coreclient.protocol.FrameKind
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Attach streams on a framed session: routing by channel and the encoding of the viewer's frames. */
class TerminalStreamTest {
    /** A core that answers `terminal.attach` on [CHANNEL] and records every frame the app sends. */
    private class StreamCore(private val features: String, private val attachAutomatic: Boolean) : CoreStream {
        val output = Channel<ByteArray>(Channel.UNLIMITED)
        private val decoder = FrameDecoder()
        private var helloDone = false
        val sent = mutableListOf<Frame>()
        var attachParams: JsonObject? = null

        override suspend fun read(): ByteArray? = output.receiveCatching().getOrNull()

        override suspend fun write(bytes: ByteArray) {
            if (!helloDone) {
                helloDone = true
                output.send((hello(features) + "\n").toByteArray())
                return
            }
            for (frame in decoder.feed(bytes)) {
                if (frame.channel != Frame.RPC_CHANNEL) {
                    sent += frame
                    continue
                }
                val request = Json.parseToJsonElement(frame.payload.decodeToString()).jsonObject
                attachParams = request["params"]?.jsonObject
                // Like the core: stream bytes may follow the reply at once, in the same chunk.
                val reply = """{"jsonrpc":"2.0","id":${request.getValue("id")},"result":{"channel":$CHANNEL,""" +
                    """"terminal":{},"resize_notifications":false,"automatic_input":$attachAutomatic}}"""
                output.send(
                    Frame(0, FrameKind.DATA, reply.toByteArray()).encode() +
                        Frame(CHANNEL, FrameKind.DATA, "paint".toByteArray()).encode()
                )
            }
        }

        fun push(frame: Frame) {
            output.trySend(frame.encode())
        }

        override suspend fun exitStatus(): Int? = null

        override suspend fun close() {
            output.close()
        }
    }

    private suspend fun open(core: StreamCore, scope: CoroutineScope): CoreSession =
        (CoreSession.handshake(core, scope) as HandshakeResult.Ready).session

    @Test
    fun `attach sends the size, history and display, and gets the first paint`() = runTest {
        val core = StreamCore(FEATURES_AUTOMATIC, attachAutomatic = true)
        val session = open(core, backgroundScope)

        val stream = session.attach(AttachRequest("t7", 120, 40, 500, DisplayFacts(0xFFFFFF, 0x101010, 9, 18)))

        val params = core.attachParams!!
        assertEquals("t7", params.getValue("terminal").jsonPrimitive.content)
        assertEquals("120", params.getValue("cols").jsonPrimitive.content)
        assertEquals("40", params.getValue("rows").jsonPrimitive.content)
        assertEquals("500", params.getValue("history").jsonPrimitive.content)
        assertEquals("#101010", params.getValue("display").jsonObject.getValue("bg").jsonPrimitive.content)
        val paint = stream.events.receive() as StreamEvent.Output
        assertEquals("paint", paint.bytes.decodeToString())
    }

    @Test
    fun `keys, replies, sizes, focus and credit are framed on the stream channel`() = runTest {
        val core = StreamCore(FEATURES_AUTOMATIC, attachAutomatic = true)
        val stream = open(core, backgroundScope).attach(AttachRequest("t7", 80, 24))

        stream.input("ls\r".toByteArray())
        stream.reply("\u001b[0n".toByteArray())
        stream.resize(100, 30)
        stream.focus(true)
        stream.consumed(4096)

        assertEquals(
            listOf(FrameKind.DATA, FrameKind.AUTOMATIC_INPUT, FrameKind.RESIZE, FrameKind.FOCUS, FrameKind.CREDIT),
            core.sent.map { it.kind }
        )
        assertTrue(core.sent.all { it.channel == CHANNEL })
        assertArrayEquals("ls\r".toByteArray(), core.sent[0].payload)
        val resize = ByteBuffer.wrap(core.sent[2].payload).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(100, resize.short.toInt())
        assertEquals(30, resize.short.toInt())
        assertArrayEquals(byteArrayOf(1), core.sent[3].payload)
        assertEquals(4096, ByteBuffer.wrap(core.sent[4].payload).order(ByteOrder.LITTLE_ENDIAN).int)
    }

    @Test
    fun `replies go as data unless both hello and attach announce automatic input`() = runTest {
        val notInHello = StreamCore(FEATURES_PLAIN, attachAutomatic = true)
        val notInAttach = StreamCore(FEATURES_AUTOMATIC, attachAutomatic = false)

        open(notInHello, backgroundScope).attach(AttachRequest("t7", 80, 24)).reply(byteArrayOf(1))
        open(notInAttach, backgroundScope).attach(AttachRequest("t7", 80, 24)).reply(byteArrayOf(1))

        assertEquals(FrameKind.DATA, notInHello.sent.single().kind)
        assertEquals(FrameKind.DATA, notInAttach.sent.single().kind)
    }

    @Test
    fun `the core's close ends the stream with the program's exit`() = runTest {
        val core = StreamCore(FEATURES_PLAIN, attachAutomatic = false)
        val stream = open(core, backgroundScope).attach(AttachRequest("t7", 80, 24))
        stream.events.receive()

        core.push(Frame(CHANNEL, FrameKind.CLOSE, """{"reason":"exited","exit_code":3,"signal":null}""".toByteArray()))

        assertEquals(StreamEvent.Closed("exited", 3), stream.events.receive())
        assertTrue(stream.events.receiveCatching().isClosed)
        stream.close()
        assertTrue("no CLOSE is sent for a stream the core closed", core.sent.isEmpty())
    }

    @Test
    fun `the viewer detaching sends close and drops what still arrives`() = runTest {
        val core = StreamCore(FEATURES_PLAIN, attachAutomatic = false)
        val session = open(core, backgroundScope)
        val stream = session.attach(AttachRequest("t7", 80, 24))
        stream.events.receive()

        stream.close()
        core.push(Frame(CHANNEL, FrameKind.DATA, "late".toByteArray()))
        core.push(Frame(CHANNEL, FrameKind.CLOSE, ByteArray(0)))

        assertEquals(FrameKind.CLOSE, core.sent.single().kind)
        assertTrue(stream.events.receiveCatching().isClosed)
    }

    @Test
    fun `the session ending closes its streams`() = runTest {
        val core = StreamCore(FEATURES_PLAIN, attachAutomatic = false)
        val stream = open(core, backgroundScope).attach(AttachRequest("t7", 80, 24))
        stream.events.receive()

        core.output.close()

        assertTrue(stream.events.receiveCatching().isClosed)
    }

    @Test
    fun `a terminal stream and a notification channel share the session, each with its own credit and close`() =
        runTest {
            val core = StreamCore(FEATURES_PLAIN, attachAutomatic = false)
            val session = open(core, backgroundScope)
            val stream = session.attach(AttachRequest("t7", 80, 24))
            val lines = mutableListOf<String>()
            val collector = backgroundScope.launch { session.lines(EVENTS).collect { lines += it } }

            core.push(Frame(EVENTS, FrameKind.DATA, "{\"a\":1}\n{\"b\"".toByteArray()))
            core.push(Frame(CHANNEL, FrameKind.DATA, "echo".toByteArray()))
            core.push(Frame(EVENTS, FrameKind.DATA, ":2}\n".toByteArray()))

            assertEquals("paint", (stream.events.receive() as StreamEvent.Output).bytes.decodeToString())
            assertEquals("echo", (stream.events.receive() as StreamEvent.Output).bytes.decodeToString())
            stream.consumed(4)
            val eventBytes = "{\"a\":1}\n{\"b\":2}\n".length
            withTimeout(WAIT_MILLIS) { while (credited(core, EVENTS) < eventBytes) yield() }
            assertEquals(listOf("""{"a":1}""", """{"b":2}"""), lines)
            assertEquals(eventBytes, credited(core, EVENTS))
            assertEquals(4, credited(core, CHANNEL))

            // Closing the notification channel ends its lines only; the terminal keeps streaming.
            core.push(Frame(EVENTS, FrameKind.CLOSE, ByteArray(0)))
            core.push(Frame(CHANNEL, FrameKind.DATA, "more".toByteArray()))
            withTimeout(WAIT_MILLIS) { collector.join() }
            assertEquals("more", (stream.events.receive() as StreamEvent.Output).bytes.decodeToString())

            // Detaching the viewer closes only the terminal's channel.
            stream.close()
            assertEquals(listOf(CHANNEL), core.sent.filter { it.kind == FrameKind.CLOSE }.map { it.channel })
        }

    /** Bytes the app gave back as credit on [channel]. */
    private fun credited(core: StreamCore, channel: Int) = core.sent
        .filter { it.kind == FrameKind.CREDIT && it.channel == channel }
        .sumOf { ByteBuffer.wrap(it.payload).order(ByteOrder.LITTLE_ENDIAN).int }

    private companion object {
        const val CHANNEL = 5
        const val EVENTS = 9
        const val WAIT_MILLIS = 5_000L
        const val FEATURES_AUTOMATIC = """["framed","attach","automatic_input"]"""
        const val FEATURES_PLAIN = """["framed","attach"]"""

        fun hello(features: String) =
            """{"jsonrpc":"2.0","id":1,"result":{"framed":true,"generation":1,"features":$features,""" +
                """"methods":["hello","server.status"],"version":"0.1.22"}}"""
    }
}
