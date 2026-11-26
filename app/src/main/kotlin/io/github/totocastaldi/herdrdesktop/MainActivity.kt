package io.github.totocastaldi.herdrdesktop

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.totocastaldi.herdrdesktop.agents.AgentSidebar
import io.github.totocastaldi.herdrdesktop.agents.PaneFocus
import io.github.totocastaldi.herdrdesktop.machines.LocalNetworkPermissionRequest
import io.github.totocastaldi.herdrdesktop.machines.MachinesScreen
import io.github.totocastaldi.herdrdesktop.workspace.WorkspaceScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as HerdrApp).container
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var openMachine by rememberSaveable { mutableStateOf<String?>(null) }
                var focus by remember { mutableStateOf<PaneFocus?>(null) }
                val open = openMachine
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        if (open == null) {
                            MachinesScreen(container, onOpen = { openMachine = it })
                        } else {
                            WorkspaceScreen(container, open, focus, onBack = { openMachine = null })
                        }
                    }
                    VerticalDivider()
                    AgentSidebar(container) {
                        openMachine = it.machineId
                        focus = it
                    }
                }
                // Runtime permissions need an activity: asked here for the machines waiting for it.
                val statuses by container.connections.statuses.collectAsState()
                LocalNetworkPermissionRequest(statuses, container.connections)
            }
        }
    }
}
