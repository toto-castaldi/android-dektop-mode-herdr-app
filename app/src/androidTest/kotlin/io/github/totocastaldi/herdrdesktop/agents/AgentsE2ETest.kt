package io.github.totocastaldi.herdrdesktop.agents

import android.app.ActivityOptions
import android.content.Intent
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
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
import io.github.totocastaldi.herdrdesktop.coreclient.agents.AgentState
import io.github.totocastaldi.herdrdesktop.coreclient.connection.CoreRpc
import io.github.totocastaldi.herdrdesktop.coreclient.connection.MachineStatus
import io.github.totocastaldi.herdrdesktop.coreclient.connection.status
import io.github.totocastaldi.herdrdesktop.coreclient.machine.Machine
import io.github.totocastaldi.herdrdesktop.coreclient.machine.MachineAuth
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.Workspace
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.WorkspaceClient
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.WorkspaceState
import io.github.totocastaldi.herdrdesktop.workspace.E2eSpaces
import java.io.File
import kotlinx.coroutines.runBlocking
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
 * The agents sidebar against a real herdr-core (REQ-AGT-001): `-e agHost <host> -e agUser <user>
 * [-e agPort <port>]`; skipped without them. The test key `e2e-agents` (public part written to
 * `agents-e2e.pub` in the app's external files directory) must be authorized on the host, ideally with a forced
 * command that reaches a separate core session (`HC_SESSION`). Programs report their status with OSC 7501
 * (`printf` in the shell of a tab, and a worker started by it); the test ends the terminals it started.
 * `-e agHostKey SHA256:…` checks the presented host key before confirming it.
 * `-e agDisplay <id>` opens the app on that display (Desktop Mode); `-e agPauseMillis <ms>` holds each step on
 * screen; `-e agDeleteKey true` deletes the test key at the end; `-e agKey write` only creates a new test key
 * and writes its public part, without connecting, to authorize it on the host first.
 */
@RunWith(AndroidJUnit4::class)
class AgentsE2ETest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val args = InstrumentationRegistry.getArguments()
    private val host: String? = args.getString("agHost")
    private val user: String? = args.getString("agUser")
    private val port = args.getString("agPort")?.toInt() ?: Machine.DEFAULT_PORT
    private val pause = args.getString("agPauseMillis")?.toLong() ?: 0L
    private val display = args.getString("agDisplay")?.toInt()

    /** Expected `SHA256:` fingerprint of the host key; when given, any other key fails the test. */
    private val hostKey: String? = args.getString("agHostKey")
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HerdrApp
    private val container get() = app.container
    private var scenario: ActivityScenario<MainActivity>? = null
    private var machine: Machine? = null
    private val spaces = E2eSpaces()
    private var worker: String? = null

    @After
    fun cleanUp() {
        machine?.let { m ->
            container.connections.sessions.value[m.id]?.let { rpc ->
                runBlocking {
                    val client = WorkspaceClient(rpc)
                    worker?.let { runCatching { client.terminals.end(it) } }
                    spaces.cleanUp(rpc)
                }
            }
            container.connections.forget(m.id)
            container.machines.remove(m.id)
        }
        scenario?.close()
        if (args.getString("agDeleteKey") == "true") {
            container.keys.delete(KEY_ALIAS)
            File(app.getExternalFilesDir(null), "agents-e2e.pub").delete()
        }
    }

    /** `-e agKey write`: a new test key, replacing any left from earlier runs, without connecting anywhere. */
    @Test
    fun writesANewTestKey() {
        assumeTrue("agKey not given", args.getString("agKey") == "write")
        container.keys.delete(KEY_ALIAS)
        val key = container.keys.generate(KEY_ALIAS)
        File(app.getExternalFilesDir(null), "agents-e2e.pub").writeText(key.authorizedKeysLine() + "\n")
    }

    @Test
    fun agentStatesFollowTheCoreAndLookingTurnsDoneIntoIdle() {
        assumeTrue("agHost and agUser not given", host != null && user != null)
        val target = connect()

        // A space with two tabs: the second, just created, is the one on screen.
        rule.onNodeWithText(text(R.string.workspace_new_space)).performClick()
        awaitWorkspace(target) { it.spaces.size == 1 }
        rule.onNodeWithText(text(R.string.workspace_new_tab)).performClick()
        awaitWorkspace(target) { it.spaces.single().tabs.size == 2 }
        // Let the screen show the new tab, so that the first one is no longer on screen.
        rule.waitForIdle()
        val agent = workspaceOf(target).spaces.single().tabs[0].terminals.single()

        // A program in the first tab becomes an agent by reporting its status; it reports done after a line of
        // input. A separate `sh`, because an interactive shell may clear the status of its own terminal.
        val working = osc("state=working:app=e2e-agent")
        send(target, agent, "sh -c \"$working; read _; ${osc("state=done:app=e2e-agent")}; read _\"")
        awaitRow(target, agent, AgentState.WORKING)
        step()

        // A worker started by the agent joins the list; an error is shown as Blocked, with the agent's message.
        val child = startWorker(target, agent).also { worker = it }
        awaitRow(target, child, AgentState.BLOCKED)
        rule.onNodeWithTag(tag(target, child)).assertTextContains("e2e-worker", substring = true)
        rule.onNodeWithTag(tag(target, child)).assertTextContains("rotto", substring = true)
        step()

        // The agent finishes while its tab is not on screen: Done, and nothing is marked seen on the core.
        send(target, agent, "x")
        awaitRow(target, agent, AgentState.DONE)
        assertEquals("0", labelsOf(target, agent)["herdr.seen"])
        step()

        // Selecting it in the sidebar brings its tab to the front: the app writes herdr.seen and it becomes Idle.
        rule.onNodeWithTag(tag(target, agent)).performClick()
        awaitRow(target, agent, AgentState.IDLE)
        assertEquals("1", labelsOf(target, agent)["herdr.seen"])
        step()
    }

    private fun connect(): Machine {
        // A machine left by an interrupted run of this test.
        container.machines.machines.value.filter { it.name == NAME }.forEach {
            container.connections.forget(it.id)
            container.machines.remove(it.id)
        }
        launch()
        val key = container.keys.publicKey(KEY_ALIAS) ?: container.keys.generate(KEY_ALIAS)
        File(app.getExternalFilesDir(null), "agents-e2e.pub").writeText(key.authorizedKeysLine() + "\n")
        val auth = MachineAuth.Key(KEY_ALIAS)
        val added = container.machines.add(NAME, checkNotNull(host), port, checkNotNull(user), auth)
        machine = added
        container.connections.connect(added.id)
        rule.waitUntil(TIMEOUT_MILLIS) { container.connections.status(added.id) is MachineStatus.HostKeyUnconfirmed }
        val presented = (container.connections.status(added.id) as MachineStatus.HostKeyUnconfirmed).presented
        hostKey?.let { assertEquals("host key of $host", it, presented.fingerprint) }
        container.connections.confirmHostKey(added.id, presented)
        rule.waitUntil(TIMEOUT_MILLIS) { container.workspaces.state(added.id).value is WorkspaceState.Ready }
        spaces.record(checkNotNull(container.connections.sessions.value[added.id]))
        // By the machine's own tag: other machines of the device may be connected and show "Apri" too.
        rule.onNodeWithTag("open-${checkNotNull(machine).id}").performClick()
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

    /** Shell command printing an OSC 7501 report with these fields. */
    private fun osc(fields: String) = "printf '\\033]7501;$fields\\007'"

    /** Starts, as a child of [parent], a program that reports an error and stays. */
    private fun startWorker(m: Machine, parent: String): String {
        val params = buildJsonObject {
            put("line", "${osc("state=error:app=e2e-worker:msg=$BROKEN_BASE64")}; sleep 600")
            put("parent", parent)
            // Notes about the child would be typed into the parent and answer its `read`.
            put("no_notify", true)
        }
        val result = runBlocking { rpc(m).call("terminal.run", params) } as JsonObject
        return result.getValue("address").jsonPrimitive.content
    }

    private fun labelsOf(m: Machine, terminal: String): Map<String, String> {
        val result = runBlocking { rpc(m).call("terminal.get", buildJsonObject { put("terminal", terminal) }) }
        return result.jsonObject.getValue("labels").jsonObject.mapValues { it.value.jsonPrimitive.content }
    }

    /** Waits for the agent in the app's state and checks the row shown in the sidebar. */
    private fun awaitRow(m: Machine, address: String, state: AgentState) {
        try {
            rule.waitUntil(TIMEOUT_MILLIS) {
                container.agents.agents.value[m.id].orEmpty().any { it.address == address && it.state == state }
            }
        } catch (e: ComposeTimeoutException) {
            val core = runBlocking { rpc(m).call("terminal.get", buildJsonObject { put("terminal", address) }) }
                .jsonObject.filterKeys { it in setOf("status", "turn", "labels", "reported", "parent") }
            throw AssertionError("$address not $state: app ${container.agents.agents.value[m.id]}, core $core", e)
        }
        rule.waitForIdle()
        rule.onNodeWithTag(tag(m, address)).assertTextContains(text(label(state)), substring = true)
    }

    private fun label(state: AgentState) = when (state) {
        AgentState.WORKING -> R.string.agent_working
        AgentState.BLOCKED -> R.string.agent_blocked
        AgentState.DONE -> R.string.agent_done
        AgentState.IDLE -> R.string.agent_idle
    }

    private fun tag(m: Machine, address: String) = "agent-${m.id}-$address"

    private fun awaitWorkspace(m: Machine, done: (Workspace) -> Boolean) {
        rule.waitUntil(TIMEOUT_MILLIS) {
            val shown = (container.workspaces.state(m.id).value as? WorkspaceState.Ready)?.workspace
            shown != null && done(shown)
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
        const val NAME = "agents-e2e"
        const val KEY_ALIAS = "e2e-agents"
        const val TIMEOUT_MILLIS = 40_000L

        /** "rotto" in base64, as OSC 7501 carries messages. */
        const val BROKEN_BASE64 = "cm90dG8="
    }
}
