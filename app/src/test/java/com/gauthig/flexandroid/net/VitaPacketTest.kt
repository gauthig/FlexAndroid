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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VitaPacketTest {

    @Test
    fun `round trips a built packet`() {
        val payload = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .putFloat(0.5f)
            .putFloat(-0.25f)
            .array()

        val frame = VitaPacket.build(
            streamId = 0x04000001L,
            packetClass = PacketClass.AUDIO_FLOAT32,
            payload = payload,
            packetCount = 3,
        )

        val parsed = VitaPacket.parse(frame)
        assertNotNull(parsed)
        checkNotNull(parsed)
        assertEquals(0x04000001L, parsed.streamId)
        assertEquals(PacketClass.AUDIO_FLOAT32, parsed.packetClass)
        assertEquals(VitaPacket.FLEX_INFORMATION_CLASS, parsed.informationClass)
        assertEquals(VitaPacket.FLEX_OUI, parsed.oui)
        assertEquals(3, parsed.packetCount)
        assertEquals((VitaPacket.HEADER_BYTES + payload.size) / 4, parsed.sizeWords)
        assertEquals(0.5f, parsed.payload.float, 0f)
        assertEquals(-0.25f, parsed.payload.float, 0f)
    }

    @Test
    fun `parses stream ids above Int MAX_VALUE`() {
        // Stream IDs like 0x84000000 are routine; a signed read would make this
        // negative and every stream-routing comparison would miss.
        val frame = VitaPacket.build(
            streamId = 0x84000000L,
            packetClass = PacketClass.FFT,
            payload = ByteArray(4),
        )
        assertEquals(0x84000000L, VitaPacket.parse(frame)!!.streamId)
    }

    @Test
    fun `rejects truncated and headerless datagrams`() {
        assertNull(VitaPacket.parse(ByteArray(0)))
        assertNull(VitaPacket.parse(ByteArray(VitaPacket.HEADER_BYTES - 1)))
    }

    @Test
    fun `rejects a packet with no class id`() {
        // Same header but with the class-id-present bit cleared: without a class
        // ID there is no packet class code, so the payload cannot be interpreted.
        val frame = VitaPacket.build(0x1L, PacketClass.FFT, ByteArray(4))
        frame[0] = (frame[0].toInt() and 0xF7).toByte()
        assertNull(VitaPacket.parse(frame))
    }

    @Test
    fun `rejects a packet claiming more words than were received`() {
        val frame = VitaPacket.build(0x1L, PacketClass.FFT, ByteArray(4))
        // Overstate the declared length: the payload would read past the datagram.
        frame[2] = 0x0F
        frame[3] = 0xFF.toByte()
        assertNull(VitaPacket.parse(frame))
    }

    @Test
    fun `requires word aligned payloads when building`() {
        val thrown = runCatching {
            VitaPacket.build(0x1L, PacketClass.AUDIO_FLOAT32, ByteArray(3))
        }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
    }
}
