package io.github.totocastaldi.herdrdesktop.coreclient.keys

import java.security.MessageDigest
import java.util.Base64

/** OpenSSH-style fingerprints of SSH key blobs. */
object Fingerprint {
    /** `SHA256:<base64 without padding>`, as printed by `ssh-keygen -l`. */
    fun sha256(blob: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }
}
