package io.github.totocastaldi.herdrdesktop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.totocastaldi.herdrdesktop.machines.LocalNetworkPermissionRequest
import io.github.totocastaldi.herdrdesktop.machines.MachinesScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as HerdrApp).container
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                MachinesScreen(container)
                // Runtime permissions need an activity: asked here for the machines waiting for it.
                val statuses by container.connections.statuses.collectAsState()
                LocalNetworkPermissionRequest(statuses, container.connections)
            }
        }
    }
}
