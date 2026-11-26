package io.github.totocastaldi.herdrdesktop.machines

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import io.github.totocastaldi.herdrdesktop.coreclient.connection.ConnectionManager
import io.github.totocastaldi.herdrdesktop.coreclient.connection.LocalNetwork
import io.github.totocastaldi.herdrdesktop.coreclient.connection.LocalNetworkAccess
import io.github.totocastaldi.herdrdesktop.coreclient.connection.MachineStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Android's `ACCESS_LOCAL_NETWORK` runtime permission, needed for hosts on the local network (BUG-001). */
class AndroidLocalNetworkAccess(private val context: Context) : LocalNetworkAccess {
    override suspend fun needsPermission(host: String): Boolean =
        withContext(Dispatchers.IO) { LocalNetwork.isLocalHost(host) }

    override fun granted(): Boolean = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** App details in Android settings, where the user grants the permission after denying it. */
    fun openAppSettings(): Boolean = try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    companion object {
        const val PERMISSION = Manifest.permission.ACCESS_LOCAL_NETWORK
    }
}

/** Asks for the local network permission while a machine waits for it, and hands the answer to [connections]. */
@Composable
fun LocalNetworkPermissionRequest(statuses: Map<String, MachineStatus>, connections: ConnectionManager) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        connections.localNetworkPermissionResult(granted)
    }
    val waiting = statuses.values.any { it == MachineStatus.LocalNetworkPermissionNeeded }
    LaunchedEffect(waiting) {
        if (waiting) launcher.launch(AndroidLocalNetworkAccess.PERMISSION)
    }
}
