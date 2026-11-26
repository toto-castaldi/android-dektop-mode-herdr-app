package io.github.totocastaldi.herdrdesktop.coreclient.keys

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * [SshKeyStore] backed by the Android Keystore: Ed25519 where the Keystore supports it,
 * ECDSA P-256 otherwise. Private keys are not exportable.
 */
class AndroidKeystoreSshKeys : SshKeyStore {
    private val keyStore: KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    override fun publicKey(alias: String): SshPublicKey? {
        val certificate = keyStore.getCertificate(alias) ?: return null
        val key = certificate.publicKey
        return if (key is ECPublicKey) {
            SshPublicKey.ecdsaP256(key.w.affineX, key.w.affineY)
        } else {
            SshPublicKey.ed25519FromX509(key.encoded)
        }
    }

    override fun generate(alias: String): SshPublicKey {
        delete(alias)
        val ed25519 = runCatching { generate(alias, ED25519_CURVE, KeyProperties.DIGEST_NONE) }
        if (ed25519.isFailure) {
            // Keystores without Ed25519 support: fall back to ECDSA P-256 (docs/design/integrations.md).
            delete(alias)
            generate(alias, P256_CURVE, KeyProperties.DIGEST_SHA256)
        }
        return checkNotNull(publicKey(alias)) { "Generated key not found" }
    }

    private fun generate(alias: String, curve: String, digest: String) {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec(curve))
            .setDigests(digest)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply {
            initialize(spec)
            generateKeyPair()
        }
    }

    override fun sign(alias: String, data: ByteArray): ByteArray {
        val privateKey = keyStore.getKey(alias, null) as? PrivateKey ?: error("No SSH key in the Keystore")
        val publicKey = checkNotNull(publicKey(alias))
        return when (publicKey.algorithm) {
            SshKeyAlgorithm.ED25519 -> SshSignatureEncoding.ed25519(jcaSign("Ed25519", privateKey, data))
            SshKeyAlgorithm.ECDSA_P256 -> SshSignatureEncoding.ecdsaP256(jcaSign("SHA256withECDSA", privateKey, data))
        }
    }

    private fun jcaSign(algorithm: String, key: PrivateKey, data: ByteArray): ByteArray =
        Signature.getInstance(algorithm).run {
            initSign(key)
            update(data)
            sign()
        }

    override fun delete(alias: String) {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ED25519_CURVE = "ed25519"
        const val P256_CURVE = "secp256r1"
    }
}
