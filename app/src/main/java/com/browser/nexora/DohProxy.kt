package com.browser.nexora

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A tiny HTTP proxy on 127.0.0.1 that exists for one reason: to look hostnames up over
 * DNS-over-HTTPS. WebView has no setting for that, but it can be pointed at a proxy
 * (ProxyController.setProxyOverride), and a proxy is where the hostname gets resolved.
 *
 *   HTTPS: WebView sends "CONNECT host:443". We resolve host over DoH, connect to the IP and
 *          copy bytes both ways. The TLS session stays between the page and the site, so this
 *          proxy never sees page content.
 *   HTTP:  WebView sends "GET http://host/path". We resolve host over DoH, forward the request
 *          in normal form and copy bytes both ways.
 *
 * fallback = true  -> if the DoH lookup fails, use the system resolver (like Chrome's automatic mode)
 * fallback = false -> if the DoH lookup fails, the connection fails (like Chrome's "With: provider")
 */
class DohProxy {

    private class Entry(val addrs: List<InetAddress>, val expires: Long)

    @Volatile private var doh: DnsOverHttps? = null
    @Volatile private var fallback = true
    @Volatile private var server: ServerSocket? = null
    private val cache = ConcurrentHashMap<String, Entry>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "nexora-doh").apply { isDaemon = true } }

    /** Total DoH lookups made (cache hits are not counted). Lets the UI show that it is really working. */
    @Volatile var lookups = 0L
        private set

    /** Called from a background thread after each DoH lookup. */
    @Volatile var onLookup: (() -> Unit)? = null

    /** Starts listening (once) and returns the port. */
    @Synchronized
    @Throws(IOException::class)
    fun start(): Int {
        server?.let { if (!it.isClosed) return it.localPort }
        val s = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        server = s
        pool.execute {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: IOException) { break }
                pool.execute { handle(c) }
            }
        }
        return s.localPort
    }

    @Synchronized
    fun stop() {
        try { server?.close() } catch (e: IOException) {}
        server = null
        cache.clear()
    }

    /** Sets the provider. [url] must be an https:// DoH address. */
    fun configure(url: String, fallback: Boolean) {
        this.fallback = fallback
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
        val u = url.toHttpUrl()
        val b = DnsOverHttps.Builder()
            .client(client)
            .url(u)
            .includeIPv6(true)
            .resolvePrivateAddresses(true)
        // Providers whose own address is well known are reached without a plain-DNS lookup of
        // their name. Others need the system resolver once to find the provider itself.
        BOOTSTRAP[u.host]?.let { ips -> b.bootstrapDnsHosts(ips.map { InetAddress.getByName(it) }) }
        doh = b.build()
        cache.clear()
    }

    // ------------------------------------------------------------------ name lookup

    private fun resolve(host: String): List<InetAddress> {
        // IP addresses and names that only exist on a home or office network never go to a public resolver
        if (isLiteral(host) || isLocalName(host)) return InetAddress.getAllByName(host).toList()

        val now = System.currentTimeMillis()
        cache[host]?.let { if (it.expires > now) return it.addrs }

        val d = doh
        if (d != null) {
            try {
                lookups++
                onLookup?.invoke()
                val a = d.lookup(host)
                if (a.isNotEmpty()) {
                    if (cache.size > 512) cache.clear()
                    cache[host] = Entry(a, now + 60_000)
                    return a
                }
                if (!fallback) throw java.net.UnknownHostException(host)
            } catch (e: java.net.UnknownHostException) {
                if (!fallback) throw e
            } catch (e: Exception) {
                if (!fallback) throw java.net.UnknownHostException("$host: ${e.message}")
            }
        }
        return InetAddress.getAllByName(host).toList()
    }

    private fun isLiteral(h: String) = h.contains(':') || IPV4.matches(h)

    private fun isLocalName(h: String): Boolean {
        val l = h.lowercase()
        return !l.contains('.') || l.endsWith(".local") || l.endsWith(".lan") ||
            l.endsWith(".home.arpa") || l.endsWith(".internal") || l.endsWith(".localhost")
    }

    private fun connect(host: String, port: Int): Socket {
        var last: IOException? = null
        for (a in resolve(host)) {
            val s = Socket()
            try {
                s.connect(InetSocketAddress(a, port), 12_000)
                s.tcpNoDelay = true
                return s
            } catch (e: IOException) {
                last = e
                try { s.close() } catch (x: IOException) {}
            }
        }
        throw last ?: java.net.UnknownHostException(host)
    }

    // ------------------------------------------------------------------ connections from WebView

    private fun handle(c: Socket) {
        var piping = false
        try {
            c.tcpNoDelay = true
            val inp = BufferedInputStream(c.getInputStream(), 16 * 1024)
            val head = readHead(inp) ?: return
            val lines = head.split("\r\n").filter { it.isNotEmpty() }
            val first = lines.firstOrNull()?.split(" ") ?: return
            if (first.size < 3) {
                reply(c, "400 Bad Request")
                return
            }
            val method = first[0]
            val target = first[1]
            val version = first[2]

            if (method.equals("CONNECT", ignoreCase = true)) {
                val (host, port) = splitHostPort(target, 443)
                val remote = try {
                    connect(host, port)
                } catch (e: Exception) {
                    reply(c, "502 Bad Gateway")
                    return
                }
                c.getOutputStream().apply {
                    write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    flush()
                }
                piping = true
                pipe(c, inp, remote)
                return
            }

            // plain http: the request line carries the whole address
            val uri = try { URI(target) } catch (e: Exception) { null }
            val host = uri?.host
            if (uri == null || host == null || !uri.scheme.equals("http", ignoreCase = true)) {
                reply(c, "400 Bad Request")
                return
            }
            val port = if (uri.port > 0) uri.port else 80
            val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
            val remote = try {
                connect(host.trim('[', ']'), port)
            } catch (e: Exception) {
                reply(c, "502 Bad Gateway")
                return
            }

            val headers = lines.drop(1)
            val upgrade = headers.any { it.startsWith("Upgrade:", ignoreCase = true) }
            val out = StringBuilder("$method $path $version\r\n")
            for (h in headers) {
                val name = h.substringBefore(':').trim().lowercase()
                if (name == "proxy-connection" || name == "proxy-authorization" || name == "keep-alive") continue
                if (name == "connection" && !upgrade) continue
                out.append(h).append("\r\n")
            }
            // one request per connection keeps the byte-copying correct without parsing bodies
            if (!upgrade) out.append("Connection: close\r\n")
            out.append("\r\n")
            remote.getOutputStream().apply {
                write(out.toString().toByteArray(Charsets.ISO_8859_1))
                flush()
            }
            piping = true
            pipe(c, inp, remote)
        } catch (e: Exception) {
            // a dropped connection is normal for a browser
        } finally {
            if (!piping) closeQuietly(c)
        }
    }

    /**
     * Copies both ways. The remote->client direction owns the clean-up: when the site closes, both
     * sockets close. If the client side ends first we only half-close the remote and let the
     * answer finish.
     */
    private fun pipe(c: Socket, fromClient: InputStream, remote: Socket) {
        pool.execute {
            try {
                copy(remote.getInputStream(), c.getOutputStream())
            } catch (e: Exception) {
            } finally {
                closeQuietly(c)
                closeQuietly(remote)
            }
        }
        val clean = try {
            copy(fromClient, remote.getOutputStream())
            true
        } catch (e: Exception) {
            false
        }
        if (clean) {
            try { remote.shutdownOutput() } catch (e: Exception) {}
        } else {
            closeQuietly(c)
            closeQuietly(remote)
        }
    }

    private fun copy(i: InputStream, o: java.io.OutputStream) {
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = i.read(buf)
            if (n < 0) return
            o.write(buf, 0, n)
            o.flush()
        }
    }

    private fun readHead(i: InputStream): String? {
        val b = ByteArrayOutputStream()
        var m = 0   // how much of "\r\n\r\n" has been matched
        while (b.size() < 64 * 1024) {
            val x = i.read()
            if (x < 0) return null
            b.write(x)
            m = when {
                x == '\r'.code && (m == 0 || m == 2) -> m + 1
                x == '\n'.code && (m == 1 || m == 3) -> m + 1
                x == '\r'.code -> 1
                else -> 0
            }
            if (m == 4) return String(b.toByteArray(), Charsets.ISO_8859_1)
        }
        return null
    }

    private fun splitHostPort(s: String, def: Int): Pair<String, Int> {
        if (s.startsWith("[")) {   // [ipv6]:port
            val end = s.indexOf(']')
            val host = s.substring(1, end)
            val port = s.substring(end + 1).removePrefix(":").toIntOrNull() ?: def
            return host to port
        }
        val i = s.lastIndexOf(':')
        return if (i > 0) s.substring(0, i) to (s.substring(i + 1).toIntOrNull() ?: def) else s to def
    }

    private fun reply(c: Socket, status: String) {
        try {
            c.getOutputStream().apply {
                write("HTTP/1.1 $status\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                flush()
            }
        } catch (e: Exception) {
        }
    }

    private fun closeQuietly(s: Socket) {
        try { s.close() } catch (e: IOException) {}
    }

    private companion object {
        val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

        // Only addresses that are certain; other providers fall back to a one-time system lookup of their name
        val BOOTSTRAP = mapOf(
            "dns.google" to listOf("8.8.8.8", "8.8.4.4"),
            "chrome.cloudflare-dns.com" to listOf("162.159.61.4", "172.64.41.4"),
            "dns.quad9.net" to listOf("9.9.9.9", "149.112.112.112")
        )
    }
}
