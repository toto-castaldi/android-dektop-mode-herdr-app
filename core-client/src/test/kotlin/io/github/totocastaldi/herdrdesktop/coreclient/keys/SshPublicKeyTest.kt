package io.github.totocastaldi.herdrdesktop.coreclient.keys

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SshPublicKeyTest {
    @Test
    fun `ed25519 blob and authorized_keys line follow RFC 8709`() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val key = SshPublicKey.ed25519FromX509(keyPair.public.encoded)
        val raw = keyPair.public.encoded.takeLast(32).toByteArray()

        val expected = byteArrayOf(0, 0, 0, 11) + "ssh-ed25519".toByteArray() + byteArrayOf(0, 0, 0, 32) + raw
        assertArrayEquals(expected, key.blob)
        val line = key.authorizedKeysLine()
        assertEquals("ssh-ed25519 ${Base64.getEncoder().encodeToString(expected)} herdr-desktop-android", line)
        assertEquals("ssh-ed25519", SshWire.leadingString(key.blob))
    }

    @Test
    fun `ecdsa blob carries the uncompressed 65-byte point`() {
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val pub = keyPair.public as ECPublicKey
        val key = SshPublicKey.ecdsaP256(pub.w.affineX, pub.w.affineY)

        val header =
            byteArrayOf(0, 0, 0, 19) + "ecdsa-sha2-nistp256".toByteArray() + byteArrayOf(0, 0, 0, 8) +
                "nistp256".toByteArray()
        assertArrayEquals(header, key.blob.copyOfRange(0, header.size))
        assertArrayEquals(byteArrayOf(0, 0, 0, 65, 4), key.blob.copyOfRange(header.size, header.size + 5))
        assertEquals(header.size + 4 + 65, key.blob.size)
    }

    @Test
    fun `ecdsa signature is converted from DER to two mpints`() {
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update("data".toByteArray())
            sign()
        }
        val (r, s) = Der.ecdsaSignature(der)
        val blob = SshSignatureEncoding.ecdsaP256(der)

        assertEquals("ecdsa-sha2-nistp256", SshWire.leadingString(blob))
        val inner = SshWire.build {
            SshWire.mpint(it, r)
            SshWire.mpint(it, s)
        }
        assertArrayEquals(inner, blob.copyOfRange(blob.size - inner.size, blob.size))
    }

    @Test
    fun `der parser reads short integers`() {
        val der = byteArrayOf(0x30, 6, 0x02, 1, 5, 0x02, 1, 7)
        assertEquals(BigInteger.valueOf(5) to BigInteger.valueOf(7), Der.ecdsaSignature(der))
    }

    @Test
    fun `ed25519 signature blob wraps the raw signature`() {
        val sig = ByteArray(64) { it.toByte() }
        val blob = SshSignatureEncoding.ed25519(sig)
        assertEquals("ssh-ed25519", SshWire.leadingString(blob))
        assertArrayEquals(sig, blob.copyOfRange(blob.size - 64, blob.size))
    }
}
