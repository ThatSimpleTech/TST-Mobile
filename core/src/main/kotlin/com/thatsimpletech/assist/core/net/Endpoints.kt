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

/**
 * The single place an outbound connection is made (plan §1, "No telemetry"). Every client
 * comes from here, every client refuses a host the config did not name, and every host that
 * did go out is on a list the tests read. `NoConnectionOutsideEndpointsTest` keeps the rest
 * of core from growing a second door.
 */
class Endpoints(
    allowedHosts: Set<String>,
    private val timeout: Long = 20,
    private val unit: TimeUnit = TimeUnit.SECONDS,
    private val readTimeout: Long = 180,
) {
    private val allowed: Set<String> = allowedHosts.map { it.trim().lowercase() }.toSet()
    private val seen = CopyOnWriteArrayList<String>()
    private val blocked = CopyOnWriteArrayList<String>()

    /** Hosts a connection was actually attempted to, in order, with repeats. */
    fun destinations(): List<String> = seen.toList()

    /** Hosts something asked for and was refused, in order. */
    fun refused(): List<String> = blocked.toList()

    fun isAllowed(host: String): Boolean = host.lowercase() in allowed

    /** A client for plain requests. Redirects are not followed: a 302 to a stranger must not open a socket. */
    fun httpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeout, unit)
        .readTimeout(readTimeout, unit)
        .writeTimeout(timeout, unit)
        .callTimeout(readTimeout + timeout, unit)
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(gate)
        .build()

    /**
     * A client for one WebSocket URL. The host is judged here, not only when the upgrade
     * request runs, so a refused URL fails at construction; the interceptor still guards the
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
        return OkHttpClient.Builder()
            .connectTimeout(timeout, unit)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(gate)
            .build()
    }

    /** Records the host when it is allowed, throws before the chain proceeds when it is not. */
    private fun admit(url: HttpUrl) {
        val host = url.host.lowercase()
        if (host !in allowed) {
            blocked.add(host)
            throw HostNotNamedException(host)
        }
        seen.add(host)
    }

    private val gate = Interceptor { chain: Interceptor.Chain ->
        admit(chain.request().url)
        chain.proceed(chain.request())
    }
}
