package io.github.totocastaldi.herdrdesktop.machines

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.totocastaldi.herdrdesktop.R
import io.github.totocastaldi.herdrdesktop.coreclient.machine.Machine
import io.github.totocastaldi.herdrdesktop.coreclient.machine.MachineAuth
import io.github.totocastaldi.herdrdesktop.coreclient.machine.MachineDraft
import io.github.totocastaldi.herdrdesktop.coreclient.machine.MachineField

/** Editable fields of the machine dialog. */
private class MachineForm(machine: Machine?) {
    var name by mutableStateOf(machine?.name.orEmpty())
    var host by mutableStateOf(machine?.host.orEmpty())
    var port by mutableStateOf((machine?.port ?: Machine.DEFAULT_PORT).toString())
    var user by mutableStateOf(machine?.user.orEmpty())
    var useKey by mutableStateOf(machine?.auth !is MachineAuth.Password)
    var password by mutableStateOf("")
    var errors by mutableStateOf(emptyList<MachineField>())
    val hasSavedPassword = machine?.auth is MachineAuth.Password

    fun canSave(keyAvailable: Boolean) = if (useKey) keyAvailable else password.isNotEmpty() || hasSavedPassword

    /** Validates the fields; returns the input to save, or null and shows the errors. */
    fun submit(): MachineInput? {
        errors = MachineDraft(name, host, port, user).validate()
        if (errors.isNotEmpty()) return null
        return MachineInput(
            name = name,
            host = host,
            port = port.trim().toInt(),
            user = user,
            useKey = useKey,
            password = password.takeIf { !useKey && it.isNotEmpty() }?.toCharArray()
        ).also { password = "" }
    }
}

@Composable
fun MachineDialog(machine: Machine?, keyAvailable: Boolean, onSave: (MachineInput) -> Unit, onDismiss: () -> Unit) {
    val form = remember { MachineForm(machine) }
    val title = if (machine == null) R.string.machine_dialog_add else R.string.machine_dialog_edit
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { MachineFormFields(form, keyAvailable) },
        confirmButton = {
            TextButton(enabled = form.canSave(keyAvailable), onClick = { form.submit()?.let(onSave) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun MachineFormFields(form: MachineForm, keyAvailable: Boolean) {
    Column {
        Field(R.string.field_name, form.name, MachineField.NAME in form.errors) { form.name = it }
        Field(R.string.field_host, form.host, MachineField.HOST in form.errors) { form.host = it }
        Field(R.string.field_port, form.port, MachineField.PORT in form.errors, KeyboardType.Number) { form.port = it }
        Field(R.string.field_user, form.user, MachineField.USER in form.errors) { form.user = it }
        Spacer(Modifier.height(8.dp))
        AuthChoice(R.string.auth_key, form.useKey) { form.useKey = true }
        if (form.useKey && !keyAvailable) {
            Text(stringResource(R.string.auth_key_missing), color = MaterialTheme.colorScheme.error)
        }
        AuthChoice(R.string.auth_password, !form.useKey) { form.useKey = false }
        if (!form.useKey) PasswordField(form)
    }
}

@Composable
private fun PasswordField(form: MachineForm) {
    OutlinedTextField(
        value = form.password,
        onValueChange = { form.password = it },
        label = { Text(stringResource(R.string.field_password)) },
        supportingText = if (form.hasSavedPassword) {
            { Text(stringResource(R.string.field_password_keep)) }
        } else {
            null
        },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun Field(
    label: Int,
    value: String,
    invalid: Boolean,
    keyboardType: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        isError = invalid,
        supportingText = if (invalid) {
            { Text(stringResource(R.string.field_invalid)) }
        } else {
            null
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun AuthChoice(label: Int, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect)
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(4.dp))
        Text(stringResource(label))
    }
}
