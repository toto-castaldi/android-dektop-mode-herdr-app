package io.github.totocastaldi.herdrdesktop.machines

/** What the dialog returns; [password] is null when the saved one is kept. */
class MachineInput(
    val name: String,
    val host: String,
    val port: Int,
    val user: String,
    val useKey: Boolean,
    val password: CharArray?
)
