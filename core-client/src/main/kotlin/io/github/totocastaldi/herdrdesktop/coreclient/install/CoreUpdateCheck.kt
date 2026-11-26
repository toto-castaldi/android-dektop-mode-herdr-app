package io.github.totocastaldi.herdrdesktop.coreclient.install

import io.github.totocastaldi.herdrdesktop.coreclient.machine.Machine
import io.github.totocastaldi.herdrdesktop.coreclient.protocol.CoreProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Whether a newer herdr-core is published (REQ-CONN-004). The manifest is read by the machine itself
 * over SSH: the app never contacts releases.herdr.dev directly. Any failure means "no update known".
 */
class CoreUpdateCheck(private val runner: CommandRunner) {
    /** The published version if it is newer than [installed], otherwise null (also on any failure). */
    suspend fun availableUpdate(machine: Machine, installed: String): String? =
        when (val result = runner.run(machine, LATEST_COMMAND) { }) {
            is CommandResult.Exited -> if (result.succeeded) newerVersion(installed, result.output) else null
            is CommandResult.NotRun -> null
        }

    companion object {
        const val MANIFEST_URL = "https://releases.herdr.dev/core/latest.json"

        /** Downloads the manifest on the machine; bounded so that a stuck download does not linger. */
        const val LATEST_COMMAND = "curl -fsSL --max-time 20 $MANIFEST_URL"

        private val VERSION = Regex("""\d+(\.\d+)*(-[0-9A-Za-z.-]+)?""")
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The `version` of the manifest [text], or null if the text is not a manifest, the version is
         * not a dotted number, or the manifest is for a protocol generation the app does not speak
         * (updating would make the core unusable).
         */
        fun parseLatestVersion(text: String): String? {
            val manifest = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            val version = (manifest?.get("version") as? JsonPrimitive)?.takeIf { it.isString }?.content
            val generation = manifest?.get("generation")
            val sameGeneration = generation == null ||
                (generation as? JsonPrimitive)?.intOrNull == CoreProtocol.GENERATION
            return version?.takeIf { sameGeneration && VERSION.matches(it) }
        }

        /** The version of the manifest [text] if newer than [installed], otherwise null. */
        fun newerVersion(installed: String, text: String): String? =
            parseLatestVersion(text)?.takeIf { CoreProtocol.compareVersions(it, installed) > 0 }
    }
}
