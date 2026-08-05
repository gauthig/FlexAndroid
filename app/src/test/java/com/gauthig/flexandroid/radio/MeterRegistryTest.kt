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

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeterRegistryTest {

    private val definitions =
        "meter 7.src=SLC#7.num=0#7.nam=LEVEL#7.unit=dBm#7.low=-150.0#7.hi=20.0" +
            "#15.src=TX-#15.num=1#15.nam=FWDPWR#15.unit=dBm#15.low=0.0#15.hi=53.0" +
            "#13.src=RAD#13.num=208#13.nam=+13.8A#13.unit=Volts#13.low=10.5#13.hi=15.0" +
            "#18.src=TX-#18.num=4#18.nam=PATEMP#18.unit=degC#18.low=0.0#18.hi=100.0"

    @Test
    fun `parses hash separated definitions`() {
        val registry = MeterRegistry()
        registry.acceptDefinitions(definitions)

        val level = registry[7]
        assertNotNull(level)
        checkNotNull(level)
        assertEquals("SLC", level.source)
        assertEquals("LEVEL", level.name)
        assertEquals("dBm", level.unit)
        assertEquals(-150.0f, level.low, 0.01f)
        assertEquals(20.0f, level.high, 0.01f)
    }

    @Test
    fun `applies the divisor matching each unit`() {
        val registry = MeterRegistry()
        registry.acceptDefinitions(definitions)

        val payload = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(7); putShort((-73 * 128).toShort()) // dBm  -> /128
            putShort(15); putShort((40 * 128).toShort()) // dBm  -> /128
            putShort(13); putShort((13 * 1024).toShort()) // Volts -> /1024
            putShort(18); putShort((45 * 64).toShort()) // degC  -> /64
            flip()
        }

        val values = registry.decode(payload)
        assertEquals(-73f, values[7]!!, 0.01f)
        assertEquals(40f, values[15]!!, 0.01f)
        assertEquals(13f, values[13]!!, 0.01f)
        assertEquals(45f, values[18]!!, 0.01f)
    }

    @Test
    fun `skips values for meters it has no definition for`() {
        val registry = MeterRegistry()
        // No definitions ingested: without a unit there is no divisor, and a
        // wrongly scaled meter reading is worse than a missing one.
        val payload = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(99); putShort(1234)
            flip()
        }
        assertTrue(registry.decode(payload).isEmpty())
    }

    @Test
    fun `finds the slice level meter by slice number`() {
        val registry = MeterRegistry()
        registry.acceptDefinitions(
            "meter 21.src=SLC#21.num=0#21.nam=LEVEL#21.unit=dBm" +
                "#31.src=SLC#31.num=1#31.nam=LEVEL#31.unit=dBm",
        )
        assertEquals(21, registry.sliceLevelMeter(0)?.id)
        assertEquals(31, registry.sliceLevelMeter(1)?.id)
    }

    @Test
    fun `extracts a meter payload only from meter status lines`() {
        assertEquals(
            "meter 7.src=SLC#7.nam=LEVEL",
            MeterRegistry.meterPayloadOrNull("S0|meter 7.src=SLC#7.nam=LEVEL"),
        )
        assertNull(MeterRegistry.meterPayloadOrNull("S0|slice 0 mode=USB"))
        assertNull(MeterRegistry.meterPayloadOrNull("no separator here"))
    }

    @Test
    fun `maps dBm onto the S meter scale`() {
        // S9 is -73 dBm, with 6 dB per S-unit below it.
        assertEquals(9f, MeterRegistry.dbmToSUnits(-73f), 0.01f)
        assertEquals(8f, MeterRegistry.dbmToSUnits(-79f), 0.01f)
        assertEquals(0f, MeterRegistry.dbmToSUnits(-127f), 0.01f)
        // Below S0 the meter pins rather than reading negative.
        assertEquals(0f, MeterRegistry.dbmToSUnits(-200f), 0.01f)

        assertEquals(0f, MeterRegistry.dbOverS9(-80f), 0.01f)
        assertEquals(20f, MeterRegistry.dbOverS9(-53f), 0.01f)
    }
}
