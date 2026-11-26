package io.github.totocastaldi.herdrdesktop.coreclient.install

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction

/**
 * Output of a command made of the chunks of its streams, decoded as UTF-8 per stream (a character split
 * between two chunks is kept whole) and limited to the last [maxChars] characters. Thread-safe.
 */
class CommandOutput(private val maxChars: Int = MAX_CHARS) {
    private val text = StringBuilder()
    private val decoders = mutableMapOf<Int, StreamDecoder>()

    /** Adds a chunk of stream [stream] (any id, for example 1 stdout and 2 stderr) and returns the output so far. */
    @Synchronized
    fun add(stream: Int, chunk: ByteArray): String {
        text.append(decoders.getOrPut(stream) { StreamDecoder() }.decode(chunk))
        if (text.length > maxChars) text.delete(0, text.length - maxChars)
        return text.toString()
    }

    @Synchronized
    override fun toString(): String = text.toString()

    private class StreamDecoder {
        private val decoder: CharsetDecoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        private var pending = ByteArray(0)

        fun decode(chunk: ByteArray): String {
            val input = ByteBuffer.wrap(pending + chunk)
            val out = CharBuffer.allocate(input.remaining() + 1)
            decoder.decode(input, out, false)
            pending = ByteArray(input.remaining()).also { input.get(it) }
            out.flip()
            return out.toString()
        }
    }

    private companion object {
        const val MAX_CHARS = 64 * 1024
    }
}
