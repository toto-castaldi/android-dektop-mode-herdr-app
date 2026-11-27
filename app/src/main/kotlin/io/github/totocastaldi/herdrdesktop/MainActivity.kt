package io.github.totocastaldi.herdrdesktop

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import io.github.totocastaldi.herdrdesktop.agents.AgentSidebar
import io.github.totocastaldi.herdrdesktop.agents.PaneFocus
import io.github.totocastaldi.herdrdesktop.agents.PeekRequest
import io.github.totocastaldi.herdrdesktop.machines.LocalNetworkPermissionRequest
import io.github.totocastaldi.herdrdesktop.machines.MachinesScreen
import io.github.totocastaldi.herdrdesktop.workspace.AgentRequests
import io.github.totocastaldi.herdrdesktop.workspace.WindowOptions
import io.github.totocastaldi.herdrdesktop.workspace.WorkspaceScreen

/**
 * One window of the app (REQ-DM-001). Each instance is its own task, hence its own resizable window in
 * Desktop Mode; size, display and density changes are handled by Compose without recreating it.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as HerdrApp).container
        val target = WindowTarget(
            intent.getStringExtra(WindowTarget.EXTRA_MACHINE),
            intent.getStringExtra(WindowTarget.EXTRA_SPACE)
        )
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                // Test tags as resource ids, so that UiAutomator finds them in the instrumented tests.
                Box(Modifier.semantics { testTagsAsResourceId = true }) { Content(container, target) }
            }
        }
    }

    @Composable
    private fun Content(container: AppContainer, target: WindowTarget) {
        var openMachine by rememberSaveable { mutableStateOf(target.machineId) }
        var focus by remember { mutableStateOf<PaneFocus?>(null) }
        var peek by remember { mutableStateOf<PeekRequest?>(null) }
        val open = openMachine
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                if (open == null) {
                    MachinesScreen(container, onOpen = { openMachine = it })
                } else {
                    val window = WindowOptions(
                        initialSpace = target.spaceId.takeIf { open == target.machineId },
                        onNewWindow = { space ->
                            startActivity(windowIntent(this@MainActivity, WindowTarget(open, space)))
                        }
                    )
                    val agents = AgentRequests(focus, peek) { peek = null }
                    WorkspaceScreen(container, open, agents, window, onBack = { openMachine = null })
                }
            }
            VerticalDivider()
            AgentSidebar(
                container,
                onOpen = {
                    openMachine = it.machineId
                    peek = null
                    focus = it
                },
                onPeek = {
                    openMachine = it.machineId
                    peek = it
                }
            )
        }
        // Runtime permissions need an activity: asked here for the machines waiting for it.
        val statuses by container.connections.statuses.collectAsState()
        LocalNetworkPermissionRequest(statuses, container.connections)
    }

    companion object {
        /** Intent of a new, separate window: each window is its own document task. */
        fun windowIntent(context: Context, target: WindowTarget): Intent = Intent(context, MainActivity::class.java)
            .putExtra(WindowTarget.EXTRA_MACHINE, target.machineId)
            .putExtra(WindowTarget.EXTRA_SPACE, target.spaceId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
    }
}
