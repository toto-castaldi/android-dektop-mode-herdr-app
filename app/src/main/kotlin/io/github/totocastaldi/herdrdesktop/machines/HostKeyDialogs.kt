package io.github.totocastaldi.herdrdesktop.machines

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.totocastaldi.herdrdesktop.R
import io.github.totocastaldi.herdrdesktop.coreclient.hostkey.HostKey

@Composable
fun HostKeyConfirmDialog(machineName: String, key: HostKey, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.host_key_confirm_title, machineName)) },
        text = {
            Column {
                Text(stringResource(R.string.host_key_confirm_text))
                Spacer(Modifier.height(12.dp))
                KeyLine(null, key)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.host_key_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
fun HostKeyChangedDialog(machineName: String, confirmed: HostKey, presented: HostKey, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.host_key_changed_title, machineName), color = MaterialTheme.colorScheme.error)
        },
        text = {
            Column {
                Text(stringResource(R.string.host_key_changed_text))
                Spacer(Modifier.height(12.dp))
                KeyLine(stringResource(R.string.host_key_confirmed_label), confirmed)
                Spacer(Modifier.height(8.dp))
                KeyLine(stringResource(R.string.host_key_presented_label), presented)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } }
    )
}

@Composable
private fun KeyLine(label: String?, key: HostKey) {
    Column {
        if (label != null) Text(label, style = MaterialTheme.typography.labelMedium)
        SelectionContainer {
            Text("${key.type}\n${key.fingerprint}", fontFamily = FontFamily.Monospace)
        }
    }
}
