package io.github.totocastaldi.herdrdesktop.coreclient.keys

/**
 * SSH keys whose private part never leaves the store (the Android Keystore in the app).
 * Signing happens inside the store; callers only see public keys and signatures.
 */
interface SshKeyStore {
    /** The public key stored under [alias], or null if there is none. */
    fun publicKey(alias: String): SshPublicKey?

    /** Generates a new key under [alias], replacing any existing one. */
    fun generate(alias: String): SshPublicKey

    /** Signs [data] with the key under [alias] and returns the SSH signature blob. */
    fun sign(alias: String, data: ByteArray): ByteArray

    fun delete(alias: String)

    companion object {
        /** The key shared by all machines that use key authentication. */
        const val DEFAULT_ALIAS = "herdr-ssh-default"
    }
}

/** Encrypts small secrets, such as SSH passwords, with a key that never leaves the store. */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray

    fun decrypt(sealed: ByteArray): ByteArray
}
