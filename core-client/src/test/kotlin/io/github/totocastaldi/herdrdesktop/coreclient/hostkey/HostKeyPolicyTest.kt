package io.github.totocastaldi.herdrdesktop.coreclient.hostkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostKeyPolicyTest {
    private val key = HostKey("ssh-ed25519", byteArrayOf(1, 2, 3))
    private val sameKeyCopy = HostKey("ssh-ed25519", byteArrayOf(1, 2, 3))
    private val otherKey = HostKey("ssh-ed25519", byteArrayOf(9, 9, 9))

    @Test
    fun `first connection asks to confirm the presented key`() {
        assertEquals(HostKeyDecision.Unconfirmed(key), HostKeyPolicy.evaluate(confirmed = null, presented = key))
    }

    @Test
    fun `confirmed key is trusted`() {
        assertEquals(HostKeyDecision.Trusted, HostKeyPolicy.evaluate(confirmed = key, presented = sameKeyCopy))
    }

    @Test
    fun `changed key is blocked`() {
        assertEquals(
            HostKeyDecision.Changed(key, otherKey),
            HostKeyPolicy.evaluate(confirmed = key, presented = otherKey)
        )
    }

    @Test
    fun `same blob with another algorithm is a changed key`() {
        val decision = HostKeyPolicy.evaluate(key, HostKey("ecdsa-sha2-nistp256", key.blob))
        assertTrue(decision is HostKeyDecision.Changed)
    }

    @Test
    fun `fingerprint has the OpenSSH SHA256 format`() {
        // `printf '\x01\x02\x03' | sha256sum` = 039058c6…, base64 without padding.
        assertEquals("SHA256:A5BYxvLAy0ksUzsKTRTvd8wPeKvMztUofYShogEc+4E", key.fingerprint)
    }

    @Test
    fun `encoded form round-trips`() {
        assertEquals(key, HostKey.decode(key.type, key.encoded))
    }
}
