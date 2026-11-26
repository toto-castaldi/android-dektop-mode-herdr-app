package io.github.totocastaldi.herdrdesktop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
                val open = openMachine
                if (open == null) {
                    MachinesScreen(container, onOpen = { openMachine = it })
                } else {
                    WorkspaceScreen(container, open, onBack = { openMachine = null })
                }
                // Runtime permissions need an activity: asked here for the machines waiting for it.
                val statuses by container.connections.statuses.collectAsState()
                LocalNetworkPermissionRequest(statuses, container.connections)
            }
        }
    }
}
