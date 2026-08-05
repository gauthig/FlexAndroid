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
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * The command channel to a radio: line-oriented ASCII in both directions.
 *
 * One class covers both transports because the grammar above the socket is
 * identical. A local radio is a plain TCP socket on port 4992; a SmartLink radio
 * is a TLS socket on the radio's public port that must first be authenticated
 * with `wan validate handle=<h>`. [connectDirect] and [connectWan] differ only
 * in how they obtain that socket.
 *
 * Not thread-confined: [send] may be called from any thread, and writes are
 * serialised on the socket's output stream.
 */
class RadioConnection(
    private val scope: CoroutineScope,
) {
    private val sequence = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, (FlexMessage.Response) -> Unit>()

    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var readerJob: Job? = null
    private val writeLock = Any()

    private val _messages = MutableSharedFlow<FlexMessage>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Every decoded line from the radio, in arrival order. */
    val messages: SharedFlow<FlexMessage> = _messages

    /** Protocol version the radio announced, once known. */
    @Volatile
    var radioVersion: String? = null
        private set

    /** This client's handle, assigned by the radio on connect. */
    @Volatile
    var clientHandle: Long = 0L
        private set

    val isConnected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    /** Local port the command socket bound to; the radio sees this as our source. */
    val localPort: Int get() = socket?.localPort ?: 0

    /**
     * Opens a plain TCP session to a radio on the local network.
     *
     * @param host radio IPv4 address, as reported by discovery
     */
    @Throws(IOException::class)
    suspend fun connectDirect(host: String, port: Int = COMMAND_PORT) {
        withContext(Dispatchers.IO) {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            attach(s)
        }
    }

    /**
     * Opens a TLS session to a radio reachable over the internet and completes
     * the SmartLink handshake.
     *
     * The [wanHandle] comes from the SmartLink server's `radio connect_ready`
     * message and is what authorises this socket — without it the radio ignores
     * every subsequent command.
     */
    @Throws(IOException::class)
    suspend fun connectWan(
        host: String,
        tlsPort: Int,
        wanHandle: String,
        trust: RadioTrustStore,
    ) {
        withContext(Dispatchers.IO) {
            val s = trust.createSocket(host, tlsPort)
            s.tcpNoDelay = true
            s.startHandshake()
            trust.verifyPin(host, s)
            attach(s)
            // Sent before anything else, and deliberately not awaited: the radio
            // answers with its V/H prologue rather than an R response.
            writeLine("wan validate handle=$wanHandle")
        }
    }

    private fun attach(s: Socket) {
        socket = s
        output = s.getOutputStream()
        readerJob = scope.launch(Dispatchers.IO) { readLoop(s) }
    }

    private suspend fun readLoop(s: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) continue
                val msg = FlexMessageParser.parse(line)
                when (msg) {
                    is FlexMessage.Version -> radioVersion = msg.version
                    is FlexMessage.Handle -> clientHandle = msg.handle
                    is FlexMessage.Response -> pending.remove(msg.seq)?.invoke(msg)
                    else -> Unit
                }
                _messages.emit(msg)
            }
        } catch (e: IOException) {
            // A closed socket during teardown is the normal path out of this loop.
            Log.d(TAG, "read loop ended: ${e.message}")
        } finally {
            failAllPending()
            // tryEmit rather than emit: this runs during cancellation, where a
            // suspending emit would be dropped before it reached subscribers.
            _messages.tryEmit(FlexMessage.Unknown(DISCONNECTED_SENTINEL))
        }
    }

    /**
     * Sends [command] and suspends until the radio's matching `R` line arrives.
     *
     * The protocol guarantees exactly one response per command, correlated by
     * sequence number, so callers can treat this as an ordinary request/reply.
     */
    @Throws(IOException::class)
    suspend fun send(command: String): FlexMessage.Response {
        val seq = sequence.getAndIncrement()
        return suspendCancellableCoroutine { cont ->
            pending[seq] = { response -> if (cont.isActive) cont.resume(response) }
            cont.invokeOnCancellation { pending.remove(seq) }
            try {
                writeLine("C$seq|$command")
            } catch (e: IOException) {
                pending.remove(seq)
                cont.cancel(e)
            }
        }
    }

    /**
     * Sends [command] without waiting for its response.
     *
     * For fire-and-forget traffic — `ping`, PTT edges — where a suspension point
     * would add latency that matters more than the acknowledgement.
     */
    fun sendAndForget(command: String) {
        val seq = sequence.getAndIncrement()
        try {
            writeLine("C$seq|$command")
        } catch (e: IOException) {
            Log.w(TAG, "send failed: $command", e)
        }
    }

    @Throws(IOException::class)
    private fun writeLine(line: String) {
        val out = output ?: throw IOException("not connected")
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        synchronized(writeLock) {
            out.write(bytes)
            out.flush()
        }
    }

    fun close() {
        readerJob?.cancel()
        readerJob = null
        failAllPending()
        try {
            socket?.close()
        } catch (e: IOException) {
            Log.d(TAG, "close: ${e.message}")
        }
        socket = null
        output = null
    }

    private fun failAllPending() {
        // Unblock anyone waiting on a reply that will never arrive. 0xFFFFFFFF is
        // not a code the radio emits, so callers can tell it apart from a real
        // protocol failure.
        val snapshot = pending.entries.toList()
        pending.clear()
        for ((seq, callback) in snapshot) {
            callback(
                FlexMessage.Response(
                    seq = seq,
                    code = DISCONNECTED_CODE,
                    message = "connection closed",
                    debug = null,
                ),
            )
        }
    }

    companion object {
        private const val TAG = "RadioConnection"

        /** SmartSDR's control port on the local network. */
        const val COMMAND_PORT = 4992

        /** UDP port the radio streams VITA-49 data from. */
        const val VITA_PORT = 4991

        private const val CONNECT_TIMEOUT_MS = 10_000

        /** Synthetic response code used when a command outlives its socket. */
        const val DISCONNECTED_CODE = 0xFFFFFFFFL

        /** Emitted on [messages] once the read loop ends, so sessions can react. */
        const val DISCONNECTED_SENTINEL = "__flexandroid_disconnected__"
    }
}

/** A TLS socket factory paired with trust-on-first-use certificate pinning. */
interface RadioTrustStore {
    fun createSocket(host: String, port: Int): SSLSocket

    /**
     * Compares the peer's certificate against the pin recorded for [host].
     *
     * Implementations throw when a previously seen host presents a different
     * certificate, so the caller never sends `wan validate` — and therefore
     * never authenticates — over a suspect path.
     */
    @Throws(IOException::class)
    fun verifyPin(host: String, socket: SSLSocket)
}
