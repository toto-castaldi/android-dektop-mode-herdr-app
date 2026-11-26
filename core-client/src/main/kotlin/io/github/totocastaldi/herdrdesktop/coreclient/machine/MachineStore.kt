package io.github.totocastaldi.herdrdesktop.coreclient.machine

import java.io.File

/** Persistence of the machine list. */
interface MachineStore {
    fun load(): List<Machine>

    fun save(machines: List<Machine>)
}

/** Stores the list as JSON in a private app file, replaced atomically. */
class FileMachineStore(private val file: File) : MachineStore {
    override fun load(): List<Machine> = if (file.exists()) MachineCodec.decode(file.readText()) else emptyList()

    override fun save(machines: List<Machine>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(MachineCodec.encode(machines))
        check(tmp.renameTo(file)) { "Cannot replace ${file.name}" }
    }
}
