package io.github.totocastaldi.herdrdesktop.coreclient.connection

import io.github.totocastaldi.herdrdesktop.coreclient.protocol.Frame
import io.github.totocastaldi.herdrdesktop.coreclient.protocol.FrameDecoder
import io.github.totocastaldi.herdrdesktop.coreclient.protocol.FrameKind
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** In-memory stand-in for `herdr-core connect`, speaking the hello line and framed JSON-RPC. */
class FakeCoreStream(
    private val helloReply: String? = HELLO_OK,
    private val exitStatus: Int? = null,
    var answersPings: Boolean = true
) : CoreStream {
    private val output = Channel<ByteArray>(Channel.UNLIMITED)
    private val decoder = FrameDecoder()
    private var helloDone = false
    var closed = false
        private set
    val calls = mutableListOf<String>()

    init {
        if (helloReply == null) output.close()
    }

    override suspend fun read(): ByteArray? = output.receiveCatching().getOrNull()

    override suspend fun write(bytes: ByteArray) {
        if (!helloDone) {
            helloDone = true
            if (helloReply != null) output.send((helloReply + "\n").toByteArray())
            return
        }
        for (frame in decoder.feed(bytes)) {
            val request = Json.parseToJsonElement(frame.payload.decodeToString()).jsonObject
            val method = request.getValue("method").jsonPrimitive.content
            calls += method
            if (method == "server.status" && !answersPings) continue
            val reply = """{"jsonrpc":"2.0","id":${request.getValue("id")},"result":{"ok":true}}"""
            output.send(Frame(0, FrameKind.DATA, reply.toByteArray()).encode())
        }
    }

    override suspend fun exitStatus(): Int? = exitStatus

    override suspend fun close() {
        closed = true
        output.close()
    }

    /** Simulates the connection dropping. */
    fun drop() {
        output.close()
    }

    companion object {
        const val HELLO_OK =
            """{"jsonrpc":"2.0","id":1,"result":{"core":{"name":"vps"},"framed":true,"generation":1,""" +
                """"methods":["hello","server.status"],"version":"0.1.22"}}"""
        const val HELLO_FUTURE = """{"jsonrpc":"2.0","id":1,"result":{"framed":true,"generation":2,""" +
            """"methods":["hello","server.status"],"version":"0.9.0"}}"""
    }
}
