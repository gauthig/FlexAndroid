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

import com.gauthig.flexandroid.net.FlexMessageParser
import java.nio.ByteBuffer

/** One meter the radio has told us about, from a `meter list` or meter status. */
data class MeterDefinition(
    val id: Int,
    val source: String,
    val number: Int,
    val name: String,
    val unit: String,
    val low: Float,
    val high: Float,
)

/**
 * Tracks meter definitions and turns raw meter packets into engineering units.
 *
 * Meters arrive in two halves. Definitions come over TCP — and unusually for this
 * protocol they use `#` rather than a space between pairs, with each key prefixed
 * by the meter's ID:
 *
 * ```
 * meter 7.src=SLC#7.num=0#7.nam=LEVEL#7.unit=dBm#7.low=-150.0#7.hi=20.0
 * ```
 *
 * Values come over UDP as a bare list of `(uint16 id, int16 raw)` pairs, with the
 * divisor to apply depending on the unit recorded in the definition. Without the
 * definitions the values are meaningless, which is why [scaleFor] returns `null`
 * for an unknown ID rather than guessing.
 */
class MeterRegistry {

    private val definitions = HashMap<Int, MeterDefinition>()

    /** Ingests a `meter ...` payload, whether from a command reply or a status line. */
    fun acceptDefinitions(payload: String) {
        val body = payload.removePrefix("meter ").trim()
        if (body.isEmpty()) return

        // Group `<id>.<field>=<value>` tokens by meter ID.
        val grouped = HashMap<Int, MutableMap<String, String>>()
        for (token in body.split('#')) {
            val eq = token.indexOf('=')
            if (eq <= 0) continue
            val dotted = token.substring(0, eq)
            val dot = dotted.indexOf('.')
            if (dot <= 0) continue
            val id = dotted.substring(0, dot).trim().toIntOrNull() ?: continue
            grouped.getOrPut(id) { HashMap() }[dotted.substring(dot + 1)] = token.substring(eq + 1)
        }

        for ((id, fields) in grouped) {
            // Status updates can carry a subset of fields; merge onto what we know.
            val existing = definitions[id]
            definitions[id] = MeterDefinition(
                id = id,
                source = fields["src"] ?: existing?.source.orEmpty(),
                number = fields["num"]?.toIntOrNull() ?: existing?.number ?: 0,
                name = fields["nam"] ?: existing?.name.orEmpty(),
                unit = fields["unit"] ?: existing?.unit.orEmpty(),
                low = fields["low"]?.toFloatOrNull() ?: existing?.low ?: 0f,
                high = fields["hi"]?.toFloatOrNull() ?: existing?.high ?: 0f,
            )
        }
    }

    /** The meter named [name] from source [source], if the radio has one. */
    fun find(name: String, source: String): MeterDefinition? =
        definitions.values.firstOrNull {
            it.name.equals(name, ignoreCase = true) && it.source.equals(source, ignoreCase = true)
        }

    /**
     * The S-meter for a slice: the passband signal strength, in dBm.
     *
     * Source `SLC` covers every slice, and the radio reports one LEVEL meter per
     * slice number, so the slice's own number is what disambiguates them.
     */
    fun sliceLevelMeter(sliceNumber: Int): MeterDefinition? =
        definitions.values.firstOrNull {
            it.name.equals("LEVEL", ignoreCase = true) &&
                it.source.equals("SLC", ignoreCase = true) &&
                it.number == sliceNumber
        } ?: find("LEVEL", "SLC")

    /**
     * Decodes a meter value packet into `id -> value` in the meter's own unit.
     *
     * Unknown IDs are skipped: without a definition there is no divisor to apply,
     * and a wrongly scaled meter is worse than a missing one.
     */
    fun decode(payload: ByteBuffer): Map<Int, Float> {
        val buf = payload.duplicate()
        val out = HashMap<Int, Float>()
        while (buf.remaining() >= 4) {
            val id = buf.short.toInt() and 0xFFFF
            val raw = buf.short.toInt() // signed: meters go negative
            val scale = scaleFor(id) ?: continue
            out[id] = raw / scale
        }
        return out
    }

    /**
     * The divisor that converts a raw meter word to its unit.
     *
     * The fixed-point format is per-unit rather than per-meter, and is not part of
     * the published API — these divisors come from FlexRadio's own client library.
     */
    fun scaleFor(id: Int): Float? {
        val unit = definitions[id]?.unit ?: return null
        return when (unit.lowercase()) {
            "dbm", "db", "dbfs", "swr" -> 128f
            "volts", "amps" -> 1024f
            "degf", "degc" -> 64f
            else -> 128f
        }
    }

    /** Definition for [id], if known. */
    operator fun get(id: Int): MeterDefinition? = definitions[id]

    fun clear() = definitions.clear()

    companion object {
        /**
         * Extracts a `meter ...` blob from a status line's raw text.
         *
         * Meter status lines cannot go through the normal field parser because
         * their separator is `#`, so [RadioSession] hands the raw line here
         * instead.
         */
        fun meterPayloadOrNull(rawStatusLine: String): String? {
            val bar = rawStatusLine.indexOf('|')
            if (bar < 0) return null
            val body = rawStatusLine.substring(bar + 1).trim()
            return body.takeIf { it.startsWith("meter ") }
        }

        /** Converts a dBm signal level to the conventional S-unit scale. */
        fun dbmToSUnits(dbm: Float): Float {
            // S9 is -73 dBm on HF, with 6 dB per S-unit below it.
            val s = 9f + (dbm - S9_DBM) / DB_PER_S_UNIT
            return s.coerceIn(0f, 9f + OVER_S9_DB_RANGE / DB_PER_S_UNIT)
        }

        /** dB over S9, for the part of the meter above S9. */
        fun dbOverS9(dbm: Float): Float = (dbm - S9_DBM).coerceAtLeast(0f)

        private const val S9_DBM = -73f
        private const val DB_PER_S_UNIT = 6f
        private const val OVER_S9_DB_RANGE = 60f

        /** Convenience wrapper so callers need not import the parser directly. */
        fun parseFields(text: String): Map<String, String> = FlexMessageParser.parseFields(text)
    }
}
