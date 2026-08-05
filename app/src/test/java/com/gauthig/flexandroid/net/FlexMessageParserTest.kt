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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser tests built from the literal examples in FlexRadio's API documentation,
 * so a change that breaks compatibility with the published protocol fails here.
 */
class FlexMessageParserTest {

    @Test
    fun `parses version prologue`() {
        val message = FlexMessageParser.parse("V1.4.0.0")
        assertEquals(FlexMessage.Version("1.4.0.0"), message)
    }

    @Test
    fun `parses client handle as unsigned`() {
        // 0x9F4EC23D exceeds Int.MAX_VALUE; sign extension here would corrupt
        // every later handle comparison.
        val message = FlexMessageParser.parse("H9F4EC23D") as FlexMessage.Handle
        assertEquals(0x9F4EC23DL, message.handle)
    }

    @Test
    fun `parses successful response with message body`() {
        val response = FlexMessageParser
            .parse("R21|0|2|OK Slice receiver 2 created on 10.000 MHz on port ANT1")
                as FlexMessage.Response

        assertEquals(21L, response.seq)
        assertEquals(0L, response.code)
        assertEquals("2", response.message)
        assertEquals("OK Slice receiver 2 created on 10.000 MHz on port ANT1", response.debug)
        assertTrue(response.isSuccess)
    }

    @Test
    fun `parses failure response code as hex`() {
        val response = FlexMessageParser.parse("R41|50000032||") as FlexMessage.Response
        assertEquals(0x50000032L, response.code)
        assertTrue(!response.isSuccess)
    }

    @Test
    fun `keeps pipes inside debug output`() {
        val response = FlexMessageParser.parse("R7|0|ok|a|b|c") as FlexMessage.Response
        assertEquals("a|b|c", response.debug)
    }

    @Test
    fun `parses slice status into object path and fields`() {
        val status = FlexMessageParser.parse(
            "SA4E3D1C9|slice 2 in_use=1 RF_frequency=1.44 antenna_id=5 mode=USB wide=1 " +
                "filter_lo=0.0003 filter_hi=0.0024",
        ) as FlexMessage.Status

        assertEquals(0xA4E3D1C9L, status.handle)
        assertEquals("slice 2", status.obj)
        assertEquals("1.44", status.fields["RF_frequency"])
        assertEquals("USB", status.fields["mode"])
        assertEquals("0.0024", status.fields["filter_hi"])
    }

    @Test
    fun `parses multi word object path for panadapter`() {
        val status = FlexMessageParser.parse(
            "SA4E3D1C9|display pan 0x40000000 center=1.3 bandwidth=0.384 x_pixels=300 " +
                "y_pixels=200 fps=20",
        ) as FlexMessage.Status

        assertEquals("display pan 0x40000000", status.obj)
        assertEquals("1.3", status.fields["center"])
        assertEquals("20", status.fields["fps"])
    }

    @Test
    fun `parses interlock status with system handle`() {
        val status = FlexMessageParser
            .parse("S0|interlock state=TRANSMITTING source=RCA") as FlexMessage.Status

        assertEquals(0L, status.handle)
        assertEquals("interlock", status.obj)
        assertEquals("TRANSMITTING", status.fields["state"])
    }

    @Test
    fun `derives notice severity from bits 24 and 25`() {
        val info = FlexMessageParser.parse("M10000002|unknown client program") as FlexMessage.Notice
        assertEquals(FlexMessage.Notice.Severity.INFO, info.severity)

        val error = FlexMessageParser.parse("M52000001|something broke") as FlexMessage.Notice
        assertEquals(FlexMessage.Notice.Severity.ERROR, error.severity)
    }

    @Test
    fun `tolerates malformed lines`() {
        assertTrue(FlexMessageParser.parse("") is FlexMessage.Unknown)
        assertTrue(FlexMessageParser.parse("garbage") is FlexMessage.Unknown)
        assertTrue(FlexMessageParser.parse("R") is FlexMessage.Unknown)
        assertTrue(FlexMessageParser.parse("Rnotanumber|0|") is FlexMessage.Unknown)
        assertTrue(FlexMessageParser.parse("Snohandleseparator") is FlexMessage.Unknown)
    }

    @Test
    fun `parses hex with and without prefix`() {
        assertEquals(0x40000000L, FlexMessageParser.parseHex("0x40000000"))
        assertEquals(0x40000000L, FlexMessageParser.parseHex("40000000"))
        assertEquals(0x84000000L, FlexMessageParser.parseHex("0x84000000"))
        assertNull(FlexMessageParser.parseHex(""))
        assertNull(FlexMessageParser.parseHex("zz"))
    }

    @Test
    fun `field parser ignores tokens without a value`() {
        val fields = FlexMessageParser.parseFields("a=1 orphan b=2 =3")
        assertEquals(mapOf("a" to "1", "b" to "2"), fields)
    }
}
