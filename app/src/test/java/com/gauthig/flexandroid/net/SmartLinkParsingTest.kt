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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartLinkParsingTest {

    @Test
    fun `prefers manually forwarded ports over UPnP`() {
        val radios = SmartLinkClient.parseRadioList(
            "radio list serial=1234-5678-9012-3456 model=FLEX-6600 radio_name=Shack " +
                "callsign=W1AW status=Available public_ip=203.0.113.7 " +
                "public_tls_port=5000 public_udp_port=5001 upnp_supported=1 " +
                "public_upnp_tls_port=6000 public_upnp_udp_port=6001",
        )

        assertEquals(1, radios.size)
        val radio = radios.single()
        assertEquals(5000, radio.publicTlsPort)
        assertEquals(5001, radio.publicUdpPort)
        assertFalse(radio.requiresHolePunch)
        assertEquals("Shack", radio.nickname)
    }

    @Test
    fun `falls back to UPnP ports when no manual forward exists`() {
        val radios = SmartLinkClient.parseRadioList(
            "radio list serial=A model=FLEX-6400 status=Available public_ip=203.0.113.9 " +
                "public_tls_port=-1 public_udp_port=-1 upnp_supported=1 " +
                "public_upnp_tls_port=6000 public_upnp_udp_port=6001",
        )
        val radio = radios.single()
        assertEquals(6000, radio.publicTlsPort)
        assertEquals(6001, radio.publicUdpPort)
        assertFalse(radio.requiresHolePunch)
    }

    @Test
    fun `marks a radio with neither forwarding nor UPnP as needing hole punching`() {
        val radios = SmartLinkClient.parseRadioList(
            "radio list serial=B model=FLEX-6300 status=Available public_ip=203.0.113.11 " +
                "upnp_supported=0",
        )
        val radio = radios.single()
        assertEquals(-1, radio.publicTlsPort)
        assertTrue(radio.requiresHolePunch)
    }

    @Test
    fun `splits several radios on the pipe separator`() {
        val radios = SmartLinkClient.parseRadioList(
            "radio list serial=A model=FLEX-6400 status=Available|" +
                "serial=B model=FLEX-6600 status=In_Use|",
        )
        assertEquals(listOf("A", "B"), radios.map { it.serial })
        assertEquals("In_Use", radios[1].status)
    }

    @Test
    fun `drops entries with no serial`() {
        val radios = SmartLinkClient.parseRadioList(
            "radio list model=FLEX-6400 status=Available|serial=B model=FLEX-6600",
        )
        assertEquals(listOf("B"), radios.map { it.serial })
    }

    @Test
    fun `falls back to the display name when a radio has no nickname`() {
        val radios = SmartLinkClient.parseRadioList("radio list serial=C model=FLEX-6700")
        assertEquals("FLEX-6700 C", radios.single().displayName)
    }
}
