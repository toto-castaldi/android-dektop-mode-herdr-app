package io.github.totocastaldi.herdrdesktop.coreclient.machine

import io.github.totocastaldi.herdrdesktop.coreclient.hostkey.HostKey
import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** JSON format of the saved machine list. Version 1. */
object MachineCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(machines: List<Machine>): String =
        json.encodeToString(StoredMachines.serializer(), StoredMachines(machines = machines.map { it.toStored() }))

    fun decode(text: String): List<Machine> =
        json.decodeFromString(StoredMachines.serializer(), text).machines.map { it.toMachine() }

    private fun Machine.toStored() = StoredMachine(
        id = id,
        name = name,
        host = host,
        port = port,
        user = user,
        keyAlias = (auth as? MachineAuth.Key)?.alias,
        sealedPassword = (auth as? MachineAuth.Password)?.sealed?.let { Base64.getEncoder().encodeToString(it) },
        hostKeyType = hostKey?.type,
        hostKey = hostKey?.encoded
    )

    private fun StoredMachine.toMachine(): Machine {
        val auth = when {
            keyAlias != null -> MachineAuth.Key(keyAlias)
            sealedPassword != null -> MachineAuth.Password(Base64.getDecoder().decode(sealedPassword))
            else -> error("Machine $id has no authentication method")
        }
        val pinned = if (hostKeyType != null && hostKey != null) HostKey.decode(hostKeyType, hostKey) else null
        return Machine(id, name, host, port, user, auth, pinned)
    }

    @Serializable
    private data class StoredMachines(val version: Int = 1, val machines: List<StoredMachine>)

    @Serializable
    private data class StoredMachine(
        val id: String,
        val name: String,
        val host: String,
        val port: Int,
        val user: String,
        @SerialName("key_alias") val keyAlias: String? = null,
        @SerialName("sealed_password") val sealedPassword: String? = null,
        @SerialName("host_key_type") val hostKeyType: String? = null,
        @SerialName("host_key") val hostKey: String? = null
    )
}
