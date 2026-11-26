package io.github.totocastaldi.herdrdesktop.coreclient.connection

import io.github.totocastaldi.herdrdesktop.coreclient.protocol.Compatibility
import io.github.totocastaldi.herdrdesktop.coreclient.protocol.CoreProtocol
import io.github.totocastaldi.herdrdesktop.coreclient.protocol.Frame
import io.github.totocastaldi.herdrdesktop.coreclient.protocol.FrameDecoder
import io.github.totocastaldi.herdrdesktop.coreclient.protocol.FrameKind
import io.github.totocastaldi.herdrdesktop.coreclient.protocol.HelloResult
import io.github.totocastaldi.herdrdesktop.coreclient.protocol.LineSplitter
import io.github.totocastaldi.herdrdesktop.coreclient.protocol.ProtocolException
import io.github.totocastaldi.herdrdesktop.coreclient.protocol.RpcError
import io.github.totocastaldi.herdrdesktop.coreclient.protocol.RpcException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Result of the `hello` handshake on a fresh [CoreStream]. */
sealed interface HandshakeResult {
    class Ready(val session: CoreSession, val hello: HelloResult) : HandshakeResult

    data object CoreMissing : HandshakeResult

    data class Incompatible(val compatibility: Compatibility) : HandshakeResult

    /** The stream ended or carried something that is not the protocol. */
    data class Failed(val reason: String) : HandshakeResult
}

/**
 * JSON-RPC session with a core in framed mode. Created by [handshake]; [closed] completes
 * when the stream ends.
 */
class CoreSession private constructor(
    private val stream: CoreStream,
    scope: CoroutineScope,
    leftover: ByteArray,
    private val features: List<String> = emptyList()
) : CoreRpc {
    private val ids = AtomicLong(FIRST_ID)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()
    private val writeLock = Mutex()
    private val decoder = FrameDecoder()

    /**
     * What arrived on every channel but 0, queued until its reader takes it: the stream of a terminal
     * ([attach]) or the lines of a notification channel ([lines]). Frames may arrive before the answer that
     * opened the channel, so a queue is made by the first frame or by its reader, whichever comes first.
     */
    private val inbound = ConcurrentHashMap<Int, Channel<StreamEvent>>()

    /** Channels the viewer detached from, whose frames are dropped until the core's closing `CLOSE`. */
    private val detached = ConcurrentHashMap.newKeySet<Int>()

    /** Completes with the cause when the stream to the core ends. */
    val closed = CompletableDeferred<Throwable>()

    private val reader: Job = scope.launch {
        val cause = runCatching {
            var chunk: ByteArray? = leftover
            while (chunk != null) {
                decoder.feed(chunk).forEach(::route)
                chunk = stream.read()
            }
        }.exceptionOrNull()
        finish(cause ?: ProtocolException("Stream closed"))
    }

    /**
     * The only routing of incoming frames: answers of channel 0 go to their caller, data and `CLOSE` of the
     * other channels to their queue; frames of a channel the viewer detached from are dropped until the
     * core's closing `CLOSE`. Control frames the app does not use are ignored.
     */
    private fun route(frame: Frame) {
        val channel = frame.channel
        when {
            channel == Frame.RPC_CHANNEL -> if (frame.kind == FrameKind.DATA) dispatch(frame)

            channel in detached -> if (frame.kind == FrameKind.CLOSE) detached.remove(channel)

            frame.kind == FrameKind.DATA -> inbound(channel).trySend(StreamEvent.Output(frame.payload))

            frame.kind == FrameKind.CLOSE -> inbound(channel).let {
                it.trySend(StreamFrames.closed(frame.payload))
                // The queue stays until its reader is done with it, which may not have started yet.
                it.close()
            }

            else -> Unit
        }
    }

    private fun inbound(channel: Int): Channel<StreamEvent> = inbound.getOrPut(channel) {
        Channel<StreamEvent>(Channel.UNLIMITED).also { if (closed.isCompleted) it.close() }
    }

    /** Splits the channel's data into lines and gives the core back the credit of every chunk read. */
    override fun lines(channel: Int): Flow<String> = flow {
        var buffer = ByteArray(0)
        for (event in inbound(channel)) {
            val chunk = (event as? StreamEvent.Output)?.bytes ?: break
            buffer += chunk
            var newline = buffer.indexOf(NEWLINE)
            while (newline >= 0) {
                emit(String(buffer, 0, newline, Charsets.UTF_8))
                buffer = buffer.copyOfRange(newline + 1, buffer.size)
                newline = buffer.indexOf(NEWLINE)
            }
            send(StreamFrames.credit(channel, chunk.size))
        }
    }

    /** Completes the call answered by an RPC frame; notifications and unknown ids are ignored. */
    private fun dispatch(frame: Frame) {
        val message = Json.parseToJsonElement(frame.payload.decodeToString()) as? JsonObject
        val id = (message?.get("id") as? JsonPrimitive)?.longOrNull
        val waiter = id?.let { pending.remove(it) } ?: return
        val error = message?.get("error") as? JsonObject
        if (error != null) {
            waiter.completeExceptionally(RpcException(RpcError.from(error)))
        } else {
            waiter.complete(message?.get("result") ?: JsonNull)
        }
    }

    private suspend fun send(frame: Frame) {
        writeLock.withLock { stream.write(frame.encode()) }
    }

    override suspend fun attach(request: AttachRequest): TerminalStream {
        val params = buildJsonObject {
            put("terminal", request.terminal)
            put("cols", request.columns)
            put("rows", request.rows)
            if (request.history > 0) put("history", request.history)
            request.display?.let { put("display", it.toJson()) }
        }
        val result = call("terminal.attach", params) as JsonObject
        val channel = checkNotNull((result["channel"] as? JsonPrimitive)?.intOrNull) { "attach returned no channel" }
        val automatic =
            AUTOMATIC_INPUT in features && (result["automatic_input"] as? JsonPrimitive)?.booleanOrNull == true
        return SessionStream(channel, inbound(channel), automatic)
    }

    private inner class SessionStream(
        private val channel: Int,
        override val events: Channel<StreamEvent>,
        override val automaticInput: Boolean
    ) : TerminalStream {
        override suspend fun input(bytes: ByteArray) {
            if (bytes.isNotEmpty()) send(Frame(channel, FrameKind.DATA, bytes))
        }

        override suspend fun reply(bytes: ByteArray) {
            if (bytes.isEmpty()) return
            send(Frame(channel, if (automaticInput) FrameKind.AUTOMATIC_INPUT else FrameKind.DATA, bytes))
        }

        override suspend fun resize(columns: Int, rows: Int) = send(StreamFrames.resize(channel, columns, rows))

        override suspend fun focus(focused: Boolean) = send(StreamFrames.focus(channel, focused))

        override suspend fun facts(display: DisplayFacts) =
            send(Frame(channel, FrameKind.FACTS, display.toJson().toString().encodeToByteArray()))

        override suspend fun consumed(bytes: Int) {
            if (bytes > 0) send(StreamFrames.credit(channel, bytes))
        }

        override suspend fun close() {
            if (inbound[channel] !== events) return
            // Closed for send: the core already closed the stream, nothing to detach from.
            if (events.isClosedForSend || this@CoreSession.closed.isCompleted) {
                inbound.remove(channel)
                return
            }
            // Marked first, so that a frame arriving meanwhile is dropped instead of opening a new entry.
            detached += channel
            inbound.remove(channel)
            events.close()
            runCatching { send(StreamFrames.close(channel)) }
        }
    }

    private fun finish(cause: Throwable) {
        pending.values.forEach { it.completeExceptionally(cause) }
        pending.clear()
        closed.complete(cause)
        inbound.values.forEach { it.close() }
    }

    /** Calls [method] on channel 0 and returns its `result`; throws [RpcException] on a JSON-RPC error. */
    override suspend fun call(method: String, params: JsonObject?): JsonElement {
        val id = ids.getAndIncrement()
        val waiter = CompletableDeferred<JsonElement>()
        pending[id] = waiter
        if (closed.isCompleted) finish(ProtocolException("Session closed"))
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }
        val frame = Frame(Frame.RPC_CHANNEL, FrameKind.DATA, request.toString().encodeToByteArray())
        writeLock.withLock { stream.write(frame.encode()) }
        return waiter.await()
    }

    /** True if the core answers `server.status` within [timeoutMillis]; a JSON-RPC error is an answer too. */
    suspend fun ping(timeoutMillis: Long): Boolean = withTimeoutOrNull(timeoutMillis) {
        val result = runCatching { call("server.status", null) }
        result.isSuccess || result.exceptionOrNull() is RpcException
    } == true

    suspend fun close() {
        reader.cancel()
        stream.close()
        finish(ProtocolException("Session closed"))
    }

    companion object {
        private const val HELLO_ID = 1L
        private const val AUTOMATIC_INPUT = "automatic_input"
        private const val FIRST_ID = 2L
        private const val NEWLINE = '\n'.code.toByte()

        /**
         * Sends `hello {generation, framed: true}` as a plain JSON line, checks compatibility and
         * switches to framed mode. The stream is closed unless the result is [HandshakeResult.Ready].
         */
        suspend fun handshake(stream: CoreStream, scope: CoroutineScope): HandshakeResult {
            val result = try {
                when (val line = readHello(stream)) {
                    is HelloLine.Line -> evaluate(line, stream, scope)

                    HelloLine.Eof -> if (stream.exitStatus() == CORE_NOT_FOUND_EXIT) {
                        HandshakeResult.CoreMissing
                    } else {
                        HandshakeResult.Failed("Stream closed before hello")
                    }
                }
            } catch (e: CancellationException) {
                stream.close()
                throw e
            } catch (e: IOException) {
                HandshakeResult.Failed(e.message ?: "hello failed")
            } catch (e: ProtocolException) {
                HandshakeResult.Failed(e.message ?: "hello failed")
            }
            if (result !is HandshakeResult.Ready) stream.close()
            return result
        }

        private sealed interface HelloLine {
            class Line(val text: String, val rest: ByteArray) : HelloLine

            data object Eof : HelloLine
        }

        private suspend fun readHello(stream: CoreStream): HelloLine {
            stream.write((CoreProtocol.helloRequest(HELLO_ID) + "\n").encodeToByteArray())
            val splitter = LineSplitter()
            while (true) {
                val chunk = stream.read() ?: return HelloLine.Eof
                val (line, rest) = splitter.feed(chunk) ?: continue
                return HelloLine.Line(line, rest)
            }
        }

        private fun evaluate(line: HelloLine.Line, stream: CoreStream, scope: CoroutineScope): HandshakeResult {
            val message = runCatching { Json.parseToJsonElement(line.text) as? JsonObject }.getOrNull()
            val error = message?.get("error") as? JsonObject
            val hello = message?.get("result")?.let { runCatching { CoreProtocol.parseHello(it) }.getOrNull() }
            return when {
                message == null -> HandshakeResult.Failed("Not a herdr-core response")

                error != null -> HandshakeResult.Incompatible(CoreProtocol.checkError(RpcError.from(error)))

                hello == null -> HandshakeResult.Failed("Malformed hello response")

                else -> when (val compatibility = CoreProtocol.check(hello)) {
                    Compatibility.Compatible -> HandshakeResult.Ready(
                        CoreSession(stream, scope, line.rest, hello.features),
                        hello
                    )

                    else -> HandshakeResult.Incompatible(compatibility)
                }
            }
        }
    }
}
