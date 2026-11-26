package io.github.totocastaldi.herdrdesktop.workspace

import io.github.totocastaldi.herdrdesktop.coreclient.connection.AttachRequest
import io.github.totocastaldi.herdrdesktop.coreclient.connection.CoreRpc
import io.github.totocastaldi.herdrdesktop.coreclient.connection.DisplayFacts
import io.github.totocastaldi.herdrdesktop.coreclient.connection.StreamEvent
import io.github.totocastaldi.herdrdesktop.coreclient.connection.TerminalStream
import io.github.totocastaldi.herdrdesktop.coreclient.protocol.RpcException
import io.github.totocastaldi.herdrdesktop.terminal.FrameObserver
import io.github.totocastaldi.herdrdesktop.terminal.GhosttyTerminalEmulator
import io.github.totocastaldi.herdrdesktop.terminal.TerminalIo
import io.github.totocastaldi.herdrdesktop.terminal.TerminalSession
import io.github.totocastaldi.herdrdesktop.terminal.TerminalSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How the panes of a tab reach their terminals: null when the screen only shows the layout (tests). */
class PaneHost(val rpc: CoreRpc, val scope: CoroutineScope, val frameObserver: FrameObserver? = null)

/** Where a pane's terminal stream is. */
sealed interface PaneTerminalState {
    data object Attaching : PaneTerminalState

    data object Live : PaneTerminalState

    /** The program ended ([reason] as reported by the core) or the stream closed. */
    data class Ended(val reason: String?, val exitCode: Int?) : PaneTerminalState

    /** The terminal could not be attached, for example because it no longer exists. */
    data class Failed(val reason: String) : PaneTerminalState
}

/**
 * A pane's terminal: an emulator fed by the `terminal.attach` stream of [address] on [rpc]
 * (docs/design/integrations.md, I/O dei terminali). It attaches once the pane has a size, sends keys,
 * automatic replies and sizes in order, and returns credit for every chunk written into the emulator.
 * Closing it detaches the viewer: the terminal keeps running on the core.
 */
internal class PaneTerminal(private val rpc: CoreRpc, val address: String, scope: CoroutineScope) : TerminalIo {
    val session = TerminalSession(GhosttyTerminalEmulator(INITIAL_SIZE), this)
    private val mutableState = MutableStateFlow<PaneTerminalState>(PaneTerminalState.Attaching)
    val state: StateFlow<PaneTerminalState> = mutableState.asStateFlow()

    private val firstSize = CompletableDeferred<Pair<TerminalSize, DisplayFacts>>()
    private val outbound = Channel<suspend (TerminalStream) -> Unit>(Channel.UNLIMITED)

    private val job = scope.launch {
        try {
            run()
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception
        ) {
            // A dropped session (ProtocolException, IOException) or an emulator failure; never the content.
            mutableState.value = PaneTerminalState.Failed(e.javaClass.simpleName)
        }
    }

    private suspend fun run() = coroutineScope {
        val (size, display) = firstSize.await()
        val stream = try {
            rpc.attach(AttachRequest(address, size.columns, size.rows, HISTORY_LINES, display))
        } catch (e: RpcException) {
            mutableState.value = PaneTerminalState.Failed(e.error.kind ?: e.message.orEmpty())
            return@coroutineScope
        }
        mutableState.value = PaneTerminalState.Live
        // Unconfined: a key queued from the UI thread is written right there, without waking another thread
        // (part of the key-to-echo latency, REQ-NFR-003); the order of the queue is kept.
        val writer = launch(Dispatchers.Unconfined) {
            for (send in outbound) {
                // A failed write means the session is gone: the reader sees the end of the stream.
                if (runCatching { send(stream) }.isFailure) break
            }
        }
        try {
            // Unconfined: output is written into the emulator on the thread that read it from the
            // connection, without waking another one (part of the key-to-echo latency, REQ-NFR-003).
            withContext(Dispatchers.Unconfined) { readOutput(stream) }
            if (mutableState.value == PaneTerminalState.Live) mutableState.value = PaneTerminalState.Ended(null, null)
        } finally {
            writer.cancel()
            withContext(NonCancellable) { runCatching { stream.close() } }
        }
    }

    /** Writes the stream's output into the emulator until the stream ends, acknowledging it in batches. */
    private suspend fun readOutput(stream: TerminalStream) {
        for (event in stream.events) {
            // Everything already received is written before one credit and one redraw.
            var next: StreamEvent? = event
            var consumed = 0
            while (next != null) {
                when (next) {
                    is StreamEvent.Output -> {
                        session.output(next.bytes)
                        consumed += next.bytes.size
                    }

                    is StreamEvent.Closed ->
                        mutableState.value =
                            PaneTerminalState.Ended(next.reason, next.exitCode)
                }
                // Credit goes back well before the core's window of 256 KiB runs out.
                if (consumed >= CREDIT_BATCH_BYTES) {
                    stream.consumed(consumed)
                    consumed = 0
                }
                next = stream.events.tryReceive().getOrNull()
            }
            stream.consumed(consumed)
        }
    }

    override fun input(bytes: ByteArray) {
        outbound.trySend { it.input(bytes) }
    }

    override fun reply(bytes: ByteArray) {
        outbound.trySend { it.reply(bytes) }
    }

    override fun resize(size: TerminalSize, cellWidthPx: Int, cellHeightPx: Int) {
        if (firstSize.isCompleted) {
            outbound.trySend { it.resize(size.columns, size.rows) }
        } else {
            val screen = session.snapshot()
            firstSize.complete(size to DisplayFacts(screen.foreground, screen.background, cellWidthPx, cellHeightPx))
        }
    }

    fun focus(focused: Boolean) {
        outbound.trySend { it.focus(focused) }
    }

    /** Detaches from the terminal and releases the emulator once the stream has stopped. */
    suspend fun close() {
        outbound.close()
        try {
            job.cancel()
            job.join()
        } finally {
            session.close()
        }
    }

    private companion object {
        /** Grid of the emulator before the pane has been measured. */
        val INITIAL_SIZE = TerminalSize(80, 24)

        /** Scrollback lines the core sends with the first paint. */
        const val HISTORY_LINES = 5_000

        /** Largest amount of output acknowledged with one `CREDIT`. */
        const val CREDIT_BATCH_BYTES = 64 * 1024
    }
}
