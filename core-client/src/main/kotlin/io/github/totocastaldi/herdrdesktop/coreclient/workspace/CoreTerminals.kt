package io.github.totocastaldi.herdrdesktop.coreclient.workspace

import io.github.totocastaldi.herdrdesktop.coreclient.agents.AgentRecord
import io.github.totocastaldi.herdrdesktop.coreclient.connection.CoreRpc
import io.github.totocastaldi.herdrdesktop.coreclient.protocol.RpcException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** A terminal of the core as far as spaces and tabs need it; [cwd] is its current folder. */
data class TerminalInfo(
    val address: String,
    val cwd: String,
    val live: Boolean,
    val labels: Map<String, String> = emptyMap()
)

/** Starts, lists and ends terminals on a core. */
class CoreTerminals(private val rpc: CoreRpc) {
    /** Live terminals of the core (also the finished ones with [all]), by address, with their current folder. */
    suspend fun list(all: Boolean = false): Map<String, TerminalInfo> {
        val result = rpc.call("terminal.list", if (all) buildJsonObject { put("all", true) } else null) as JsonObject
        return (result["terminals"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.toTerminal() }
            .associateBy { it.address }
    }

    /**
     * Starts the core's shell in [folder] (the core's default when null) for tab [tabId] of space [spaceId]:
     * labelled with their ids, with the app's own labels so that the desktop app does not adopt it, and as
     * started by this app.
     */
    suspend fun runShell(folder: String?, spaceId: String, tabId: String): TerminalInfo {
        val params = buildJsonObject {
            if (!folder.isNullOrBlank()) put("cwd", folder)
            putJsonObject("labels") {
                put(APP_LABEL, APP_LABEL_VALUE)
                put(SPACE_LABEL, spaceId)
                put(TAB_LABEL, tabId)
                // Shared Herdr rule: a terminal a client starts has nothing unseen yet.
                put(AgentRecord.SEEN_LABEL, "0")
            }
        }
        val result = rpc.call("terminal.run", params) as JsonObject
        return checkNotNull(result.toTerminal()) { "terminal.run returned no address" }
    }

    /**
     * Labels [address] as shown in tab [tabId] of space [spaceId] of the app and removes the desktop app's
     * space and tab labels; a terminal already gone is not an error.
     */
    suspend fun place(address: String, spaceId: String, tabId: String) {
        val params = buildJsonObject {
            put("terminal", address)
            putJsonObject("labels") {
                put(SPACE_LABEL, spaceId)
                put(TAB_LABEL, tabId)
                // `key=` removes a label.
                put(DESKTOP_SPACE_LABEL, "")
                put(DESKTOP_TAB_LABEL, "")
            }
        }
        ignoreGone { rpc.call("terminal.label", params) }
    }

    /** Ends the program of [address] and removes the terminal; one already ended or gone is not an error. */
    suspend fun end(address: String) {
        val params = buildJsonObject { put("terminal", address) }
        ignoreGone { rpc.call("terminal.kill", params) }
        ignoreGone { rpc.call("terminal.rm", params) }
    }

    private suspend fun ignoreGone(call: suspend () -> JsonElement) {
        try {
            call()
        } catch (e: RpcException) {
            if (e.error.kind !in GONE) throw e
        }
    }

    companion object {
        /** Errors of kill and rm meaning that the terminal has already ended or is gone. */
        val GONE = setOf("not_found", "finished")

        /** Label on the terminals this app starts. */
        const val APP_LABEL = "app"
        const val APP_LABEL_VALUE = "herdr-android"

        /** Space and tab of a terminal, by their ids in the app's document. */
        const val SPACE_LABEL = AgentRecord.APP_SPACE_LABEL
        const val TAB_LABEL = AgentRecord.APP_TAB_LABEL

        /** The desktop app's space and tab labels: never put on the app's terminals (REQ-WS-001). */
        const val DESKTOP_SPACE_LABEL = "herdr.space"
        const val DESKTOP_TAB_LABEL = "herdr.tab"

        /** True when [labels] say the app's own space and tab are [spaceId] and [tabId], and nothing else. */
        fun placedIn(labels: Map<String, String>, spaceId: String, tabId: String): Boolean =
            labels[SPACE_LABEL] == spaceId && labels[TAB_LABEL] == tabId &&
                DESKTOP_SPACE_LABEL !in labels && DESKTOP_TAB_LABEL !in labels
    }
}

private fun JsonObject.toTerminal(): TerminalInfo? {
    val address = (this["address"] as? JsonPrimitive)?.content ?: return null
    val foreground = this["foreground"] as? JsonObject
    val cwd = (foreground?.get("cwd") as? JsonPrimitive)?.content ?: (this["cwd"] as? JsonPrimitive)?.content.orEmpty()
    val state = (this["state"] as? JsonPrimitive)?.content
    val labels = (this["labels"] as? JsonObject).orEmpty()
        .mapNotNull { (k, v) -> (v as? JsonPrimitive)?.let { k to it.content } }.toMap()
    return TerminalInfo(address, cwd, state == null || state == "live", labels)
}
