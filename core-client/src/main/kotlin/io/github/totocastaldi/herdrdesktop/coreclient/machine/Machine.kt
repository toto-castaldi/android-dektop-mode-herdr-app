package io.github.totocastaldi.herdrdesktop.coreclient.machine

import io.github.totocastaldi.herdrdesktop.coreclient.hostkey.HostKey

/** A machine running herdr-core, saved in the app (docs/design/data-model.md). */
data class Machine(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = DEFAULT_PORT,
    val user: String,
    val auth: MachineAuth,
    val hostKey: HostKey? = null
) {
    companion object {
        const val DEFAULT_PORT = 22
    }
}

/** How the app authenticates to a machine. Secrets are never stored in clear. */
sealed interface MachineAuth {
    /** A key of the Android Keystore, referenced by alias. */
    data class Key(val alias: String) : MachineAuth

    /** A password, encrypted with a Keystore key (`iv || ciphertext`). */
    class Password(val sealed: ByteArray) : MachineAuth {
        override fun equals(other: Any?): Boolean = other is Password && other.sealed.contentEquals(sealed)

        override fun hashCode(): Int = sealed.contentHashCode()

        override fun toString(): String = "Password(***)"
    }
}

/** Fields the user edits; validated before becoming a [Machine]. */
data class MachineDraft(val name: String, val host: String, val port: String, val user: String) {
    fun validate(): List<MachineField> = buildList {
        if (name.isBlank()) add(MachineField.NAME)
        if (host.isBlank() || host.any { it.isWhitespace() }) add(MachineField.HOST)
        if (port.trim().toIntOrNull()?.takeIf { it in PORT_RANGE } == null) add(MachineField.PORT)
        if (user.isBlank() || user.any { it.isWhitespace() }) add(MachineField.USER)
    }

    private companion object {
        val PORT_RANGE = 1..65535
    }
}

enum class MachineField { NAME, HOST, PORT, USER }
