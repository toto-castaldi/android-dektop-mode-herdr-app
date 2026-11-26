package io.github.totocastaldi.herdrdesktop.workspace

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.Window
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.totocastaldi.herdrdesktop.HerdrApp
import io.github.totocastaldi.herdrdesktop.MainActivity
import io.github.totocastaldi.herdrdesktop.coreclient.connection.CoreRpc
import io.github.totocastaldi.herdrdesktop.coreclient.connection.MachineStatus
import io.github.totocastaldi.herdrdesktop.coreclient.connection.status
import io.github.totocastaldi.herdrdesktop.coreclient.machine.Machine
import io.github.totocastaldi.herdrdesktop.coreclient.machine.MachineAuth
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.PaneLayout
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.WorkspaceClient
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.WorkspaceCodec
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.WorkspaceState
import io.github.totocastaldi.herdrdesktop.terminal.FrameObserver
import io.github.totocastaldi.herdrdesktop.terminal.TerminalSession
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * REQ-NFR-003 on the device, with the method of the requirement: key-to-echo latency in vim and frame rate
 * during 15 s of truecolor and Unicode output, against a real herdr-core on the local network. Without a
 * Compose test rule, so that the app draws on the display's own vsync. Same arguments and test key as
 * [PaneE2ETest] plus `-e wsPerf true` (skipped without it) and `[-e wsKeys <n>]`. Results are logged with
 * the tag `PaneE2E`: times and counters only, never terminal content.
 */
@RunWith(AndroidJUnit4::class)
class TerminalPerformanceE2ETest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val args = InstrumentationRegistry.getArguments()
    private val host: String? = args.getString("wsHost")
    private val user: String? = args.getString("wsUser")
    private val port = args.getString("wsPort")?.toInt() ?: Machine.DEFAULT_PORT
    private val display = args.getString("wsDisplay")?.toInt()
    private val app = instrumentation.targetContext.applicationContext as HerdrApp
    private val container get() = app.container
    private var scenario: ActivityScenario<MainActivity>? = null
    private var machine: Machine? = null
    private val spaces = E2eSpaces()

    /** Every frame drawn by a terminal pane: session, draw time (`System.nanoTime()`), version shown. */
    private val drawn = CopyOnWriteArrayList<Triple<TerminalSession, Long, Long>>()
    private val sessions = ConcurrentHashMap.newKeySet<TerminalSession>()

    /** `KeyEvent.getEventTime()` of the keys the panes received. */
    private val keyTimes = CopyOnWriteArrayList<Long>()

    @After
    fun cleanUp() {
        container.frameObserver = null
        machine?.let { m ->
            container.connections.sessions.value[m.id]?.let { rpc ->
                runBlocking { spaces.cleanUp(rpc) }
            }
            container.connections.forget(m.id)
            container.machines.remove(m.id)
        }
        scenario?.close()
    }

    @Test
    fun keyToEchoLatencyAndFrameRateMeetTheThresholds() {
        assumeTrue("wsPerf not given", host != null && user != null && args.getString("wsPerf") == "true")
        val target = connect()
        container.workspaces.perform(target.id) { createSpace() }
        val pane = awaitUntil {
            val ws = (container.workspaces.state(target.id).value as? WorkspaceState.Ready)?.workspace
            ws?.spaces?.singleOrNull()?.tabs?.singleOrNull()?.let { PaneLayout.leaves(it.root).single() }
        }
        click("open-${target.id}")
        val session = awaitUntil { sessions.firstOrNull { it.snapshot().rowText(0).isNotBlank() } }
        click("terminal-${pane.id}")
        val metrics = FrameMetricsLog()
        scenario!!.onActivity { metrics.attach(it) }
        try {
            val latencies = measureLatency(session, metrics)
            val p50 = percentile(latencies, P50)
            val p95 = percentile(latencies, P95)
            val p99 = percentile(latencies, P99)
            Log.i(TAG, "latency n=${latencies.size} p50=$p50 p95=$p95 p99=$p99 max=${latencies.last()} ms")
            val (fps, longest) = measureFlood(target, session, metrics)
            var refresh = 0f
            scenario!!.onActivity { refresh = it.display.refreshRate }
            Log.i(
                TAG,
                "flood ${session.size.columns}x${session.size.rows} refresh=$refresh fps=$fps longest=$longest ms"
            )

            assertTrue("at least 200 keys measured: ${latencies.size}", latencies.size >= MIN_KEYS)
            assertTrue("p50 $p50 ms", p50 <= P50_MAX)
            assertTrue("p95 $p95 ms", p95 <= P95_MAX)
            assertTrue("p99 $p99 ms", p99 <= P99_MAX)
            assertTrue("fps $fps at $refresh Hz", fps >= refresh * FPS_SHARE)
            assertTrue("longest frame $longest ms", longest <= LONGEST_FRAME_MAX)
        } finally {
            metrics.detach()
        }
    }

    /** Key-to-frame latencies in ms, sorted: alternating x and Backspace in vim's insert mode. */
    private fun measureLatency(session: TerminalSession, metrics: FrameMetricsLog): List<Double> {
        type("vim -u NONE -N\n")
        Thread.sleep(SETTLE_MILLIS)
        type("i")
        Thread.sleep(SETTLE_MILLIS)
        val latencies = mutableListOf<Double>()
        repeat(args.getString("wsKeys")?.toInt() ?: LATENCY_KEYS) { i ->
            val code = if (i % 2 == 0) KeyEvent.KEYCODE_X else KeyEvent.KEYCODE_DEL
            val version = session.version
            drawn.clear()
            keyTimes.clear()
            systemKey(code)
            val t0 = awaitUntil { keyTimes.firstOrNull() }
            val frame = awaitUntil { drawn.firstOrNull { it.first === session && it.third > version } }
            metrics.awaitCompletion(frame.second)?.let {
                latencies +=
                    (it - t0 * NANOS_PER_MILLI) / NANOS_PER_MILLI.toDouble()
            }
            check(i < EARLY_KEYS || latencies.isNotEmpty()) { "no FrameMetrics frame matches the drawn frames" }
            Thread.sleep(KEY_INTERVAL_MILLIS)
        }
        key(KeyEvent.KEYCODE_ESCAPE)
        type(":q!\n")
        return latencies.sorted()
    }

    /** Frames per second drawn for [session] and longest frame in ms, during 15 s of continuous output. */
    private fun measureFlood(
        target: Machine,
        session: TerminalSession,
        metrics: FrameMetricsLog
    ): Pair<Double, Double> {
        // The output file is written by a terminal of its own, so that nothing is typed but the loop.
        runBlocking {
            rpc(target).call("terminal.run", buildJsonObject { put("line", "python3 -c \"$FLOOD\" > $FLOOD_FILE") })
        }
        Thread.sleep(SETTLE_MILLIS)
        type("clear; timeout 17 sh -c 'while :; do cat $FLOOD_FILE; done'; clear\n")
        Thread.sleep(WARMUP_MILLIS)
        drawn.clear()
        metrics.clear()
        val start = System.nanoTime()
        Thread.sleep(FLOOD_MILLIS)
        val seconds = (System.nanoTime() - start) / NANOS_PER_SECOND.toDouble()
        val frames = drawn.count { it.first === session }
        return frames / seconds to metrics.longestMillis()
    }

    /** `FrameMetrics` of the activity window: intended vsync and completion time of every frame. */
    private class FrameMetricsLog {
        private val thread = HandlerThread("frame-metrics").apply { start() }
        private val done = ConcurrentHashMap<Long, Long>()
        private val durations = CopyOnWriteArrayList<Long>()
        private var window: Window? = null
        private val listener = Window.OnFrameMetricsAvailableListener { _, m, _ ->
            val vsync = m.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
            val total = m.getMetric(FrameMetrics.TOTAL_DURATION)
            done[vsync] = vsync + total
            durations += total
        }

        fun attach(activity: Activity) {
            window = activity.window
            activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
        }

        fun detach() {
            window?.removeOnFrameMetricsAvailableListener(listener)
            thread.quitSafely()
        }

        /**
         * Completion time (intended vsync plus total duration) of the frame that was being drawn at
         * [drawNanos], null if no reported frame contains it.
         */
        fun awaitCompletion(drawNanos: Long): Long? {
            val deadline = SystemClock.uptimeMillis() + METRICS_WAIT_MILLIS
            var found: Long? = null
            while (found == null && SystemClock.uptimeMillis() < deadline) {
                found = done.entries.filter { it.key <= drawNanos && drawNanos <= it.value }.minOfOrNull { it.value }
                if (found == null) Thread.sleep(1)
            }
            return found
        }

        fun clear() {
            durations.clear()
        }

        fun longestMillis(): Double = (durations.maxOrNull() ?: 0L) / NANOS_PER_MILLI.toDouble()
    }

    private fun connect(): Machine {
        container.frameObserver = object : FrameObserver {
            override fun onFrame(session: TerminalSession, vsyncNanos: Long, version: Long) {
                sessions += session
                if (drawn.size > MAX_KEPT_FRAMES) drawn.clear()
                drawn += Triple(session, System.nanoTime(), version)
            }

            override fun onKeyDown(session: TerminalSession, eventTimeMillis: Long) {
                keyTimes += eventTimeMillis
            }
        }
        container.machines.machines.value.filter { it.name == NAME }.forEach {
            container.connections.forget(it.id)
            container.machines.remove(it.id)
        }
        val intent = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = display?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it).toBundle() }
        scenario = ActivityScenario.launch(intent, options)
        if (container.keys.publicKey(KEY_ALIAS) == null) container.keys.generate(KEY_ALIAS)
        val added = container.machines.add(
            NAME,
            checkNotNull(host),
            port,
            checkNotNull(user),
            MachineAuth.Key(KEY_ALIAS)
        )
        machine = added
        container.connections.connect(added.id)
        val presented = awaitUntil {
            (container.connections.status(added.id) as? MachineStatus.HostKeyUnconfirmed)?.presented
        }
        args.getString("wsHostKey")?.let { check(it == presented.fingerprint) { "unexpected host key" } }
        container.connections.confirmHostKey(added.id, presented)
        awaitUntil { container.workspaces.state(added.id).value as? WorkspaceState.Ready }
        spaces.record(checkNotNull(container.connections.sessions.value[added.id]))
        return added
    }

    /** Clicks the node whose test tag (exposed as resource id) is [tag]. */
    private fun click(tag: String) {
        val node = checkNotNull(device.wait(Until.findObject(By.res(tag)), TIMEOUT_MILLIS)) { "no node $tag" }
        node.click()
        Thread.sleep(SHORT_PAUSE_MILLIS)
    }

    private fun rpc(m: Machine): CoreRpc = checkNotNull(container.connections.sessions.value[m.id])

    /** Types ASCII [text] as key events of a virtual keyboard into the focused window. */
    private fun type(text: String) {
        val map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        val events = checkNotNull(map.getEvents(text.toCharArray())) { "not typeable: $text" }
        events.forEach {
            instrumentation.uiAutomation.injectInputEvent(
                KeyEvent.changeTimeRepeat(it, SystemClock.uptimeMillis(), 0),
                true
            )
        }
    }

    /**
     * Presses and releases one key through the system's `input` command, which stamps the event when it
     * injects it, as the kernel does for a physical keyboard; injecting from the test process instead adds
     * about 17 ms before the event reaches the window.
     */
    private fun systemKey(code: Int) {
        val displayArg = display?.let { "-d $it " }.orEmpty()
        instrumentation.uiAutomation.executeShellCommand("input ${displayArg}keyevent $code").close()
    }

    /** Presses and releases one key; returns the down event's time (`uptimeMillis`). */
    private fun key(code: Int): Long {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0)
        instrumentation.uiAutomation.injectInputEvent(down, true)
        instrumentation.uiAutomation.injectInputEvent(KeyEvent.changeAction(down, KeyEvent.ACTION_UP), true)
        return now
    }

    /** Polls [value] until it is not null (or fails after the timeout). */
    private fun <T : Any> awaitUntil(value: () -> T?): T {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            runCatching(value).getOrNull()?.let { return it }
            Thread.sleep(POLL_MILLIS)
        }
        error("condition not met in $TIMEOUT_MILLIS ms")
    }

    private fun percentile(sorted: List<Double>, p: Double): Double =
        sorted[((sorted.size - 1) * p).toInt().coerceIn(0, sorted.lastIndex)]

    private companion object {
        const val TAG = "PaneE2E"
        const val NAME = "perf-e2e"
        const val KEY_ALIAS = "e2e-workspace"
        const val TIMEOUT_MILLIS = 40_000L
        const val POLL_MILLIS = 1L
        const val SHORT_PAUSE_MILLIS = 500L
        const val SETTLE_MILLIS = 3_000L
        const val WARMUP_MILLIS = 1_000L
        const val FLOOD_MILLIS = 15_000L
        const val KEY_INTERVAL_MILLIS = 150L
        const val METRICS_WAIT_MILLIS = 2_000L
        const val LATENCY_KEYS = 220
        const val MIN_KEYS = 200
        const val EARLY_KEYS = 10
        const val MAX_KEPT_FRAMES = 5_000
        const val NANOS_PER_MILLI = 1_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val P50 = 0.50
        const val P95 = 0.95
        const val P99 = 0.99
        const val P50_MAX = 40.0
        const val P95_MAX = 60.0
        const val P99_MAX = 100.0
        const val FPS_SHARE = 0.83
        const val LONGEST_FRAME_MAX = 100.0
        const val FLOOD_FILE = "/tmp/us005-flood.txt"

        /** 20 000 lines of truecolor words with styles, CJK, emoji, box drawing and blocks (SPIKE-003). */
        const val FLOOD = "import random;r=random.Random(5);" +
            "w=['\\u6f22\\u5b57','\\ud55c\\uae00','\\U0001f642','\\u250c\\u2500\\u2510','\\u2502x\\u2502'," +
            "'\\u2593\\u2592\\u2591','\\u03b1\\u03b2\\u03b3','h\\u00e9llo','w\\u00f6rld','ok'];" +
            "c=lambda:r.randrange(256);" +
            "print('\\\\n'.join(' '.join('\\\\033[%d;38;2;%d;%d;%dm%s\\\\033[0m'%" +
            "(r.choice([0,1,3,4]),c(),c(),c(),r.choice(w)) for _ in range(10)) for _ in range(20000)))"
    }
}
