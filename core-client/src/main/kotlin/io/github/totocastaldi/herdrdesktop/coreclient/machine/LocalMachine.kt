package io.github.totocastaldi.herdrdesktop.coreclient.machine

import io.github.totocastaldi.herdrdesktop.coreclient.keys.SshKeyStore

/**
 * The predefined machine "this device": herdr-core in the Debian VM of the Android Linux Terminal,
 * reached through the port the Terminal forwards to Android's loopback (docs/design/integrations.md).
 * Same transport, host key check and Keystore key as the remote machines.
 */
object LocalMachine {
    const val ID = "local"
    const val HOST = "127.0.0.1"
    const val PORT = 2222
    const val USER = "droid"

    /** Package of the Android Linux Terminal app. */
    const val TERMINAL_PACKAGE = "com.android.virtualization.terminal"

    fun create(name: String): Machine = Machine(ID, name, HOST, PORT, USER, MachineAuth.Key(SshKeyStore.DEFAULT_ALIAS))

    /**
     * Command the user pastes in the Linux Terminal: installs openssh-server, makes it listen on
     * [PORT] (Android forwards only ports from 1024) and authorizes [authorizedKeysLine] once.
     */
    fun setupCommand(authorizedKeysLine: String): String {
        require(authorizedKeysLine.none { it == '\'' || it == '\n' }) { "Unexpected characters in the public key line" }
        val key = "'$authorizedKeysLine'"
        return listOf(
            "sudo apt-get update",
            "sudo apt-get install -y openssh-server",
            "echo 'Port $PORT' | sudo tee /etc/ssh/sshd_config.d/port.conf",
            "sudo systemctl restart ssh",
            "mkdir -p ~/.ssh",
            "chmod 700 ~/.ssh",
            "touch ~/.ssh/authorized_keys",
            "chmod 600 ~/.ssh/authorized_keys",
            "{ grep -qxF $key ~/.ssh/authorized_keys || echo $key >> ~/.ssh/authorized_keys; }"
        ).joinToString(" && ")
    }
}

val Machine.isLocal: Boolean get() = id == LocalMachine.ID
