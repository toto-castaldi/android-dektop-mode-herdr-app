package io.github.totocastaldi.herdrdesktop.coreclient.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameTest {
    @Test
    fun `header is channel u32 LE, kind u8, length u32 LE`() {
        val bytes = Frame(0x01020304, FrameKind.CREDIT, byteArrayOf(9, 8)).encode()
        assertArrayEquals(byteArrayOf(4, 3, 2, 1, 1, 2, 0, 0, 0, 9, 8), bytes)
    }

    @Test
    fun `decoder reassembles frames split across chunks`() {
        val a = Frame(0, FrameKind.DATA, "{\"id\":2}".toByteArray()).encode()
        val b = Frame(3, FrameKind.CLOSE, byteArrayOf()).encode()
        val all = a + b
        val decoder = FrameDecoder()

        assertTrue(decoder.feed(all.copyOfRange(0, 5)).isEmpty())
        val frames = decoder.feed(all.copyOfRange(5, all.size))

        assertEquals(2, frames.size)
        assertEquals("{\"id\":2}", frames[0].payload.decodeToString())
        assertEquals(3, frames[1].channel)
        assertEquals(FrameKind.CLOSE, frames[1].kind)
    }

    @Test(expected = ProtocolException::class)
    fun `unknown kind is a protocol error`() {
        FrameDecoder().feed(byteArrayOf(0, 0, 0, 0, 42, 0, 0, 0, 0))
    }

    @Test
    fun `line splitter returns the first line and the bytes after it`() {
        val splitter = LineSplitter()
        assertNull(splitter.feed("{\"id\":1".toByteArray()))
        val (line, rest) = checkNotNull(splitter.feed("}\n\u0000\u0001".toByteArray()))
        assertEquals("{\"id\":1}", line)
        assertArrayEquals(byteArrayOf(0, 1), rest)
    }
}
