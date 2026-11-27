package io.github.totocastaldi.herdrdesktop.agents

import android.app.ActivityOptions
import android.content.Intent
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.totocastaldi.herdrdesktop.HerdrApp
import io.github.totocastaldi.herdrdesktop.MainActivity
import io.github.totocastaldi.herdrdesktop.R
import io.github.totocastaldi.herdrdesktop.coreclient.connection.CoreRpc
import io.github.totocastaldi.herdrdesktop.coreclient.connection.MachineStatus
import io.github.totocastaldi.herdrdesktop.coreclient.connection.status
import io.github.totocastaldi.herdrdesktop.coreclient.machine.Machine
import io.github.totocastaldi.herdrdesktop.coreclient.machine.MachineAuth
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.CoreTerminals
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.PaneLayout
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.Workspace
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.WorkspaceClient
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.WorkspaceState
import io.github.totocastaldi.herdrdesktop.workspace.E2eSpaces
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Terminals started by agents in the app's spaces and tabs, against a real herdr-core (REQ-WS-003):
 * `-e adHost <host> -e adUser <user> [-e adPort <port>]`; skipped without them. Same key and options as
 * [AgentsE2ETest] (key `e2e-agents`, public part in `agents-e2e.pub`; `-e adHostKey SHA256:…`, `-e adDisplay <id>`,
 * `-e adPauseMillis <ms>`). The shell of a tab of the app acts as the agent: it reads its address and labels,
 * starts workers with `hc run --label herdr-desktop-android.space=…` and talks to one with `hc send` and
 * `hc wait`. The test ends the terminals it started and closes its space.
 */
@RunWith(AndroidJUnit4::class)
class AdoptionE2ETest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val args = InstrumentationRegistry.getArguments()
    private val host: String? = args.getString("adHost")
    private val user: String? = args.getString("adUser")
    private val port = args.getString("adPort")?.toInt() ?: Machine.DEFAULT_PORT
    private val pause = args.getString("adPauseMillis")?.toLong() ?: 0L
    private val display = args.getString("adDisplay")?.toInt()
    private val hostKey: String? = args.getString("adHostKey")
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HerdrApp
    private val container get() = app.container
    private var scenario: ActivityScenario<MainActivity>? = null
    private var machine: Machine? = null
    private val spaces = E2eSpaces()
    private val workers = mutableListOf<String>()

    @After
    fun cleanUp() {
        machine?.let { m ->
            container.connections.sessions.value[m.id]?.let { rpc ->
                runBlocking {
                    val client = WorkspaceClient(rpc)
                    workers.forEach { runCatching { client.terminals.end(it) } }
                    spaces.cleanUp(rpc)
                }
            }
            container.connections.forget(m.id)
            container.machines.remove(m.id)
        }
        scenario?.close()
    }

    @Test
    fun workersStartedWithTheAppLabelsJoinTheSpaceAndTalkThroughTheCore() {
        assumeTrue("adHost and adUser not given", host != null && user != null)
        val target = connect()
        rule.onNodeWithText(text(R.string.workspace_new_space)).performClick()
        awaitWorkspace(target) { it.spaces.size == 1 }
        val space = workspaceOf(target).spaces.single()
        val tab = space.tabs.single()
        val shell = tab.terminals.single()
        spaces.own(space.id)
        step()

        // The agent reads its own address and the app's labels of its space and tab.
        val label = "sed -n 's/.*\"herdr-desktop-android.%s\":\"\\([^\"]*\\)\".*/\\1/p'"
        send(
            target,
            shell,
            "S=$(hc get \"\$HC_TERMINAL_ID\" --json | ${label.format("space")}); " +
                "T=$(hc get \"\$HC_TERMINAL_ID\" --json | ${label.format("tab")}); " +
                "echo \"ID=\$HC_TERMINAL_ID SPACE=\$S TAB=\$T\""
        )
        awaitScreen(target, shell, "ID=$shell SPACE=${space.id} TAB=${tab.id}")
        step()

        // A worker with only the space label: a new tab of the space, labelled with it.
        send(
            target,
            shell,
            "hc run --bg --no-notify --name $WORKER --label herdr-desktop-android.space=\$S " +
                "sh -c 'read x; sleep 2; echo \"ricevuto \$x\"; sleep 600'"
        )
        val worker = awaitTerminal(target, WORKER).also { workers += it }
        awaitWorkspace(target) { ws -> ws.space(space.id)?.tabs?.size == 2 }
        val adopted = workspaceOf(target).space(space.id)!!.tabs[1]
        assertEquals(listOf(worker), adopted.terminals)
        rule.waitUntil(TIMEOUT_MILLIS) { labelsOf(target, worker)[CoreTerminals.TAB_LABEL] == adopted.id }
        rule.waitForIdle()
        rule.onNodeWithTag("pane-${PaneLayout.leaves(adopted.root).single().id}").assertExists()
        step()

        // A worker with the space and the tab of the agent: a new pane next to the agent's.
        send(
            target,
            shell,
            "hc run --bg --no-notify --name $HELPER --label herdr-desktop-android.space=\$S " +
                "--label herdr-desktop-android.tab=\$T sleep 600"
        )
        val helper = awaitTerminal(target, HELPER).also { workers += it }
        awaitWorkspace(target) { ws -> ws.space(space.id)?.tab(tab.id)?.terminals == listOf(shell, helper) }
        step()

        // The agent coordinates the worker through the core.
        send(target, shell, "hc send $WORKER ciao && hc wait $WORKER --output 'ricevuto ciao' && echo ATTESA-OK")
        awaitScreen(target, worker, "ricevuto ciao")
        awaitScreen(target, shell, "ATTESA-OK")
        step()

        // Taking the labels away takes the panes out of the app; the terminals go on running.
        send(
            target,
            shell,
            "hc label $WORKER herdr-desktop-android.space= && hc label $HELPER herdr-desktop-android.space="
        )
        awaitWorkspace(target) { ws -> ws.space(space.id)?.tabs?.map { it.terminals } == listOf(listOf(shell)) }
        assertEquals("live", stateOf(target, worker))
        assertEquals("live", stateOf(target, helper))
        step()
    }

    private fun connect(): Machine {
        container.machines.machines.value.filter { it.name == NAME }.forEach {
            container.connections.forget(it.id)
            container.machines.remove(it.id)
        }
        launch()
        val key = container.keys.publicKey(KEY_ALIAS) ?: container.keys.generate(KEY_ALIAS)
        File(app.getExternalFilesDir(null), "agents-e2e.pub").writeText(key.authorizedKeysLine() + "\n")
        val added = container.machines.add(
            NAME,
            checkNotNull(host),
            port,
            checkNotNull(user),
            MachineAuth.Key(KEY_ALIAS)
        )
        machine = added
        container.connections.connect(added.id)
        rule.waitUntil(TIMEOUT_MILLIS) { container.connections.status(added.id) is MachineStatus.HostKeyUnconfirmed }
        val presented = (container.connections.status(added.id) as MachineStatus.HostKeyUnconfirmed).presented
        hostKey?.let { assertEquals("host key of $host", it, presented.fingerprint) }
        container.connections.confirmHostKey(added.id, presented)
        rule.waitUntil(TIMEOUT_MILLIS) { container.workspaces.state(added.id).value is WorkspaceState.Ready }
        spaces.record(checkNotNull(container.connections.sessions.value[added.id]))
        rule.onNodeWithTag("open-${added.id}").performClick()
        rule.onNodeWithText(text(R.string.workspace_spaces)).assertExists()
        return added
    }

    private fun launch() {
        val intent = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = display?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it).toBundle() }
        scenario = ActivityScenario.launch(intent, options)
    }

    /** Types [text] into [terminal] and presses enter. */
    private fun send(m: Machine, terminal: String, text: String) {
        val params = buildJsonObject {
            put("terminal", terminal)
            put("text", text)
        }
        runBlocking { rpc(m).call("terminal.send", params) }
    }

    private fun screenOf(m: Machine, terminal: String): String {
        val params = buildJsonObject {
            put("terminal", terminal)
            put("lines", SCREEN_HISTORY)
        }
        return runBlocking { rpc(m).call("terminal.read", params) }.jsonObject["text"]?.jsonPrimitive?.content.orEmpty()
    }

    private fun awaitScreen(m: Machine, terminal: String, text: String) {
        try {
            rule.waitUntil(TIMEOUT_MILLIS) { screenOf(m, terminal).contains(text) }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("\"$text\" not on $terminal:\n${screenOf(m, terminal).takeLast(SCREEN_TAIL)}", e)
        }
    }

    /** The address of the live terminal named [name]. */
    private fun awaitTerminal(m: Machine, name: String): String {
        var address: String? = null
        rule.waitUntil(TIMEOUT_MILLIS) {
            val list = runBlocking { rpc(m).call("terminal.list", null) }.jsonObject["terminals"] as JsonArray
            address = list.map { it.jsonObject }.firstOrNull { it["name"]?.jsonPrimitive?.content == name }
                ?.get("address")?.jsonPrimitive?.content
            address != null
        }
        return checkNotNull(address)
    }

    private fun stateOf(m: Machine, terminal: String): String? {
        val result = runBlocking { rpc(m).call("terminal.get", buildJsonObject { put("terminal", terminal) }) }
        return result.jsonObject["state"]?.jsonPrimitive?.content
    }

    private fun labelsOf(m: Machine, terminal: String): Map<String, String> {
        val result = runBlocking { rpc(m).call("terminal.get", buildJsonObject { put("terminal", terminal) }) }
        return (result.jsonObject["labels"] as? JsonObject).orEmpty().mapValues { it.value.jsonPrimitive.content }
    }

    private fun awaitWorkspace(m: Machine, done: (Workspace) -> Boolean) {
        try {
            rule.waitUntil(TIMEOUT_MILLIS) {
                val shown = (container.workspaces.state(m.id).value as? WorkspaceState.Ready)?.workspace
                shown != null && done(shown)
            }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("workspace: ${container.workspaces.state(m.id).value}", e)
        }
    }

    private fun workspaceOf(m: Machine) = (container.workspaces.state(m.id).value as WorkspaceState.Ready).workspace

    private fun rpc(m: Machine): CoreRpc = checkNotNull(container.connections.sessions.value[m.id])

    private fun step() {
        rule.waitForIdle()
        if (pause > 0) Thread.sleep(pause)
    }

    private fun text(id: Int) = app.getString(id)

    private companion object {
        const val NAME = "adoption-e2e"
        const val KEY_ALIAS = "e2e-agents"
        const val WORKER = "e2e-adopt-worker"
        const val HELPER = "e2e-adopt-helper"
        const val TIMEOUT_MILLIS = 40_000L
        const val SCREEN_HISTORY = 200
        const val SCREEN_TAIL = 2000
    }
}
