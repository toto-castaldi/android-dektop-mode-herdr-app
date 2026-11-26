package io.github.totocastaldi.herdrdesktop.coreclient.keys

import java.math.BigInteger

/** Key algorithms the app generates in the Android Keystore. */
enum class SshKeyAlgorithm(val sshName: String) {
    ED25519("ssh-ed25519"),
    ECDSA_P256("ecdsa-sha2-nistp256")
    ;

    companion object {
        fun fromSshName(name: String): SshKeyAlgorithm? = entries.firstOrNull { it.sshName == name }
    }
}

/** A public key in SSH wire format, exportable as an `authorized_keys` line. */
data class SshPublicKey(val algorithm: SshKeyAlgorithm, val blob: ByteArray) {
    /** The `authorized_keys` line, for example `ssh-ed25519 AAAA… herdr-desktop`. */
    fun authorizedKeysLine(comment: String = DEFAULT_COMMENT): String =
        "${algorithm.sshName} ${SshWire.base64(blob)} $comment".trimEnd()

    val fingerprint: String get() = Fingerprint.sha256(blob)

    override fun equals(other: Any?): Boolean =
        other is SshPublicKey && other.algorithm == algorithm && other.blob.contentEquals(blob)

    override fun hashCode(): Int = 31 * algorithm.hashCode() + blob.contentHashCode()

    companion object {
        const val DEFAULT_COMMENT = "herdr-desktop-android"
        private const val ED25519_KEY_SIZE = 32
        private const val P256_COORDINATE_SIZE = 32
        private const val UNCOMPRESSED_POINT = 0x04

        /**
         * Builds the blob of an Ed25519 key from its X.509 SubjectPublicKeyInfo encoding,
         * whose last 32 bytes are the raw key.
         */
        fun ed25519FromX509(encoded: ByteArray): SshPublicKey {
            require(encoded.size >= ED25519_KEY_SIZE) { "Ed25519 public key too short" }
            val raw = encoded.copyOfRange(encoded.size - ED25519_KEY_SIZE, encoded.size)
            return SshPublicKey(
                SshKeyAlgorithm.ED25519,
                SshWire.build {
                    SshWire.string(it, SshKeyAlgorithm.ED25519.sshName)
                    SshWire.string(it, raw)
                }
            )
        }

        /** Builds the blob of an ECDSA P-256 key from the affine coordinates of its point. */
        fun ecdsaP256(x: BigInteger, y: BigInteger): SshPublicKey {
            val point = byteArrayOf(UNCOMPRESSED_POINT.toByte()) + x.unsigned() + y.unsigned()
            return SshPublicKey(
                SshKeyAlgorithm.ECDSA_P256,
                SshWire.build {
                    SshWire.string(it, SshKeyAlgorithm.ECDSA_P256.sshName)
                    SshWire.string(it, "nistp256")
                    SshWire.string(it, point)
                }
            )
        }

        private fun BigInteger.unsigned(): ByteArray {
            val bytes = toByteArray()
            return when {
                bytes.size == P256_COORDINATE_SIZE -> bytes
                bytes.size > P256_COORDINATE_SIZE -> bytes.copyOfRange(bytes.size - P256_COORDINATE_SIZE, bytes.size)
                else -> ByteArray(P256_COORDINATE_SIZE - bytes.size) + bytes
            }
        }
    }
}

/** Encodes JCA signatures as SSH signature blobs (RFC 8709 §6, RFC 5656 §3.1.2). */
object SshSignatureEncoding {
    fun ed25519(rawSignature: ByteArray): ByteArray = SshWire.build {
        SshWire.string(it, SshKeyAlgorithm.ED25519.sshName)
        SshWire.string(it, rawSignature)
    }

    /** Converts a DER `SEQUENCE { INTEGER r, INTEGER s }` signature into the SSH blob. */
    fun ecdsaP256(derSignature: ByteArray): ByteArray {
        val (r, s) = Der.ecdsaSignature(derSignature)
        val inner = SshWire.build {
            SshWire.mpint(it, r)
            SshWire.mpint(it, s)
        }
        return SshWire.build {
            SshWire.string(it, SshKeyAlgorithm.ECDSA_P256.sshName)
            SshWire.string(it, inner)
        }
    }
}

internal object Der {
    private const val SEQUENCE = 0x30
    private const val INTEGER = 0x02
    private const val LONG_FORM = 0x80
    private const val LENGTH_MASK = 0x7f

    fun ecdsaSignature(der: ByteArray): Pair<BigInteger, BigInteger> {
        var pos = 0
        fun next(): Int {
            require(pos < der.size) { "Truncated DER signature" }
            return der[pos++].toUByte().toInt()
        }
        fun length(): Int {
            val first = next()
            if (first and LONG_FORM == 0) return first
            return (0 until (first and LENGTH_MASK)).fold(0) { acc, _ -> acc shl Byte.SIZE_BITS or next() }
        }
        fun integer(): BigInteger {
            require(next() == INTEGER) { "Expected DER INTEGER" }
            val len = length()
            require(pos + len <= der.size) { "Truncated DER INTEGER" }
            return BigInteger(der.copyOfRange(pos, pos + len)).also { pos += len }
        }
        require(next() == SEQUENCE) { "Expected DER SEQUENCE" }
        length()
        return integer() to integer()
    }
}
