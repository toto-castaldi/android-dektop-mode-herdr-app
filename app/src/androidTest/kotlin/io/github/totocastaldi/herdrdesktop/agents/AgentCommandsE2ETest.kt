package io.github.totocastaldi.herdrdesktop.agents

import android.app.ActivityOptions
import android.content.Intent
import android.util.Base64
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
import io.github.totocastaldi.herdrdesktop.coreclient.agents.AgentClaims
import io.github.totocastaldi.herdrdesktop.coreclient.agents.AgentCommandCodec
import io.github.totocastaldi.herdrdesktop.coreclient.agents.AgentCommandLog
import io.github.totocastaldi.herdrdesktop.coreclient.agents.AgentCommands
import io.github.totocastaldi.herdrdesktop.coreclient.connection.CoreRpc
import io.github.totocastaldi.herdrdesktop.coreclient.connection.MachineStatus
import io.github.totocastaldi.herdrdesktop.coreclient.connection.status
import io.github.totocastaldi.herdrdesktop.coreclient.machine.Machine
import io.github.totocastaldi.herdrdesktop.coreclient.machine.MachineAuth
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.PaneLayout
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.Workspace
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.WorkspaceClient
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.WorkspaceCodec
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.WorkspaceState
import io.github.totocastaldi.herdrdesktop.workspace.E2eSpaces
import java.io.File
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The agents' commands to the app against a real herdr-core (REQ-AGT-003):
 * `-e acHost <host> -e acUser <user> [-e acPort <port>] [-e acHostKey SHA256:…] [-e acDisplay <id>]
 * [-e acPauseMillis <ms>]`; skipped without them. Same key as [AgentsE2ETest] (`e2e-agents`, public part in
 * `agents-e2e.pub`). The shell of a tab of the app is the agent: it loads the README's `hda` recipe (asset
 * `hda.sh`, the same text) and runs every supported action, an unsupported one and one made while the app is
 * not connected. A second [AgentCommands] in this process plays a second device claiming the same requests on
 * the core. The test ends the terminals it started and closes its spaces.
 */
@RunWith(AndroidJUnit4::class)
class AgentCommandsE2ETest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val args = InstrumentationRegistry.getArguments()
    private val host: String? = args.getString("acHost")
    private val user: String? = args.getString("acUser")
    private val port = args.getString("acPort")?.toInt() ?: Machine.DEFAULT_PORT
    private val pause = args.getString("acPauseMillis")?.toLong() ?: 0L
    private val display = args.getString("acDisplay")?.toInt()
    private val hostKey: String? = args.getString("acHostKey")
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HerdrApp
    private val container get() = app.container
    private var scenario: ActivityScenario<MainActivity>? = null
    private var machine: Machine? = null
    private val spaces = E2eSpaces()
    private val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val secondLog: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @After
    fun cleanUp() {
        secondScope.cancel()
        machine?.let { m ->
            container.connections.sessions.value[m.id]?.let { rpc ->
                runBlocking {
                    runCatching { WorkspaceClient(rpc).document.load() }.getOrNull()?.workspace?.spaces
                        ?.filter { it.name.startsWith(PREFIX) }?.forEach { spaces.own(it.id) }
                    spaces.cleanUp(rpc)
                }
            }
            container.connections.forget(m.id)
            container.machines.remove(m.id)
        }
        scenario?.close()
    }

    @Test
    fun anAgentRunsTheSupportedActionsWithTheReadmeRecipe() {
        assumeTrue("acHost and acUser not given", host != null && user != null)
        val target = connect()
        // A second device of the app on the same core: it claims the same requests (REQ-AGT-003).
        AgentCommands(
            container.connections.sessions,
            container.agents.snapshots,
            container.workspaces,
            container.agentWindows,
            secondScope,
            AgentCommandLog { _, action, outcome -> secondLog += "$action $outcome" }
        )
        rule.onNodeWithText(text(R.string.workspace_new_space)).performClick()
        awaitWorkspace(target) { it.spaces.size == 1 }
        val home = workspaceOf(target).spaces.single()
        val shell = home.tabs.single().terminals.single()
        spaces.own(home.id)
        val desktopBefore = docVersion(target, WorkspaceCodec.DESKTOP_DOC_NAME)
        writeRecipe(target)
        // The recipe is for bash, whatever the login shell of the machine.
        send(target, shell, "bash")
        send(target, shell, "source $RECIPE_PATH && ${mark("RICETTA-OK")}")
        awaitScreen(target, shell, "RICETTA-OK")
        step()

        // space.new: a named space with one tab, answered with its ids.
        send(target, shell, "hda space.new '{\"name\":\"${PREFIX}web\"}' && ${mark("AC-SPACE-OK")}")
        awaitScreen(target, shell, "AC-SPACE-OK")
        awaitWorkspace(target) { ws -> ws.spaces.count { it.name == "${PREFIX}web" } == 1 }
        val web = workspaceOf(target).spaces.single { it.name == "${PREFIX}web" }
        spaces.own(web.id)
        assertTrue(answerOf(target, shell).contains("\"space\":\"${web.id}\""))
        step()

        // tab.new without space: in the agent's space.
        send(target, shell, "hda tab.new '{\"name\":\"${PREFIX}tab\"}' && ${mark("AC-TAB-OK")}")
        awaitScreen(target, shell, "AC-TAB-OK")
        awaitWorkspace(target) { ws -> ws.space(home.id)?.tabs?.map { it.name } == listOf("", "${PREFIX}tab") }
        step()

        // pane.split without pane: the agent's pane.
        send(target, shell, "hda pane.split '{\"direction\":\"down\"}' && ${mark("AC-SPLIT-OK")}")
        awaitScreen(target, shell, "AC-SPLIT-OK")
        awaitWorkspace(target) { ws -> ws.space(home.id)?.tabs?.first()?.terminals?.size == 2 }
        step()

        // space.select and tab.select bring them to the front in the window.
        send(target, shell, "hda space.select '{\"id\":\"${web.id}\"}' && ${mark("AC-SSEL-OK")}")
        awaitScreen(target, shell, "AC-SSEL-OK")
        awaitPane(PaneLayout.leaves(web.tabs.single().root).single().id)
        step()
        send(target, shell, "hda tab.select '{\"index\":1}' && ${mark("AC-TSEL-OK")}")
        awaitScreen(target, shell, "AC-TSEL-OK")
        step()

        // agent.focus brings the agent's own pane back to the front.
        send(target, shell, "hda agent.focus \"{\\\"terminal\\\":\\\"\$HC_TERMINAL_ID\\\"}\" && ${mark("AC-FOCUS-OK")}")
        awaitScreen(target, shell, "AC-FOCUS-OK")
        awaitPane(workspaceOf(target).paneOf(shell)!!.paneId)
        step()

        // Anything else is unsupported, and nothing is closed.
        send(target, shell, "hda tab.close '{}'; ${mark("AC-CLOSE-\$?")}")
        awaitScreen(target, shell, "AC-CLOSE-1")
        assertTrue(answerOf(target, shell).contains("\"error\":\"unsupported\""))
        assertEquals(3, workspaceOf(target).space(home.id)!!.tabs.sumOf { it.terminals.size })
        step()

        offlineRequestRunsAtTheConnection(target, shell)
        everyRequestRanOnce(target, shell, desktopBefore)
    }

    /** A request made while the app is not connected runs at the connection, while the agent waits. */
    private fun offlineRequestRunsAtTheConnection(target: Machine, shell: String) {
        send(target, shell, "sleep 3; hda space.new '{\"name\":\"${PREFIX}offline\"}' && ${mark("AC-OFFLINE-OK")}")
        container.connections.disconnect(target.id)
        rule.waitUntil(TIMEOUT_MILLIS) { container.connections.sessions.value[target.id] == null }
        Thread.sleep(OFFLINE_MILLIS)
        container.connections.connect(target.id)
        rule.waitUntil(TIMEOUT_MILLIS) { container.connections.status(target.id) is MachineStatus.Connected }
        awaitScreen(target, shell, "AC-OFFLINE-OK")
        awaitWorkspace(target) { ws -> ws.spaces.count { it.name == "${PREFIX}offline" } == 1 }
        step()
    }

    /** Every request ran once over the two "devices"; the claims are on the core; herdr-app untouched. */
    private fun everyRequestRanOnce(target: Machine, shell: String, desktopBefore: String) {
        val ran = workspaceOf(target).spaces.filter { it.name.startsWith(PREFIX) }.map { it.name }
        assertEquals(listOf("${PREFIX}web", "${PREFIX}offline"), ran)
        assertTrue(claims(target).contains("\"$shell\""))
        assertTrue("second device: $secondLog", secondLog.size >= REQUESTS)
        assertEquals(desktopBefore, docVersion(target, WorkspaceCodec.DESKTOP_DOC_NAME))
        allLive(target, workspaceOf(target))
        send(target, shell, "rm -f $RECIPE_PATH; exit")
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
        hostKey?.let { assertEquals("host key", it, presented.fingerprint) }
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

    /** The README's recipe, written on the machine with `file.write`. */
    private fun writeRecipe(m: Machine) {
        val recipe = InstrumentationRegistry.getInstrumentation().context.assets.open("hda.sh").readBytes()
        val params = buildJsonObject {
            put("path", RECIPE_PATH)
            put("data", Base64.encodeToString(recipe, Base64.NO_WRAP))
        }
        runBlocking { rpc(m).call("file.write", params) }
    }

    /**
     * Prints [marker] in upper case from a lower-case command, so that the screen shows it only once the command
     * ran, not while it is typed.
     */
    private fun mark(marker: String) = "echo ${marker.lowercase()} | tr a-z A-Z"

    private fun send(m: Machine, terminal: String, text: String) {
        val params = buildJsonObject {
            put("terminal", terminal)
            put("text", text)
        }
        runBlocking { rpc(m).call("terminal.send", params) }
    }

    private fun screenOf(m: Machine, terminal: String): String {
        val rpc = container.connections.sessions.value[m.id] ?: return ""
        val params = buildJsonObject {
            put("terminal", terminal)
            put("lines", SCREEN_HISTORY)
        }
        return runCatching { runBlocking { rpc.call("terminal.read", params) } }.getOrNull()
            ?.jsonObject?.get("text")?.jsonPrimitive?.content.orEmpty()
    }

    private fun awaitScreen(m: Machine, terminal: String, text: String) {
        try {
            rule.waitUntil(TIMEOUT_MILLIS) { screenOf(m, terminal).contains(text) }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("\"$text\" not on $terminal:\n${screenOf(m, terminal).takeLast(SCREEN_TAIL)}", e)
        }
    }

    private fun awaitPane(paneId: String) {
        rule.waitUntil(TIMEOUT_MILLIS) {
            runCatching { rule.onNodeWithTag("pane-$paneId").assertExists() }.isSuccess
        }
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

    /** The last answer the agent got (its label `herdr-desktop-android.re`); the screen wraps long lines. */
    private fun answerOf(m: Machine, terminal: String): String {
        val result = runBlocking { rpc(m).call("terminal.get", buildJsonObject { put("terminal", terminal) }) }
        return (result.jsonObject["labels"] as? JsonObject)?.get(AgentCommandCodec.RESPONSE_LABEL)
            ?.jsonPrimitive?.content.orEmpty()
    }

    private fun docVersion(m: Machine, name: String): String = runBlocking {
        rpc(m).call("doc.get", buildJsonObject { put("name", name) }).jsonObject["version"].toString()
    }

    private fun claims(m: Machine): String = runBlocking {
        rpc(m).call("doc.get", buildJsonObject { put("name", AgentClaims.DOC_NAME) }).jsonObject["value"].toString()
    }

    /** No terminal of the shown document was ended by the commands. */
    private fun allLive(m: Machine, ws: Workspace) {
        val list = runBlocking { rpc(m).call("terminal.list", null) }.jsonObject["terminals"] as JsonArray
        val live = list.map { it.jsonObject }.filter { it["state"]?.jsonPrimitive?.content == "live" }
            .map { (it as JsonObject).getValue("address").jsonPrimitive.content }.toSet()
        val shown = ws.spaces.filter { it.name.startsWith(PREFIX) || it.tabs.any { t -> t.name.startsWith(PREFIX) } }
            .flatMap { s -> s.tabs.flatMap { it.terminals } }
        assertTrue("ended: ${shown - live}", live.containsAll(shown))
    }

    private fun workspaceOf(m: Machine) = (container.workspaces.state(m.id).value as WorkspaceState.Ready).workspace

    private fun rpc(m: Machine): CoreRpc = checkNotNull(container.connections.sessions.value[m.id])

    private fun step() {
        rule.waitForIdle()
        if (pause > 0) Thread.sleep(pause)
    }

    private fun text(id: Int) = app.getString(id)

    private companion object {
        const val NAME = "commands-e2e"
        const val KEY_ALIAS = "e2e-agents"
        const val PREFIX = "e2e-ac-"
        const val RECIPE_PATH = "/tmp/herdr-e2e-hda.sh"
        const val TIMEOUT_MILLIS = 40_000L
        const val OFFLINE_MILLIS = 5_000L

        /** Requests of the agent, each seen (claimed or lost) by the second device too. */
        const val REQUESTS = 8
        const val SCREEN_HISTORY = 200
        const val SCREEN_TAIL = 2000
    }
}
