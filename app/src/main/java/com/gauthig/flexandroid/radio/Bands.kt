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

/**
 * An amateur band, with the frequency the app tunes to when the operator picks it.
 *
 * [defaultFrequencyMHz] lands in the part of the band matching [defaultMode] —
 * the SSB portion for phone bands, the CW end for 30 m — so a band change is one
 * tap rather than a tap plus a tune. Band edges are the ITU Region 2 (US)
 * allocations; the radio enforces its own region's transmit limits regardless of
 * what this table says, so these bounds are for display and clamping only.
 */
enum class Band(
    val label: String,
    val lowMHz: Double,
    val highMHz: Double,
    val defaultFrequencyMHz: Double,
    val defaultMode: Mode,
) {
    B160("160m", 1.800, 2.000, 1.900, Mode.LSB),
    B80("80m", 3.500, 4.000, 3.800, Mode.LSB),
    B60("60m", 5.330, 5.410, 5.357, Mode.USB),
    B40("40m", 7.000, 7.300, 7.200, Mode.LSB),
    B30("30m", 10.100, 10.150, 10.120, Mode.CW),
    B20("20m", 14.000, 14.350, 14.250, Mode.USB),
    B17("17m", 18.068, 18.168, 18.130, Mode.USB),
    B15("15m", 21.000, 21.450, 21.300, Mode.USB),
    B12("12m", 24.890, 24.990, 24.950, Mode.USB),
    B10("10m", 28.000, 29.700, 28.400, Mode.USB),
    B6("6m", 50.000, 54.000, 50.150, Mode.USB),
    B2("2m", 144.000, 148.000, 144.200, Mode.USB),
    ;

    operator fun contains(frequencyMHz: Double): Boolean =
        frequencyMHz >= lowMHz && frequencyMHz <= highMHz

    companion object {
        /** The band containing [frequencyMHz], or `null` outside every allocation. */
        fun of(frequencyMHz: Double): Band? = entries.firstOrNull { frequencyMHz in it }
    }
}

/**
 * A demodulation mode the radio accepts on a slice.
 *
 * [wire] is the token the `slice s <n> mode=` command expects. Modes the radio
 * supports but a phone-sized UI has no room for — SAM, DFM, the FreeDV waveform —
 * are deliberately absent; the radio keeps whatever it was set to by another
 * client and this app displays it as-is.
 */
enum class Mode(val wire: String, val label: String) {
    LSB("lsb", "LSB"),
    USB("usb", "USB"),
    CW("cw", "CW"),
    AM("am", "AM"),
    FM("fm", "FM"),
    DIGL("digl", "DIGL"),
    DIGU("digu", "DIGU"),
    ;

    /** True when this mode carries voice, and so can be driven by the microphone. */
    val isVoice: Boolean get() = this == LSB || this == USB || this == AM || this == FM

    companion object {
        /** Maps a radio-reported mode string onto an enum entry, ignoring case. */
        fun fromWire(value: String): Mode? =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) }
    }
}

/** Tuning increments offered on the radio screen, in hertz. */
enum class TuningStep(val hz: Int, val label: String) {
    HZ10(10, "10 Hz"),
    HZ100(100, "100 Hz"),
    HZ500(500, "500 Hz"),
    KHZ1(1_000, "1 kHz"),
    KHZ5(5_000, "5 kHz"),
    KHZ10(10_000, "10 kHz"),
}
