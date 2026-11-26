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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
class CoreSession private constructor(private val stream: CoreStream, scope: CoroutineScope, leftover: ByteArray) :
    CoreRpc {
    private val ids = AtomicLong(FIRST_ID)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()
    private val writeLock = Mutex()
    private val decoder = FrameDecoder()

    /** Completes with the cause when the stream to the core ends. */
    val closed = CompletableDeferred<Throwable>()

    private val reader: Job = scope.launch {
        val cause = runCatching {
            var chunk: ByteArray? = leftover
            while (chunk != null) {
                decoder.feed(chunk).filter {
                    it.channel == Frame.RPC_CHANNEL && it.kind == FrameKind.DATA
                }.forEach(::dispatch)
                chunk = stream.read()
            }
        }.exceptionOrNull()
        finish(cause ?: ProtocolException("Stream closed"))
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

    private fun finish(cause: Throwable) {
        pending.values.forEach { it.completeExceptionally(cause) }
        pending.clear()
        closed.complete(cause)
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
        private const val FIRST_ID = 2L

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
                    Compatibility.Compatible -> HandshakeResult.Ready(CoreSession(stream, scope, line.rest), hello)
                    else -> HandshakeResult.Incompatible(compatibility)
                }
            }
        }
    }
}
