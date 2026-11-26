package io.github.totocastaldi.herdrdesktop.coreclient.keys

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import java.security.Signature
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreTest {
    private val alias = "test-ssh-key"
    private val keys = AndroidKeystoreSshKeys()

    @After
    fun cleanUp() = keys.delete(alias)

    @Test
    fun generatedKeySignsVerifiablyAndStaysInTheKeystore() {
        val publicKey = keys.generate(alias)
        Log.i("KeystoreTest", "generated ${publicKey.algorithm.sshName}")
        assertEquals(publicKey, keys.publicKey(alias))
        assertTrue(publicKey.authorizedKeysLine().startsWith(publicKey.algorithm.sshName + " "))

        val data = "session-id-and-request".toByteArray()
        val blob = keys.sign(alias, data)
        assertEquals(publicKey.algorithm.sshName, SshWire.leadingString(blob))

        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val jcaPublic = keyStore.getCertificate(alias).publicKey
        val entry = keyStore.getEntry(alias, null) as KeyStore.PrivateKeyEntry
        // The private key is opaque: no exportable encoding.
        assertEquals(null, entry.privateKey.encoded)
        if (publicKey.algorithm == SshKeyAlgorithm.ED25519) {
            val raw = blob.copyOfRange(blob.size - 64, blob.size)
            val ok = Signature.getInstance("Ed25519").run {
                initVerify(jcaPublic)
                update(data)
                verify(raw)
            }
            assertTrue(ok)
        }
    }

    @Test
    fun secretCipherRoundTripsAndHidesThePlaintext() {
        val cipher = AndroidKeystoreSecretCipher("test-secret-cipher")
        val plain = "not-a-real-password".toByteArray()
        val sealed = cipher.encrypt(plain)
        assertTrue(!String(sealed, Charsets.ISO_8859_1).contains("not-a-real-password"))
        assertArrayEquals(plain, cipher.decrypt(sealed))
    }
}
