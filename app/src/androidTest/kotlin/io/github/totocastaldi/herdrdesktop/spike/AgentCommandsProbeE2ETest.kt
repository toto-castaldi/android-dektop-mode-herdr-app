package io.github.totocastaldi.herdrdesktop.spike

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.totocastaldi.herdrdesktop.HerdrApp
import io.github.totocastaldi.herdrdesktop.coreclient.connection.CoreRpc
import io.github.totocastaldi.herdrdesktop.coreclient.connection.MachineStatus
import io.github.totocastaldi.herdrdesktop.coreclient.connection.status
import io.github.totocastaldi.herdrdesktop.coreclient.machine.Machine
import io.github.totocastaldi.herdrdesktop.coreclient.machine.MachineAuth
import io.github.totocastaldi.herdrdesktop.coreclient.protocol.RpcException
import java.io.File
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SPIKE-004 exploratory probe, not part of any suite: compares two channels that carry a request from an agent
 * on a core to the app and the reply back (labels on the agent's terminal, an opaque document of requests).
 * `-e spHost <host> -e spUser <user> [-e spPort <port>] -e spCase <case>`; skipped without them.
 * Cases: `key` (only writes the public part of the test key `e2e-spike004` to `spike004-e2e.pub` in the app's
 * external files directory), `labels`, `doc`, `labels-cli`, `burst-labels`, `burst-doc` (one responder),
 * `shell` (the agent's shell recipe), `two-labels`, `two-doc` (two sessions answering, as two devices would),
 * `two-claim` (labels, but a responder answers only after claiming the request with compare-and-set on the
 * document `herdr-desktop-android.claims`), `closed-labels`, `closed-doc` (the
 * agent sends while no client is connected; a responder connects later). The agent side is
 * `assets/spike004_agent.py`, run in a terminal of the core; results go to `spike004-<case>.jsonl` in the
 * external files directory and to logcat (tag `Spike004`).
 */
@RunWith(AndroidJUnit4::class)
class AgentCommandsProbeE2ETest {
    private val args = InstrumentationRegistry.getArguments()
    private val host: String? = args.getString("spHost")
    private val user: String? = args.getString("spUser")
    private val port = args.getString("spPort")?.toInt() ?: Machine.DEFAULT_PORT
    private val case: String? = args.getString("spCase")
    private val count = args.getString("spCount")?.toInt() ?: DEFAULT_COUNT
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HerdrApp
    private val container get() = app.container
    private val added = mutableListOf<Machine>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun cleanUp() {
        scope.coroutineContext[Job]?.cancel()
        added.forEach {
            container.connections.forget(it.id)
            container.machines.remove(it.id)
        }
    }

    @Test
    fun probe() = runBlocking {
        assumeTrue("spCase not given", case != null)
        if (case == "key") {
            val key = container.keys.publicKey(KEY_ALIAS) ?: container.keys.generate(KEY_ALIAS)
            File(app.getExternalFilesDir(null), "spike004-e2e.pub").writeText(key.authorizedKeysLine() + "\n")
            return@runBlocking
        }
        assumeTrue("spHost and spUser not given", host != null && user != null)
        val c = checkNotNull(case)
        val two = c.startsWith("two-")
        val closed = c.startsWith("closed-")
        val claim = c == "two-claim"
        val mode = if (claim) "labels" else c.removePrefix("two-").removePrefix("closed-")
        val first = connect("spike004-a")
        val responders = mutableListOf<Job>()
        if (!closed) responders += scope.launch { Responder("a", first, claim).run() }
        if (two) responders += scope.launch { Responder("b", connect("spike004-b"), claim).run() }
        // Let the subscriptions start before the agent sends.
        delay(SETTLE_MILLIS)

        val timeout = if (closed) CLOSED_TIMEOUT_S else REPLY_TIMEOUT_S
        val out = "/tmp/spike004-${System.currentTimeMillis()}.jsonl"
        val script = "/tmp/spike004_agent.py"
        val source = InstrumentationRegistry.getInstrumentation().context.assets.open("spike004_agent.py")
            .use { it.readBytes() }
        first.call(
            "file.write",
            buildJsonObject {
                put("path", script)
                put("data", Base64.getEncoder().encodeToString(source))
            }
        )
        val n = if (closed) 1 else count
        val started = first.call(
            "terminal.run",
            buildJsonObject {
                put("line", "python3 $script $mode $n $timeout $out")
                put("no_notify", true)
            }
        ).jsonObject.getValue("address").jsonPrimitive.content
        if (closed) {
            // The agent's request waits on the core with no client; then a client connects and answers it.
            delay(CLOSED_DELAY_MILLIS)
            responders += scope.launch { Responder("late", connect("spike004-late")).run() }
        }
        try {
            withTimeout(RUN_TIMEOUT_MILLIS) {
                while (state(first, started) == "live") delay(POLL_MILLIS)
            }
            val text = readFile(first, out)
            File(app.getExternalFilesDir(null), "spike004-$c.jsonl").writeText(text)
            text.lines().filter { it.contains("summary") }.forEach { Log.i(TAG, "$c $it") }
        } finally {
            runCatching { first.call("terminal.kill", buildJsonObject { put("terminal", started) }) }
            runCatching { first.call("terminal.rm", buildJsonObject { put("terminal", started) }) }
            responders.forEach { it.cancelAndJoin() }
        }
    }

    /** Stand-in for the app: answers every request it sees, with its [name] in the reply. */
    private class Responder(private val name: String, private val rpc: CoreRpc, private val claim: Boolean = false) {
        private val answered = mutableSetOf<String>()
        private val docLock = Mutex()

        suspend fun run() {
            val result = rpc.call("events.subscribe", JsonObject(emptyMap())).jsonObject
            // Requests left while no client was connected: in the snapshot and in the document.
            result["terminals"]?.jsonArray?.forEach { t ->
                val o = t.jsonObject
                val labels = o["labels"]?.jsonObject ?: return@forEach
                answerLabel(o.getValue("address").jsonPrimitive.content, labels)
            }
            answerDoc()
            val channel = checkNotNull(result["channel"]?.jsonPrimitive?.longOrNull).toInt()
            rpc.lines(channel).collect { line ->
                val params = Json.parseToJsonElement(line).jsonObject["params"]?.jsonObject ?: return@collect
                when (params["type"]?.jsonPrimitive?.content) {
                    "labels" -> answerLabel(
                        params.getValue("terminal").jsonPrimitive.content,
                        params.getValue("data").jsonObject.getValue("labels").jsonObject
                    )

                    "doc" -> if (params.getValue("data").jsonObject["name"]?.jsonPrimitive?.content == DOC) answerDoc()
                }
            }
        }

        @Suppress("ReturnCount")
        private suspend fun answerLabel(terminal: String, labels: JsonObject) {
            val raw = labels[DO]?.jsonPrimitive?.contentOrNull ?: return
            val id = Json.parseToJsonElement(raw).jsonObject.getValue("id").jsonPrimitive.content
            val replied = labels[RE]?.jsonPrimitive?.contentOrNull
                ?.let { Json.parseToJsonElement(it).jsonObject["id"]?.jsonPrimitive?.content }
            if (id == replied || !answered.add("$terminal/$id")) return
            if (claim && !claim(terminal, id)) return
            val reply = buildJsonObject {
                put("id", id)
                put("ok", true)
                put("by", name)
            }
            rpc.call(
                "terminal.label",
                buildJsonObject {
                    put("terminal", terminal)
                    put("labels", buildJsonObject { put(RE, reply.toString()) })
                }
            )
        }

        /** True if this responder recorded [id] as the last request of [terminal] handled, false if another did. */
        @Suppress("ReturnCount")
        private suspend fun claim(terminal: String, id: String): Boolean {
            repeat(MAX_ATTEMPTS) {
                val got = rpc.call("doc.get", buildJsonObject { put("name", CLAIMS) }).jsonObject
                val value = got["value"]?.takeIf { it != JsonNull }?.jsonObject ?: JsonObject(emptyMap())
                if (value[terminal]?.jsonPrimitive?.content == id) return false
                try {
                    rpc.call(
                        "doc.set",
                        buildJsonObject {
                            put("name", CLAIMS)
                            put("value", JsonObject(value + (terminal to JsonPrimitive(id))))
                            put("version", got.getValue("version"))
                        }
                    )
                    return true
                } catch (e: RpcException) {
                    if (e.error.kind != "conflict") throw e
                }
            }
            return false
        }

        /** Moves every pending request to the replies with one compare-and-set write; rereads on conflict. */
        private suspend fun answerDoc() = docLock.withLock {
            repeat(MAX_ATTEMPTS) {
                val got = rpc.call("doc.get", buildJsonObject { put("name", DOC) }).jsonObject
                val value = got["value"]?.takeIf { it != JsonNull }?.jsonObject ?: return
                val requests = value["requests"]?.jsonArray.orEmpty()
                if (requests.isEmpty()) return
                val replies = value["replies"]?.jsonArray.orEmpty() + requests.map { r ->
                    buildJsonObject {
                        put("id", r.jsonObject.getValue("id"))
                        put("from", r.jsonObject.getValue("from"))
                        put("ok", true)
                        put("by", name)
                    }
                }
                val next = JsonObject(
                    value + mapOf(
                        "requests" to JsonArray(emptyList()),
                        "replies" to JsonArray(replies.takeLast(MAX_REPLIES))
                    )
                )
                try {
                    rpc.call(
                        "doc.set",
                        buildJsonObject {
                            put("name", DOC)
                            put("value", next)
                            put("version", got.getValue("version"))
                        }
                    )
                    return
                } catch (e: RpcException) {
                    if (e.error.kind != "conflict") throw e
                }
            }
        }
    }

    private fun connect(name: String): CoreRpc {
        container.machines.machines.value.filter { it.name == name }.forEach {
            container.connections.forget(it.id)
            container.machines.remove(it.id)
        }
        container.keys.publicKey(KEY_ALIAS) ?: container.keys.generate(KEY_ALIAS)
        val m = container.machines.add(name, checkNotNull(host), port, checkNotNull(user), MachineAuth.Key(KEY_ALIAS))
        added += m
        container.connections.connect(m.id)
        waitFor { container.connections.status(m.id) is MachineStatus.HostKeyUnconfirmed }
        val presented = (container.connections.status(m.id) as MachineStatus.HostKeyUnconfirmed).presented
        container.connections.confirmHostKey(m.id, presented)
        waitFor { container.connections.sessions.value[m.id] != null }
        return checkNotNull(container.connections.sessions.value[m.id])
    }

    private fun waitFor(done: () -> Boolean) {
        val end = System.currentTimeMillis() + CONNECT_TIMEOUT_MILLIS
        while (!done()) {
            check(System.currentTimeMillis() < end) { "timeout" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private suspend fun state(rpc: CoreRpc, terminal: String): String? = rpc.call(
        "terminal.get",
        buildJsonObject { put("terminal", terminal) }
    ).jsonObject["state"]?.jsonPrimitive?.content

    private suspend fun readFile(rpc: CoreRpc, path: String): String =
        rpc.call("file.read", buildJsonObject { put("path", path) }).jsonObject.getValue("text").jsonPrimitive.content

    private companion object {
        const val TAG = "Spike004"
        const val KEY_ALIAS = "e2e-spike004"
        const val DO = "herdr-desktop-android.do"
        const val RE = "herdr-desktop-android.re"
        const val DOC = "herdr-desktop-android.do"
        const val CLAIMS = "herdr-desktop-android.claims"
        const val DEFAULT_COUNT = 30
        const val REPLY_TIMEOUT_S = 5
        const val CLOSED_TIMEOUT_S = 30
        const val CLOSED_DELAY_MILLIS = 5_000L
        const val SETTLE_MILLIS = 2_000L
        const val RUN_TIMEOUT_MILLIS = 300_000L
        const val CONNECT_TIMEOUT_MILLIS = 40_000L
        const val POLL_MILLIS = 200L
        const val MAX_ATTEMPTS = 5
        const val MAX_REPLIES = 64
    }
}
