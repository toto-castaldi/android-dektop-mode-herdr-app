package io.github.totocastaldi.herdrdesktop.coreclient.agents

/** JSON of herdr-core terminal records and notifications, as they arrive on the wire. */
object AgentEventsJson {
    @Suppress("LongParameterList")
    fun terminal(
        address: String,
        status: String? = null,
        turn: Long = 0,
        seen: Long? = null,
        parent: String? = null,
        name: String? = null,
        state: String = "live"
    ): String {
        val statusJson = status?.let { """{"app":"claude-code","msg":"msg of $address","state":"$it"}""" } ?: "null"
        val labels = seen?.let { """{"herdr.seen":"$it"}""" } ?: "{}"
        val nameJson = name?.let { "\"$it\"" } ?: "null"
        val parentJson = parent?.let { "\"$it\"" } ?: "null"
        return """{"address":"$address","name":$nameJson,"parent":$parentJson,"state":"$state",""" +
            """"reported":${status != null},"status":$statusJson,"turn":$turn,"labels":$labels,"title":""}"""
    }

    fun snapshot(seq: Long, vararg terminals: String, channel: Int = 1) =
        """{"seq":$seq,"channel":$channel,"terminals":[${terminals.joinToString(",")}]}"""

    fun event(seq: Long, type: String, terminal: String, data: String) =
        """{"jsonrpc":"2.0","method":"event","params":{"seq":$seq,"type":"$type","terminal":"$terminal",""" +
            """"data":$data}}"""

    fun status(seq: Long, terminal: String, state: String?) = event(
        seq,
        "status",
        terminal,
        """{"reported":true,"records":{},"status":${state?.let { """{"state":"$it","msg":"m"}""" } ?: "null"}}"""
    )

    fun turn(seq: Long, terminal: String, turn: Long) =
        event(seq, "turn", terminal, """{"turn":$turn,"state":"done"}""")

    fun labels(seq: Long, terminal: String, seen: Long) =
        event(seq, "labels", terminal, """{"labels":{"herdr.seen":"$seen"}}""")
}
