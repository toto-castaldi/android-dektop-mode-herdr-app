package io.github.totocastaldi.herdrdesktop.coreclient.agents

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Terminals of one core as seen by an `events.subscribe` subscriber: the snapshot and every event applied in
 * order (`schema/README.md` of herdr-core, section Events). Terminals of machines added to that core are in it
 * too, addressed as `machine/t7`.
 */
data class AgentSnapshot(val seq: Long, val records: Map<String, AgentRecord>) {
    /** The agents, in the core's order. */
    val agents: List<AgentRecord> get() = records.values.filter { it.isAgent }

    /** Applies one notification of the subscription channel; anything unknown leaves the snapshot as it is. */
    fun apply(notification: JsonObject): AgentSnapshot {
        val params = notification["params"] as? JsonObject ?: return this
        return when (notification.string("method")) {
            "missed" -> from(params)
            "event" -> event(params)
            else -> this
        }
    }

    private fun event(params: JsonObject): AgentSnapshot {
        val seq = params["seq"].long()
        if (seq == null || seq <= this.seq) return this
        val data = params["data"] as? JsonObject ?: JsonObject(emptyMap())
        return AgentSnapshot(seq, records(params.string("type"), params.string("terminal"), data) ?: records)
    }

    /** The records after an event; null when it changes nothing the sidebar shows. */
    private fun records(type: String?, address: String?, data: JsonObject): Map<String, AgentRecord>? {
        val current = address?.let { records[it] }
        return when (type) {
            "created", "restarted" -> AgentRecord.from(data)?.let { records + (it.address to it) }
            "removed" -> address?.let { records - it }
            "labels" -> labels(address, current, data)
            else -> current?.let { change(type, it, data) }?.let { records + (it.address to it) }
        }
    }

    /** The record after an event of [type] about it; null when the event does not concern the sidebar. */
    private fun change(type: String?, current: AgentRecord, data: JsonObject): AgentRecord? = when (type) {
        "finished" -> current.copy(live = false, status = null)
        "status" -> current.withStatus(data)
        "turn" -> data["turn"].long()?.let { current.copy(turn = it) }
        "title" -> data.string("title")?.let { current.copy(title = it) }
        else -> null
    }

    private fun labels(address: String?, current: AgentRecord?, data: JsonObject): Map<String, AgentRecord>? {
        val added = (data["record"] as? JsonObject)?.let(AgentRecord::from)
        val record = (added ?: current) ?: return null
        val labels = data["labels"]?.let(AgentRecord::labelsOf) ?: record.labels
        return records + ((address ?: record.address) to record.copy(labels = labels))
    }

    companion object {
        val EMPTY = AgentSnapshot(0, emptyMap())

        /** From the result of `events.subscribe` or the params of `missed`: `seq` and the terminals. */
        fun from(o: JsonObject): AgentSnapshot {
            val terminals = (o["terminals"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.let(AgentRecord::from) }
            return AgentSnapshot(o["seq"].long() ?: 0, terminals.associateBy { it.address })
        }

        internal fun channelOf(result: JsonElement): Int? = (result as? JsonObject)?.get("channel").long()?.toInt()
    }
}
