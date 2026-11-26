package io.github.totocastaldi.herdrdesktop.coreclient.install

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandOutputTest {
    @Test
    fun `chunks of two streams are joined in arrival order`() {
        val output = CommandOutput()
        output.add(1, "installing\n".toByteArray())
        output.add(2, "warning\n".toByteArray())
        assertEquals("installing\nwarning\ndone\n", output.add(1, "done\n".toByteArray()))
    }

    @Test
    fun `a character split between chunks stays whole`() {
        val bytes = "→ ok".toByteArray()
        val output = CommandOutput()
        assertEquals("", output.add(1, bytes.copyOfRange(0, 1)))
        assertEquals("→ ok", output.add(1, bytes.copyOfRange(1, bytes.size)))
    }

    @Test
    fun `only the last characters are kept`() {
        val output = CommandOutput(maxChars = 4)
        output.add(1, "abcdef".toByteArray())
        assertEquals("cdef", output.toString())
    }
}
