package io.github.totocastaldi.herdrdesktop.coreclient.hostkey

import io.github.totocastaldi.herdrdesktop.coreclient.keys.Fingerprint
import java.util.Base64

/** A server host key as presented during the SSH handshake: algorithm name and wire blob. */
data class HostKey(val type: String, val blob: ByteArray) {
    val fingerprint: String get() = Fingerprint.sha256(blob)

    /** Base64 of the blob, as stored with the machine. */
    val encoded: String get() = Base64.getEncoder().encodeToString(blob)

    override fun equals(other: Any?): Boolean = other is HostKey && other.type == type && other.blob.contentEquals(blob)

    override fun hashCode(): Int = 31 * type.hashCode() + blob.contentHashCode()

    companion object {
        fun decode(type: String, encoded: String): HostKey = HostKey(type, Base64.getDecoder().decode(encoded))
    }
}

/** Outcome of checking a presented host key against the one the user confirmed for the machine. */
sealed interface HostKeyDecision {
    /** The presented key is the confirmed one: the connection may proceed. */
    data object Trusted : HostKeyDecision

    /** No key confirmed yet: the user must confirm [presented] before connecting. */
    data class Unconfirmed(val presented: HostKey) : HostKeyDecision

    /** The key differs from the confirmed one: the connection is blocked. */
    data class Changed(val confirmed: HostKey, val presented: HostKey) : HostKeyDecision
}

/** Host key pinning (REQ-NFR-001): only the confirmed key is accepted, never an unknown or changed one. */
object HostKeyPolicy {
    fun evaluate(confirmed: HostKey?, presented: HostKey): HostKeyDecision = when {
        confirmed == null -> HostKeyDecision.Unconfirmed(presented)
        confirmed == presented -> HostKeyDecision.Trusted
        else -> HostKeyDecision.Changed(confirmed, presented)
    }
}
