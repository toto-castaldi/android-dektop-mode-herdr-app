package io.github.totocastaldi.herdrdesktop

import android.content.Context
import android.content.Intent
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
                var openMachine by rememberSaveable { mutableStateOf(target.machineId) }
                val open = openMachine
                if (open == null) {
                    MachinesScreen(container, onOpen = { openMachine = it })
                } else {
                    WorkspaceScreen(
                        container,
                        open,
                        initialSpace = target.spaceId.takeIf { open == target.machineId },
                        onNewWindow = { space -> startActivity(windowIntent(this, WindowTarget(open, space))) },
                        onBack = { openMachine = null }
                    )
                }
                // Runtime permissions need an activity: asked here for the machines waiting for it.
                val statuses by container.connections.statuses.collectAsState()
                LocalNetworkPermissionRequest(statuses, container.connections)
            }
        }
    }

    companion object {
        /** Intent of a new, separate window: each window is its own document task. */
        fun windowIntent(context: Context, target: WindowTarget): Intent = Intent(context, MainActivity::class.java)
            .putExtra(WindowTarget.EXTRA_MACHINE, target.machineId)
            .putExtra(WindowTarget.EXTRA_SPACE, target.spaceId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
    }
}
