package io.github.totocastaldi.herdrdesktop.coreclient.connection

import java.net.InetAddress
import java.net.UnknownHostException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkTest {
    /** Literals only: no test reaches DNS. */
    private val noDns = LocalNetwork.Resolver { host ->
        if (host.any { it.isLetter() } && !host.contains(':')) throw UnknownHostException(host)
        listOf(InetAddress.getByName(host))
    }

    private fun local(host: String) = LocalNetwork.isLocalHost(host, noDns)

    @Test
    fun `private and link-local addresses are on the local network`() {
        listOf(
            "10.0.0.1", "172.16.0.1", "172.27.3.4", "172.31.255.254", "192.168.1.10", "169.254.10.20",
            "fe80::1", "[fe80::1]", "fd12:3456::1", "fc00::1", "::ffff:192.168.1.1"
        ).forEach { assertTrue(it, local(it)) }
    }

    @Test
    fun `loopback, Tailscale and public addresses are not on the local network`() {
        listOf(
            "127.0.0.1", "::1", "100.89.146.97", "100.64.0.1", "100.127.255.254", "172.15.0.1", "172.32.0.1",
            "8.8.8.8", "192.0.2.1", "2001:db8::1"
        ).forEach { assertFalse(it, local(it)) }
    }

    @Test
    fun `mDNS names are local without resolving them`() {
        assertTrue(local("raspberry.local"))
        assertTrue(local("NAS.LOCAL."))
    }

    @Test
    fun `names are classified by their resolved addresses`() {
        val dns = LocalNetwork.Resolver { host ->
            when (host) {
                "nas.home.arpa" -> listOf(InetAddress.getByName("192.168.1.5"))
                "vps.example.org" -> listOf(InetAddress.getByName("203.0.113.7"))
                "box.tailnet.ts.net" -> listOf(InetAddress.getByName("100.101.102.103"))
                else -> throw UnknownHostException(host)
            }
        }
        assertTrue(LocalNetwork.isLocalHost("nas.home.arpa", dns))
        assertFalse(LocalNetwork.isLocalHost("vps.example.org", dns))
        assertFalse(LocalNetwork.isLocalHost("box.tailnet.ts.net", dns))
        assertFalse(LocalNetwork.isLocalHost("unknown.example", dns))
    }
}
