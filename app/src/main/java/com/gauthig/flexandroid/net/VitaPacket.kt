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

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * VITA-49 framing as FlexRadio uses it on UDP port 4991.
 *
 * Every streaming payload — spectrum, waterfall, audio, meters, discovery —
 * arrives wrapped in the same 28-byte header. The byte that actually decides
 * how to read the payload is the **packet class code** in the low 16 bits of
 * header word 3; see [PacketClass].
 *
 * ```
 * word 0  header: type | C | TSI | TSF | packet count | size in 32-bit words
 * word 1  stream id
 * word 2  class id high: 24-bit OUI (FlexRadio = 00-1C-2D)
 * word 3  class id low: information class (0x534C) | packet class code
 * word 4  integer timestamp
 * word 5  fractional timestamp, high word
 * word 6  fractional timestamp, low word
 * word 7+ payload
 * ```
 *
 * All multi-byte fields are big-endian (network order), including the payload
 * samples — which is why the audio decoders below byte-swap rather than
 * memcpy.
 */
data class VitaPacket(
    val packetType: Int,
    val classIdPresent: Boolean,
    val trailerPresent: Boolean,
    val packetCount: Int,
    val sizeWords: Int,
    val streamId: Long,
    val oui: Long,
    val informationClass: Int,
    val packetClass: Int,
    /** Payload bytes, big-endian, excluding the header. */
    val payload: ByteBuffer,
) {
    companion object {
        const val HEADER_BYTES = 28

        /** FlexRadio's IEEE OUI, carried in class-id word 2. */
        const val FLEX_OUI = 0x00001C2DL

        /** "SL" (SmoothLake) — the information class on every FlexRadio packet. */
        const val FLEX_INFORMATION_CLASS = 0x534C

        /**
         * Decodes a received datagram.
         *
         * Returns `null` when the buffer is too short, is not a class-ID-bearing
         * FlexRadio packet, or declares a length that does not fit what we
         * actually received — all of which are the shapes a stray datagram on
         * port 4991 takes.
         */
        fun parse(data: ByteArray, length: Int = data.size): VitaPacket? {
            if (length < HEADER_BYTES) return null
            val buf = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN)

            val header = buf.int
            val packetType = (header ushr 28) and 0x0F
            val classIdPresent = (header and 0x08000000) != 0
            val trailerPresent = (header and 0x04000000) != 0
            val packetCount = (header ushr 16) and 0x0F
            val sizeWords = header and 0xFFFF

            // Without a class ID there is no packet class code, so there is no
            // way to know what the payload means. Drop it.
            if (!classIdPresent) return null

            val streamId = buf.int.toLong() and 0xFFFFFFFFL
            val classHigh = buf.int.toLong() and 0xFFFFFFFFL
            val classLow = buf.int
            // Timestamps are read to advance the cursor; nothing here needs them.
            buf.int
            buf.long

            val declaredBytes = sizeWords * 4
            // A declared size larger than the datagram means truncation.
            if (declaredBytes > length) return null
            val payloadEnd = if (declaredBytes >= HEADER_BYTES) declaredBytes else length

            val payload = ByteBuffer
                .wrap(data, HEADER_BYTES, payloadEnd - HEADER_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .slice()
                .order(ByteOrder.BIG_ENDIAN)

            return VitaPacket(
                packetType = packetType,
                classIdPresent = true,
                trailerPresent = trailerPresent,
                packetCount = packetCount,
                sizeWords = sizeWords,
                streamId = streamId,
                oui = classHigh and 0x00FFFFFFL,
                informationClass = (classLow ushr 16) and 0xFFFF,
                packetClass = classLow and 0xFFFF,
                payload = payload,
            )
        }

        /**
         * Frames [payload] for transmission to the radio.
         *
         * Mirrors the header FlexRadio's own firmware emits: extension data with
         * a stream ID, class ID present, "other" time source, sample-count
         * fractional timestamp. [packetCount] must advance by one per packet and
         * wrap at 16 — the radio uses it to spot dropped datagrams.
         */
        fun build(
            streamId: Long,
            packetClass: Int,
            payload: ByteArray,
            payloadLength: Int = payload.size,
            packetCount: Int = 0,
        ): ByteArray {
            require(payloadLength % 4 == 0) {
                "VITA-49 payloads are word-aligned; got $payloadLength bytes"
            }
            val sizeWords = (HEADER_BYTES + payloadLength) / 4
            require(sizeWords <= 0xFFFF) { "packet too large: $sizeWords words" }

            val out = ByteBuffer
                .allocate(HEADER_BYTES + payloadLength)
                .order(ByteOrder.BIG_ENDIAN)

            val header = EXT_DATA_WITH_STREAM_ID or
                CLASS_ID_PRESENT or
                TSI_OTHER or
                TSF_SAMPLE_COUNT or
                ((packetCount and 0x0F) shl 16) or
                sizeWords
            out.putInt(header)
            out.putInt(streamId.toInt())
            out.putInt(FLEX_OUI.toInt())
            out.putInt((FLEX_INFORMATION_CLASS shl 16) or (packetClass and 0xFFFF))
            out.putInt(0) // integer timestamp: the radio does not require one
            out.putLong(0L) // fractional timestamp
            out.put(payload, 0, payloadLength)
            return out.array()
        }

        // Header bit fields, per the VITA-49 spec's word-0 layout.
        private const val EXT_DATA_WITH_STREAM_ID = 0x30000000
        private const val CLASS_ID_PRESENT = 0x08000000
        private const val TSI_OTHER = 0x00C00000
        private const val TSF_SAMPLE_COUNT = 0x00100000
    }
}

/**
 * The packet class codes FlexAndroid handles.
 *
 * Values are taken from the SmartSDR firmware's own emitters; the FlexRadio API
 * documentation covers the control protocol but leaves these to be read off the
 * wire, so they are recorded here and in docs/PROTOCOL.md.
 */
object PacketClass {
    /** FFT panadapter bins. Payload: 12-byte sub-header then uint16 bins. */
    const val FFT = 0x8003

    /** Waterfall tiles. Payload: 36-byte sub-header then uint16 bins. */
    const val WATERFALL = 0x8004

    /** Meter values. Payload: N x (uint16 meter id, int16 raw value). */
    const val METER = 0x8002

    /** Discovery beacon. Payload: an ASCII `key=value` status string. */
    const val DISCOVERY = 0xFFFF

    /** Uncompressed remote audio: big-endian float32, interleaved stereo, 24 kHz. */
    const val AUDIO_FLOAT32 = 0x03E3

    /** DAX / reduced-bandwidth audio: big-endian int16, mono, 24 kHz. */
    const val AUDIO_INT16_MONO = 0x0123

    /** Opus-compressed remote audio, one Opus frame per packet, 24 kHz stereo. */
    const val AUDIO_OPUS = 0x8005
}
