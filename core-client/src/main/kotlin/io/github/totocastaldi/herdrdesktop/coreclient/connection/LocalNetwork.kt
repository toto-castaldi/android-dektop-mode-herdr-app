package io.github.totocastaldi.herdrdesktop.coreclient.connection

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Android's local network permission (`ACCESS_LOCAL_NETWORK`), as seen by [ConnectionManager]. Without
 * it, a TCP connection to a local network address hangs silently, so the manager checks it first.
 */
interface LocalNetworkAccess {
    /** True when connecting to [host] needs the local network permission; may resolve [host]. */
    suspend fun needsPermission(host: String): Boolean

    /** True when the permission is granted. */
    fun granted(): Boolean

    /** Access where no host needs the permission (tests, platforms without it). */
    object Unrestricted : LocalNetworkAccess {
        override suspend fun needsPermission(host: String) = false

        override fun granted() = true
    }
}

/**
 * Tells local network hosts from the others. Local: private IPv4 (10/8, 172.16/12, 192.168/16),
 * link-local (169.254/16, fe80::/10), IPv6 unique local (fc00::/7) and mDNS names (`.local`).
 * Not local: loopback (the Linux Terminal on 127.0.0.1), carrier-grade NAT 100.64/10 (Tailscale)
 * and public addresses.
 */
object LocalNetwork {
    /** Resolves a host to its addresses; literal addresses are parsed without DNS. */
    fun interface Resolver {
        fun resolve(host: String): List<InetAddress>
    }

    val systemResolver = Resolver { host -> InetAddress.getAllByName(host).toList() }

    /**
     * True when [host] is on the local network: an mDNS name, or a name or literal whose addresses
     * include a local one. An unresolvable host is not local: the connection then fails as usual.
     */
    fun isLocalHost(host: String, resolver: Resolver = systemResolver): Boolean {
        val name = host.trim().removePrefix("[").removeSuffix("]").removeSuffix(".")
        if (name.endsWith(MDNS_SUFFIX, ignoreCase = true)) return true
        val addresses = try {
            resolver.resolve(name)
        } catch (_: UnknownHostException) {
            emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
        return addresses.any(::isLocalAddress)
    }

    fun isLocalAddress(address: InetAddress): Boolean = when (address) {
        is Inet4Address -> address.isSiteLocalAddress || address.isLinkLocalAddress

        // IPv4-mapped literals (::ffff:a.b.c.d) are already parsed as Inet4Address.
        is Inet6Address -> address.isLinkLocalAddress || address.isUniqueLocal()

        else -> false
    }

    private fun Inet6Address.isUniqueLocal() = (address[0].toInt() and ULA_MASK) == ULA_PREFIX

    private const val MDNS_SUFFIX = ".local"
    private const val ULA_MASK = 0xfe
    private const val ULA_PREFIX = 0xfc
}
