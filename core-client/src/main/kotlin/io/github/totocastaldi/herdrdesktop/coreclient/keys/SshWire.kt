package io.github.totocastaldi.herdrdesktop.coreclient.keys

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.util.Base64

/** Encoding helpers for the SSH wire format (RFC 4251 §5). */
internal object SshWire {
    fun string(out: ByteArrayOutputStream, value: ByteArray) {
        uint32(out, value.size)
        out.write(value)
    }

    fun string(out: ByteArrayOutputStream, value: String) = string(out, value.toByteArray(Charsets.US_ASCII))

    fun mpint(out: ByteArrayOutputStream, value: BigInteger) = string(out, value.toByteArray())

    private fun uint32(out: ByteArrayOutputStream, value: Int) {
        out.write(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value).array())
    }

    fun build(block: (ByteArrayOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().also(block).toByteArray()

    /** Reads the leading SSH string of [blob] as ASCII, or null if the blob is malformed. */
    fun leadingString(blob: ByteArray): String? {
        if (blob.size < Int.SIZE_BYTES) return null
        val length = ByteBuffer.wrap(blob).int
        val valid = length >= 0 && length <= blob.size - Int.SIZE_BYTES
        return if (valid) String(blob, Int.SIZE_BYTES, length, Charsets.US_ASCII) else null
    }

    fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
