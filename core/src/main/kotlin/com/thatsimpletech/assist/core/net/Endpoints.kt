package com.thatsimpletech.assist.core.net

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Thrown before any socket opens. The message names the host so the audit row can too. */
class HostNotNamedException(val host: String) : IOException("refusing connection to '$host': host not named in config")

/** Thrown before any socket opens: plain http/ws is only for loopback and the tailnet. */
class CleartextRefusedException(val host: String) : IOException("refusing cleartext to '$host': only loopback and the tailnet may be reached without TLS")

/**
 * The single place an outbound connection is made (plan §1, "No telemetry"). Every client
 * comes from here, every client refuses a host the config did not name, refuses cleartext
 * outside loopback and the tailnet, never follows a redirect (a redirect is a new host the
 * config did not name), pins MagicDNS names to tailnet addresses, and records every host that
 * did go out on a list the tests read. `NoConnectionOutsideEndpointsTest` keeps the rest of
 * core and the app from growing a second door.
 */
class Endpoints(
    allowedHosts: Set<String>,
    private val timeout: Long = 60,
    private val unit: TimeUnit = TimeUnit.SECONDS,
    private val dns: okhttp3.Dns = TailnetDns(),
) {
    private val allowed: Set<String> = allowedHosts.map { it.trim().lowercase() }.toSet()
    private val seen = CopyOnWriteArrayList<String>()
    private val blocked = CopyOnWriteArrayList<String>()

    /** Hosts a connection was actually attempted to, in order, with repeats. */
    fun destinations(): List<String> = seen.toList()

    /** Hosts something asked for and was refused, in order. */
    fun refused(): List<String> = blocked.toList()

    fun isAllowed(host: String): Boolean = host.lowercase() in allowed

    /** A client for plain requests. The gate runs as an application and as a network interceptor. */
    fun httpClient(): OkHttpClient = base()
        .readTimeout(timeout, unit)
        .writeTimeout(timeout, unit)
        .build()

    /**
     * A client for one WebSocket URL. The host is judged here, not only when the upgrade
     * request runs, so a refused URL fails at construction; the interceptors still guard the
     * upgrade itself.
     */
    fun webSocketClient(url: String): OkHttpClient {
        // HttpUrl does not speak ws://; the host is the same either way.
        val http = when {
            url.startsWith("wss://", ignoreCase = true) -> "https://" + url.substring(6)
            url.startsWith("ws://", ignoreCase = true) -> "http://" + url.substring(5)
            else -> throw IllegalArgumentException("not a WebSocket URL")
        }
        val parsed = http.toHttpUrlOrNull() ?: throw IllegalArgumentException("not a WebSocket URL")
        admit(parsed)
        return base()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    private fun base(): OkHttpClient.Builder = OkHttpClient.Builder()
        .connectTimeout(timeout, unit)
        .followRedirects(false)
        .followSslRedirects(false)
        .dns(dns)
        .addInterceptor(gate)
        .addNetworkInterceptor(networkGate)

    /** Records the host when it is allowed, throws before the chain proceeds when it is not. */
    private fun admit(url: HttpUrl, record: Boolean = true) {
        val host = url.host.lowercase()
        if (host !in allowed) {
            blocked.add(host)
            throw HostNotNamedException(host)
        }
        if (!url.isHttps && !CleartextPolicy.allowed(host)) {
            blocked.add(host)
            throw CleartextRefusedException(host)
        }
        if (record) seen.add(host)
    }

    /** Application level: judged and recorded once per call. */
    private val gate = Interceptor { chain: Interceptor.Chain ->
        admit(chain.request().url)
        chain.proceed(chain.request())
    }

    /** Network level: judged again for every hop OkHttp makes (retries, follow-ups), never recorded twice. */
    private val networkGate = Interceptor { chain: Interceptor.Chain ->
        admit(chain.request().url, record = false)
        chain.proceed(chain.request())
    }
}
