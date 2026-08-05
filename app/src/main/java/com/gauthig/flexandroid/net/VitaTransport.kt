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
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The UDP half of a radio session: VITA-49 datagrams in both directions.
 *
 * One socket carries everything — spectrum, meters, RX audio inbound, TX audio
 * outbound — because the radio multiplexes streams by the stream ID inside each
 * packet rather than by port.
 *
 * Received packets are dispatched on the socket's reader thread. Handlers must
 * not block: a slow handler shows up as dropped spectrum frames and stuttering
 * audio, not as backpressure.
 */
class VitaTransport(
    private val scope: CoroutineScope,
) {
    private var socket: DatagramSocket? = null
    private var readerJob: Job? = null

    private var remoteAddress: InetAddress? = null
    private var remotePort: Int = 0

    /** Packet counter for outbound datagrams; the radio uses it to spot drops. */
    private var txPacketCount = 0

    /** The local UDP port in use. Passed to the radio via `client udpport`. */
    val localPort: Int get() = socket?.localPort ?: 0

    /**
     * Binds a local UDP socket.
     *
     * Passing port 0 lets the OS choose, which is what we want: the chosen port
     * is then announced to the radio, and for SmartLink it is also the port the
     * hole-punch request is made against.
     */
    @Throws(SocketException::class)
    fun bind(port: Int = 0) {
        val s = DatagramSocket(port)
        s.soTimeout = SO_TIMEOUT_MS
        s.receiveBufferSize = RECEIVE_BUFFER_BYTES
        socket = s
    }

    /** Points outbound traffic at [host]:[port] — the radio's VITA endpoint. */
    @Throws(IOException::class)
    fun setRemote(host: String, port: Int) {
        remoteAddress = InetAddress.getByName(host)
        remotePort = port
    }

    /**
     * Starts dispatching received packets to [onPacket].
     *
     * [onPacket] receives a packet whose payload buffer is backed by a reusable
     * receive array, so it must finish reading before it returns.
     */
    fun start(onPacket: (VitaPacket) -> Unit) {
        val s = socket ?: error("bind() before start()")
        readerJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(MAX_DATAGRAM_BYTES)
            val packet = DatagramPacket(buffer, buffer.size)
            while (isActive) {
                try {
                    packet.setData(buffer, 0, buffer.size)
                    s.receive(packet)
                    VitaPacket.parse(buffer, packet.length)?.let(onPacket)
                } catch (_: SocketTimeoutException) {
                    // Idle socket; loop so cancellation is observed promptly.
                } catch (e: IOException) {
                    if (isActive) Log.d(TAG, "vita receive ended: ${e.message}")
                    break
                }
            }
        }
    }

    /**
     * Opens the inbound NAT mapping for radios with no forwarded ports.
     *
     * The SmartLink server tells the radio to start sending to our public
     * address at the same moment; these outbound datagrams create the mapping
     * that lets the radio's traffic back in. The payload is a minimal VITA-49
     * packet so anything that inspects it sees well-formed traffic.
     */
    fun punchHole(repeat: Int = HOLE_PUNCH_PACKETS) {
        val s = socket ?: return
        val addr = remoteAddress ?: return
        val frame = VitaPacket.build(
            streamId = 0L,
            packetClass = PacketClass.DISCOVERY,
            payload = ByteArray(4),
        )
        repeat(repeat) {
            try {
                s.send(DatagramPacket(frame, frame.size, addr, remotePort))
            } catch (e: IOException) {
                Log.d(TAG, "hole punch send failed: ${e.message}")
            }
        }
    }

    /** Wraps [payload] in a VITA-49 frame for [streamId] and sends it. */
    fun send(streamId: Long, packetClass: Int, payload: ByteArray, length: Int = payload.size) {
        val s = socket ?: return
        val addr = remoteAddress ?: return
        val frame = VitaPacket.build(
            streamId = streamId,
            packetClass = packetClass,
            payload = payload,
            payloadLength = length,
            packetCount = txPacketCount,
        )
        txPacketCount = (txPacketCount + 1) and 0x0F
        try {
            s.send(DatagramPacket(frame, frame.size, addr, remotePort))
        } catch (e: IOException) {
            Log.d(TAG, "vita send failed: ${e.message}")
        }
    }

    fun close() {
        readerJob?.cancel()
        readerJob = null
        socket?.close()
        socket = null
    }

    private companion object {
        const val TAG = "VitaTransport"

        /** Comfortably above the largest FFT/waterfall datagram the radio emits. */
        const val MAX_DATAGRAM_BYTES = 16_384

        /** Sized to absorb a spectrum burst while the app is briefly descheduled. */
        const val RECEIVE_BUFFER_BYTES = 1 shl 20

        const val SO_TIMEOUT_MS = 1_000
        const val HOLE_PUNCH_PACKETS = 5
    }
}
