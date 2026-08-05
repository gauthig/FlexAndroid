/*
 * FlexAndroid - remote control of a FlexRadio SDR over SmartLink.
 * Copyright (C) 2026 Garrett Gauthier
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.gauthig.flexandroid.net

import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A radio the SmartLink server says this account can reach over the internet. */
data class WanRadio(
    val serial: String,
    val nickname: String,
    val model: String,
    val callsign: String,
    val status: String,
    val publicIp: String,
    val publicTlsPort: Int,
    val publicUdpPort: Int,
    val upnpSupported: Boolean,
    val requiresHolePunch: Boolean,
    val inUseBy: String,
) {
    val isAvailable: Boolean get() = publicTlsPort > 0 || requiresHolePunch

    val displayName: String get() = nickname.ifBlank { "$model $serial" }
}

/** Tokens returned by Auth0. Only the refresh token is worth persisting. */
data class SmartLinkTokens(val idToken: String, val refreshToken: String?)

/**
 * The SmartLink side of a remote connection.
 *
 * Reaching a radio over the internet is a three-party dance:
 *
 * 1. Authenticate to FlexRadio's Auth0 tenant and obtain a JWT id token.
 * 2. Hold a TLS session open to `smartlink.flexradio.com`, registering this
 *    client with that token. The server answers with the account's radio list
 *    and this device's public IP.
 * 3. Ask the server to broker a specific radio. It nudges the radio, which opens
 *    its ports, and the server hands back a one-shot `wan handle` that
 *    [RadioConnection.connectWan] presents to the radio itself.
 *
 * The server connection stays up for the whole session: it is also how radio
 * availability changes reach us.
 */
class SmartLinkClient(
    private val scope: CoroutineScope,
    private val socketFactory: SSLSocketFactory =
        SSLSocketFactory.getDefault() as SSLSocketFactory,
) {
    private var socket: SSLSocket? = null
    private var output: OutputStream? = null
    private var readerJob: Job? = null
    private var pingJob: Job? = null
    private val writeLock = Any()

    private val _radios = MutableStateFlow<List<WanRadio>>(emptyList())
    val radios: StateFlow<List<WanRadio>> = _radios

    private val _publicIp = MutableStateFlow<String?>(null)
    val publicIp: StateFlow<String?> = _publicIp

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val _connectReady = MutableSharedFlow<ConnectReady>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Emitted when the server has brokered a radio and handed back its handle. */
    val connectReady: SharedFlow<ConnectReady> = _connectReady

    private val _errors = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val errors: SharedFlow<String> = _errors

    /** The `wan handle` that authorises one TLS session to one radio. */
    data class ConnectReady(val wanHandle: String, val serial: String)

    // Touched from the UI dispatcher (sign-in) and the reader coroutine
    // (registration_invalid), so it needs a happens-before edge between them.
    @Volatile
    private var idToken: String? = null

    // ── Auth0 ────────────────────────────────────────────────────────────────

    /**
     * Exchanges a FlexRadio community email and password for tokens.
     *
     * This uses Auth0's resource-owner password grant, which is what the
     * SmartLink tenant accepts and therefore means the password passes through
     * this process. It is never persisted: only the returned refresh token is,
     * and only under the device keystore. See docs/ARCHITECTURE.md for why the
     * grant type is not our choice to make.
     */
    @Throws(IOException::class)
    suspend fun login(email: String, password: String): SmartLinkTokens =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("grant_type", "http://auth0.com/oauth/grant-type/password-realm")
                put("realm", "Username-Password-Authentication")
                put("username", email)
                put("password", password)
                put("client_id", AUTH0_CLIENT_ID)
                put("scope", AUTH0_SCOPE)
            }
            requestTokens(body).also { idToken = it.idToken }
        }

    /** Renews an id token from a stored refresh token, for silent sign-in. */
    @Throws(IOException::class)
    suspend fun loginWithRefreshToken(refreshToken: String): SmartLinkTokens =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("grant_type", "refresh_token")
                put("refresh_token", refreshToken)
                put("client_id", AUTH0_CLIENT_ID)
            }
            // Auth0 may or may not rotate the refresh token; keep the old one if
            // the response omits a replacement.
            val tokens = requestTokens(body)
            val effective = tokens.copy(refreshToken = tokens.refreshToken ?: refreshToken)
            idToken = effective.idToken
            effective
        }

    private fun requestTokens(body: JSONObject): SmartLinkTokens {
        val conn = URL("https://$AUTH0_DOMAIN/oauth/token").openConnection()
                as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = HTTP_TIMEOUT_MS
            conn.readTimeout = HTTP_TIMEOUT_MS
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (code !in 200..299) {
                val description = runCatching {
                    JSONObject(text).optString("error_description")
                }.getOrNull()
                throw IOException(description?.takeIf { it.isNotBlank() } ?: "sign-in failed ($code)")
            }

            val json = JSONObject(text)
            val id = json.optString("id_token").orEmpty()
            if (id.isBlank()) throw IOException("sign-in response contained no id_token")
            return SmartLinkTokens(
                idToken = id,
                refreshToken = json.optString("refresh_token").orEmpty().takeIf { it.isNotBlank() },
            )
        } finally {
            conn.disconnect()
        }
    }

    // ── SmartLink server session ─────────────────────────────────────────────

    /** Opens the TLS session to the SmartLink server and registers this client. */
    @Throws(IOException::class)
    suspend fun connect() {
        val token = idToken ?: throw IOException("sign in before connecting to SmartLink")
        withContext(Dispatchers.IO) {
            val s = socketFactory.createSocket(SMARTLINK_HOST, SMARTLINK_PORT) as SSLSocket
            s.tcpNoDelay = true
            // The SmartLink server has a real CA-issued certificate, so the
            // platform's default validation is exactly what we want here.
            s.startHandshake()
            socket = s
            output = s.getOutputStream()
            _connected.value = true

            readerJob = scope.launch(Dispatchers.IO) { readLoop(s) }
            write("application register name=$CLIENT_NAME platform=Android token=$token")
            pingJob = scope.launch {
                while (isActive) {
                    delay(PING_INTERVAL_MS)
                    runCatching { write("ping from client") }
                }
            }
        }
    }

    private suspend fun readLoop(s: SSLSocket) {
        try {
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            while (true) {
                val line = reader.readLine() ?: break
                handleMessage(line.trim())
            }
        } catch (e: IOException) {
            Log.d(TAG, "SmartLink read loop ended: ${e.message}")
        } finally {
            _connected.value = false
        }
    }

    private suspend fun handleMessage(msg: String) {
        when {
            msg.startsWith("radio list ") -> _radios.value = parseRadioList(msg)

            msg.startsWith("radio connect_ready") -> {
                val kv = FlexMessageParser.parseFields(msg)
                val handle = kv["handle"]
                val serial = kv["serial"]
                if (!handle.isNullOrBlank() && !serial.isNullOrBlank()) {
                    _connectReady.emit(ConnectReady(handle, serial))
                }
            }

            msg.startsWith("application info") ->
                _publicIp.value = FlexMessageParser.parseFields(msg)["public_ip"]

            msg.startsWith("application registration_invalid") -> {
                idToken = null
                _errors.emit("SmartLink rejected the sign-in. Please sign in again.")
                disconnect()
            }

            // user_settings carries the account holder's name and callsign. It is
            // not needed to operate the radio, so it is not stored or logged.
            msg.startsWith("application user_settings") -> Unit

            msg.startsWith("ping") -> Unit

            else -> Log.d(TAG, "unhandled SmartLink message: ${msg.take(60)}")
        }
    }

    /**
     * Asks the server to prepare [serial] for a direct connection.
     *
     * [holePunchPort] is the local UDP port already bound for VITA-49 reception.
     * For radios behind a NAT with no forwarded ports, the server passes it to
     * the radio so both sides start sending at once and the NAT mapping opens
     * from the inside.
     */
    @Throws(IOException::class)
    fun requestConnect(serial: String, holePunchPort: Int) {
        write("application connect serial=$serial hole_punch_port=$holePunchPort")
    }

    /** Force-disconnects another GUI client so this one can take its slot. */
    @Throws(IOException::class)
    fun disconnectClient(serial: String, handle: Long) {
        write("application disconnect_users serial=$serial handle=0x%08X".format(handle))
    }

    @Throws(IOException::class)
    private fun write(line: String) {
        val out = output ?: throw IOException("SmartLink not connected")
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        synchronized(writeLock) {
            out.write(bytes)
            out.flush()
        }
    }

    fun disconnect() {
        pingJob?.cancel()
        pingJob = null
        readerJob?.cancel()
        readerJob = null
        runCatching { socket?.close() }
        socket = null
        output = null
        _connected.value = false
    }

    /** Forgets the in-memory id token. Stored refresh tokens are handled elsewhere. */
    fun signOut() {
        idToken = null
        disconnect()
        _radios.value = emptyList()
    }

    companion object {
        private const val TAG = "SmartLinkClient"

        // FlexRadio's Auth0 tenant. These are public client identifiers, not
        // secrets: they identify the application, not the user.
        private const val AUTH0_DOMAIN = "frtest.auth0.com"
        private const val AUTH0_CLIENT_ID = "4Y9fEIIsVYyQo5u6jr7yBWc4lV5ugC2m"
        private const val AUTH0_SCOPE =
            "openid email given_name family_name profile picture offline_access"

        private const val SMARTLINK_HOST = "smartlink.flexradio.com"
        private const val SMARTLINK_PORT = 443
        private const val CLIENT_NAME = "FlexAndroid"

        private const val HTTP_TIMEOUT_MS = 20_000
        private const val PING_INTERVAL_MS = 10_000L

        /**
         * Parses a `radio list` message into one [WanRadio] per pipe-delimited entry.
         *
         * Port selection follows the same precedence FlexRadio's own clients use:
         * an operator's manual port forward wins, UPnP-mapped ports are the
         * fallback, and a radio offering neither needs UDP hole punching.
         */
        fun parseRadioList(message: String): List<WanRadio> {
            val body = message.removePrefix("radio list ")
            return body.split('|')
                .filter { it.isNotBlank() }
                .mapNotNull { entry ->
                    val kv = FlexMessageParser.parseFields(entry)
                    val serial = kv["serial"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null

                    val manualTls = kv["public_tls_port"]?.toIntOrNull() ?: -1
                    val manualUdp = kv["public_udp_port"]?.toIntOrNull() ?: -1
                    val upnpSupported = kv["upnp_supported"] == "1"
                    val upnpTls = kv["public_upnp_tls_port"]?.toIntOrNull() ?: -1
                    val upnpUdp = kv["public_upnp_udp_port"]?.toIntOrNull() ?: -1

                    val (tlsPort, udpPort) = when {
                        manualTls > 0 && manualUdp > 0 -> manualTls to manualUdp
                        upnpSupported && upnpTls > 0 && upnpUdp > 0 -> upnpTls to upnpUdp
                        else -> -1 to -1
                    }

                    WanRadio(
                        serial = serial,
                        nickname = (kv["radio_name"] ?: kv["name"]).orEmpty(),
                        model = kv["model"].orEmpty(),
                        callsign = kv["callsign"].orEmpty(),
                        status = kv["status"] ?: "Unknown",
                        publicIp = kv["public_ip"].orEmpty(),
                        publicTlsPort = tlsPort,
                        publicUdpPort = udpPort,
                        upnpSupported = upnpSupported,
                        requiresHolePunch = !upnpSupported && tlsPort <= 0,
                        inUseBy = kv["gui_client_stations"].orEmpty(),
                    )
                }
        }
    }
}
