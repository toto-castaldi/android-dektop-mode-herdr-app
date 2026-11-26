package io.github.totocastaldi.herdrdesktop.coreclient.ssh

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRefusedTest {
    @Test
    fun `refused connections are recognized from the cause or from the sshlib message`() {
        // Message seen on the Pixel 11 Pro with the Linux Terminal closed (sshlib 0.6.9).
        assertTrue(
            isConnectionRefused(
                IOException("Failed to connect to 127.0.0.1:2222. Tried 1 addresses. Errors: Connection refused")
            )
        )
        assertTrue(isConnectionRefused(IOException("wrapped", ConnectException("refused"))))
    }

    @Test
    fun `timeouts and other errors are not refusals`() {
        assertFalse(isConnectionRefused(SocketTimeoutException("connect timed out")))
        assertFalse(isConnectionRefused(IOException("Failed to connect. Errors: Network is unreachable")))
    }
}
