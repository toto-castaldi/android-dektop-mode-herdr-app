package io.github.totocastaldi.herdrdesktop.coreclient.connection

import io.github.totocastaldi.herdrdesktop.coreclient.protocol.Frame
import io.github.totocastaldi.herdrdesktop.coreclient.protocol.FrameDecoder
import io.github.totocastaldi.herdrdesktop.coreclient.protocol.FrameKind
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** A stream whose core side is driven by the test. */
private class ScriptedStream : CoreStream {
    val output = Channel<ByteArray>(Channel.UNLIMITED)
    val written = mutableListOf<Frame>()
    private val decoder = FrameDecoder()
    private var helloDone = false

    override suspend fun read(): ByteArray? = output.receiveCatching().getOrNull()

    override suspend fun write(bytes: ByteArray) {
        if (!helloDone) {
            helloDone = true
            output.send((FakeCoreStream.HELLO_OK + "\n").toByteArray())
        } else {
            written += decoder.feed(bytes)
        }
    }

    override suspend fun exitStatus(): Int? = null

    override suspend fun close() {
        output.close()
    }
}

class CoreSessionChannelTest {
    @Test
    fun `lines of a notification channel are delivered in order and their bytes credited back`() = runTest {
        val stream = ScriptedStream()
        val session = (CoreSession.handshake(stream, backgroundScope) as HandshakeResult.Ready).session
        // A line split across two frames, then a frame with two lines, then the channel ends.
        stream.output.send(Frame(1, FrameKind.DATA, "{\"a\":".toByteArray()).encode())
        stream.output.send(Frame(1, FrameKind.DATA, "1}\n{\"b\":2}\n{\"c\":3}\n".toByteArray()).encode())
        stream.output.send(Frame(1, FrameKind.CLOSE, ByteArray(0)).encode())

        val lines = session.lines(1).toList()

        assertEquals(listOf("{\"a\":1}", "{\"b\":2}", "{\"c\":3}"), lines)
        val credits = stream.written.filter { it.kind == FrameKind.CREDIT && it.channel == 1 }
            .map { ByteBuffer.wrap(it.payload).order(ByteOrder.LITTLE_ENDIAN).int }
        assertEquals(listOf(5, 19), credits)
    }
}
