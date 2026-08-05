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

/** Where a session is in its lifecycle. */
enum class ConnectionPhase {
    IDLE,
    AUTHENTICATING,
    BROKERING,
    CONNECTING,
    CONFIGURING,
    READY,
    FAILED,
}

/**
 * The radio's interlock state machine, as reported by `interlock` status.
 *
 * PTT is only honoured from [READY]; the rest are either transient or a reason the
 * radio is refusing to key, which the UI surfaces rather than silently swallowing.
 */
enum class InterlockState {
    NOT_READY,
    RECEIVE,
    READY,
    PTT_REQUESTED,
    TRANSMITTING,
    UNKEY_REQUESTED,
    TIMEOUT,
    TX_FAULT,
    UNKNOWN,
    ;

    val isTransmitting: Boolean get() = this == TRANSMITTING || this == PTT_REQUESTED

    companion object {
        fun fromWire(value: String): InterlockState =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: UNKNOWN
    }
}

/** Everything about slice A that the phone UI shows or changes. */
data class SliceState(
    val index: Int = 0,
    val exists: Boolean = false,
    val frequencyMHz: Double = 14.250,
    val mode: Mode = Mode.USB,
    val modeRaw: String = Mode.USB.wire,
    val isTransmitSlice: Boolean = false,
    val audioLevel: Int = 50,
    val filterLowHz: Int = 100,
    val filterHighHz: Int = 2_800,
    val noiseReduction: Boolean = false,
    val noiseBlanker: Boolean = false,
) {
    /** The band this slice sits in, or `null` outside an amateur allocation. */
    val band: Band? get() = Band.of(frequencyMHz)
}

/** Geometry of the panadapter the app asked the radio to render for it. */
data class PanadapterState(
    val streamId: Long = 0L,
    val waterfallStreamId: Long = 0L,
    val centerMHz: Double = 14.250,
    val bandwidthMHz: Double = 0.200,
    val minDbm: Float = -135f,
    val maxDbm: Float = -40f,
    val xPixels: Int = 512,
    val yPixels: Int = 200,
    val fps: Int = 15,
) {
    val lowMHz: Double get() = centerMHz - bandwidthMHz / 2
    val highMHz: Double get() = centerMHz + bandwidthMHz / 2
}

/** Transmitter settings and live TX metering. */
data class TransmitState(
    val rfPowerPercent: Int = 25,
    val tunePowerPercent: Int = 10,
    val micLevel: Int = 50,
    val isTuning: Boolean = false,
    val interlock: InterlockState = InterlockState.UNKNOWN,
    val interlockReason: String = "",
    val forwardPowerWatts: Float = 0f,
    val swr: Float = 1f,
)

/** Identity of the connected radio, for the title bar. */
data class RadioIdentity(
    val model: String = "",
    val serial: String = "",
    val nickname: String = "",
    val callsign: String = "",
    val version: String = "",
    val viaSmartLink: Boolean = false,
)

/**
 * The complete observable state of a radio session.
 *
 * Held as one immutable value so the UI recomposes from a single source and can
 * never show a half-applied change — for example a new band's frequency against
 * the previous band's panadapter edges.
 */
data class RadioSessionState(
    val phase: ConnectionPhase = ConnectionPhase.IDLE,
    val statusText: String = "",
    val error: String? = null,
    val identity: RadioIdentity = RadioIdentity(),
    val slice: SliceState = SliceState(),
    val pan: PanadapterState = PanadapterState(),
    val transmit: TransmitState = TransmitState(),
    /** Passband signal strength in dBm, from the slice's LEVEL meter. */
    val signalDbm: Float = -130f,
    /** Local playback gain, 0..1. Applied on the phone, not in the radio. */
    val audioGain: Float = 0.7f,
    /** True once a TX audio stream exists and the mic source is pointed at us. */
    val transmitReady: Boolean = false,
    /** Set when the microphone could not be opened, so PTT is disabled. */
    val microphoneError: String? = null,
) {
    val canTransmit: Boolean
        get() = phase == ConnectionPhase.READY &&
            transmitReady &&
            microphoneError == null &&
            transmit.interlock != InterlockState.NOT_READY &&
            transmit.interlock != InterlockState.TX_FAULT

    val isTransmitting: Boolean get() = transmit.interlock.isTransmitting
}
