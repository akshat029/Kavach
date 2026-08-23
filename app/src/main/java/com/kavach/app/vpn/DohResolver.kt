package com.kavach.app.vpn

import android.net.VpnService
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/** An upstream DNS-over-HTTPS resolver the user can pick in Settings. */
data class DohEndpoint(
    val id: String,
    val label: String,
    val description: String,
    val url: String,
    /**
     * Hard-coded addresses for the endpoint host.
     *
     * Without these the resolver would have to resolve its own hostname through the
     * system resolver, which - inside a VPN app that *is* the system resolver - is a
     * deadlock. Bootstrapping from literals removes the cycle entirely.
     */
    val bootstrap: List<String>,
)

object DohEndpoints {

    val CLOUDFLARE = DohEndpoint(
        id = "cloudflare",
        label = "Cloudflare",
        description = "Fast, and does not log personally identifiable data.",
        url = "https://cloudflare-dns.com/dns-query",
        bootstrap = listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001"),
    )

    val QUAD9 = DohEndpoint(
        id = "quad9",
        label = "Quad9",
        description = "Also refuses known malware and phishing domains.",
        url = "https://dns.quad9.net/dns-query",
        bootstrap = listOf("9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9"),
    )

    val MULLVAD = DohEndpoint(
        id = "mullvad",
        label = "Mullvad",
        description = "Run by a privacy company. No accounts, no logs.",
        url = "https://dns.mullvad.net/dns-query",
        bootstrap = listOf("194.242.2.2", "2a07:e340::2"),
    )

    val ADGUARD = DohEndpoint(
        id = "adguard",
        label = "AdGuard DNS",
        description = "Adds a second layer of ad filtering upstream.",
        url = "https://dns.adguard-dns.com/dns-query",
        bootstrap = listOf("94.140.14.14", "94.140.15.15", "2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff"),
    )

    val ALL = listOf(CLOUDFLARE, QUAD9, MULLVAD, ADGUARD)

    fun byId(id: String?): DohEndpoint = ALL.firstOrNull { it.id == id } ?: CLOUDFLARE
}

/**
 * Forwards allowed questions upstream over DNS-over-HTTPS (RFC 8484).
 *
 * Two details matter more than they look:
 *
 * 1. **Every socket is protected.** VpnService.protect keeps the resolver's own
 *    traffic outside the tunnel. Without it the resolver would try to reach the
 *    internet through the interface it is servicing, and hang forever.
 * 2. **Plaintext DNS is never used as a fallback.** If the encrypted resolver is
 *    unreachable Kavach returns SERVFAIL. Silently downgrading to port 53 would hand
 *    the user's entire browsing history to their carrier at exactly the moment they
 *    believed they were protected.
 */
class DohResolver(
    vpnService: VpnService?,
    val endpoint: DohEndpoint,
) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .socketFactory(ProtectedSocketFactory(vpnService))
        .dns(BootstrapDns(endpoint.bootstrap))
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Returns the raw upstream reply, or null if the resolver could not be reached. */
    fun resolve(query: ByteArray): ByteArray? = try {
        val request = Request.Builder()
            .url(endpoint.url)
            .post(query.toRequestBody(DNS_MESSAGE))
            .header("Accept", CONTENT_TYPE)
            .header("User-Agent", "Kavach")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                null
            } else {
                response.body?.bytes()?.takeIf { it.size >= DnsMessage.HEADER_LEN }
            }
        }
    } catch (_: Throwable) {
        // Timeouts, TLS failures, airplane mode. The caller turns null into SERVFAIL.
        null
    }

    fun shutdown() {
        try {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        } catch (_: Throwable) {
            // Nothing useful to do while tearing down.
        }
    }

    private companion object {
        const val CONTENT_TYPE = "application/dns-message"
        val DNS_MESSAGE = CONTENT_TYPE.toMediaType()
    }
}

/** Resolves the DoH hostname, and only to its pinned literals. */
private class BootstrapDns(addresses: List<String>) : Dns {

    private val resolved: List<InetAddress> = addresses.mapNotNull { literal ->
        try {
            // Passing a literal never touches the network, it only parses.
            InetAddress.getByName(literal)
        } catch (_: Throwable) {
            null
        }
    }

    override fun lookup(hostname: String): List<InetAddress> =
        if (resolved.isNotEmpty()) resolved else Dns.SYSTEM.lookup(hostname)
}

/** Hands OkHttp sockets that the VPN has been told to leave alone. */
private class ProtectedSocketFactory(private val vpnService: VpnService?) : SocketFactory() {

    private fun protect(socket: Socket): Socket {
        vpnService?.protect(socket)
        return socket
    }

    // OkHttp uses the no-argument form and connects the socket itself, so this is the
    // overload that actually matters in practice.
    override fun createSocket(): Socket = protect(Socket())

    override fun createSocket(host: String, port: Int): Socket =
        protect(Socket()).also { it.connect(InetSocketAddress(host, port)) }

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int,
    ): Socket = protect(Socket()).also {
        it.bind(InetSocketAddress(localHost, localPort))
        it.connect(InetSocketAddress(host, port))
    }

    override fun createSocket(host: InetAddress, port: Int): Socket =
        protect(Socket()).also { it.connect(InetSocketAddress(host, port)) }

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket = protect(Socket()).also {
        it.bind(InetSocketAddress(localAddress, localPort))
        it.connect(InetSocketAddress(address, port))
    }
}
