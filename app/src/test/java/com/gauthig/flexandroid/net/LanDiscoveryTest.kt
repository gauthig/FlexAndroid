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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanDiscoveryTest {

    @Test
    fun `parses a discovery beacon`() {
        val payload = (
            "model=FLEX-6600M serial=1234-5678-9012-3456 version=3.4.20.34 " +
                "nickname=Back_Porch callsign=W1AW ip=192.168.1.50 port=4992"
            ).toByteArray(Charsets.US_ASCII)

        val radio = LanDiscovery.parseBeacon(payload)
        assertEquals("1234-5678-9012-3456", radio?.serial)
        assertEquals("FLEX-6600M", radio?.model)
        // Beacons encode spaces in the name as underscores so the string stays
        // parseable as space-separated pairs.
        assertEquals("Back Porch", radio?.nickname)
        assertEquals("192.168.1.50", radio?.ip)
        assertEquals(4992, radio?.port)
    }

    @Test
    fun `tolerates the NUL padding the radio appends for word alignment`() {
        val text = "model=FLEX-6400 serial=A ip=192.168.1.9 port=4992"
        val padded = text.toByteArray(Charsets.US_ASCII) + ByteArray(4)
        assertEquals("A", LanDiscovery.parseBeacon(padded)?.serial)
    }

    @Test
    fun `defaults the command port when a beacon omits it`() {
        val payload = "model=FLEX-6400 serial=A ip=192.168.1.9".toByteArray(Charsets.US_ASCII)
        assertEquals(RadioConnection.COMMAND_PORT, LanDiscovery.parseBeacon(payload)?.port)
    }

    @Test
    fun `rejects beacons with no serial`() {
        assertNull(LanDiscovery.parseBeacon("model=FLEX-6400".toByteArray(Charsets.US_ASCII)))
        assertNull(LanDiscovery.parseBeacon(ByteArray(0)))
    }
}
