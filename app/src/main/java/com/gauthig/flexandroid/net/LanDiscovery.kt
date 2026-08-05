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
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A radio found by listening for discovery beacons on the local network. */
data class LanRadio(
    val serial: String,
    val model: String,
    val nickname: String,
    val callsign: String,
    val version: String,
    val ip: String,
    val port: Int,
) {
    val displayName: String get() = nickname.ifBlank { "$model $serial" }
}

/**
 * Listens for the once-per-second discovery beacon FLEX radios broadcast.
 *
 * Since firmware v1.1.3 the beacon is a VITA-49 extension packet on UDP 4991
 * whose payload is an ASCII `key=value` status string — the same shape as a
 * status message, so it shares the control protocol's field parser.
 *
 * This path exists mainly for use on the same LAN as the radio, which is how the
 * SmartLink-independent parts of the app get exercised. Remote operation does
 * not need it.
 */
object LanDiscovery {

    /**
     * Emits each distinct radio as its beacon is first seen.
     *
     * The flow runs until cancelled. Binding may fail if another SmartSDR client
     * on the device already holds port 4991, in which case the flow completes
     * without emitting.
     */
    fun discover(): Flow<LanRadio> = callbackFlow {
        val socket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = SO_TIMEOUT_MS
                bind(InetSocketAddress(RadioConnection.VITA_PORT))
            }
        } catch (e: IOException) {
            Log.d(TAG, "cannot bind discovery port: ${e.message}")
            close()
            return@callbackFlow
        }

        val seen = mutableSetOf<String>()
        val buffer = ByteArray(MAX_BEACON_BYTES)
        val datagram = DatagramPacket(buffer, buffer.size)

        // The receive loop runs in its own coroutine so the builder can reach
        // awaitClose: closing the socket from there is what unblocks a receive
        // that is mid-timeout when the collector goes away.
        val reader = launch {
            try {
                while (isActive) {
                    try {
                        datagram.setData(buffer, 0, buffer.size)
                        socket.receive(datagram)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val vita = VitaPacket.parse(buffer, datagram.length) ?: continue
                    if (vita.packetClass != PacketClass.DISCOVERY) continue

                    val payload = ByteArray(vita.payload.remaining())
                    vita.payload.duplicate().get(payload)
                    val radio = parseBeacon(payload) ?: continue

                    if (seen.add(radio.serial)) trySend(radio)
                }
            } catch (e: IOException) {
                Log.d(TAG, "discovery ended: ${e.message}")
            }
        }

        awaitClose {
            reader.cancel()
            socket.close()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Decodes a beacon payload.
     *
     * The payload is NUL-padded to a word boundary, and the radio's name has its
     * spaces rewritten as underscores so the string stays parseable as
     * space-separated pairs. Both are undone here.
     */
    fun parseBeacon(payload: ByteArray): LanRadio? {
        // The radio pads the payload with NULs to reach a 32-bit boundary.
        // Those are not whitespace, so trimming would leave them attached to
        // the final value and turn `port=4992` into something unparseable.
        val end = payload.indexOf(0).let { if (it >= 0) it else payload.size }
        val text = String(payload, 0, end, Charsets.US_ASCII).trim()
        if (text.isEmpty()) return null
        val kv = FlexMessageParser.parseFields(text)
        val serial = kv["serial"]?.takeIf { it.isNotBlank() } ?: return null
        return LanRadio(
            serial = serial,
            model = kv["model"].orEmpty(),
            nickname = kv["nickname"].orEmpty().ifBlank { kv["name"].orEmpty() }
                .replace('_', ' '),
            callsign = kv["callsign"].orEmpty(),
            version = kv["version"].orEmpty(),
            ip = kv["ip"].orEmpty(),
            port = kv["port"]?.toIntOrNull() ?: RadioConnection.COMMAND_PORT,
        )
    }

    private const val TAG = "LanDiscovery"
    private const val MAX_BEACON_BYTES = 2_048
    private const val SO_TIMEOUT_MS = 1_000
}
