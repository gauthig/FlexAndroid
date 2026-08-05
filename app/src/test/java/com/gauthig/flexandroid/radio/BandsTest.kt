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
package com.gauthig.flexandroid.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BandsTest {

    @Test
    fun `resolves a frequency to its band`() {
        assertEquals(Band.B20, Band.of(14.250))
        assertEquals(Band.B40, Band.of(7.200))
        assertEquals(Band.B2, Band.of(146.520))
    }

    @Test
    fun `returns no band outside an amateur allocation`() {
        assertNull(Band.of(13.500))
        assertNull(Band.of(0.5))
    }

    @Test
    fun `band edges are inclusive`() {
        assertEquals(Band.B20, Band.of(14.000))
        assertEquals(Band.B20, Band.of(14.350))
    }

    @Test
    fun `every band's calling frequency sits inside it`() {
        Band.entries.forEach { band ->
            assertTrue(
                "${band.label} default ${band.defaultFrequencyMHz} is outside the band",
                band.defaultFrequencyMHz in band,
            )
        }
    }

    @Test
    fun `phone bands below 10 MHz default to lower sideband`() {
        // Convention, not a rule the radio enforces: LSB below 10 MHz, USB above.
        assertEquals(Mode.LSB, Band.B160.defaultMode)
        assertEquals(Mode.LSB, Band.B80.defaultMode)
        assertEquals(Mode.LSB, Band.B40.defaultMode)
        assertEquals(Mode.USB, Band.B20.defaultMode)
        assertEquals(Mode.USB, Band.B10.defaultMode)
    }

    @Test
    fun `maps radio mode strings back to modes`() {
        assertEquals(Mode.USB, Mode.fromWire("USB"))
        assertEquals(Mode.USB, Mode.fromWire("usb"))
        assertEquals(Mode.DIGU, Mode.fromWire("DIGU"))
        // Modes this UI does not offer stay unmapped rather than being coerced.
        assertNull(Mode.fromWire("sam"))
        assertNull(Mode.fromWire(""))
    }

    @Test
    fun `voice modes are the ones the microphone can drive`() {
        assertTrue(Mode.USB.isVoice)
        assertTrue(Mode.FM.isVoice)
        assertTrue(!Mode.CW.isVoice)
        assertTrue(!Mode.DIGU.isVoice)
    }
}
