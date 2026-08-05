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

import android.Manifest
import android.util.Log
import androidx.annotation.RequiresPermission
import com.gauthig.flexandroid.audio.RxAudioPlayer
import com.gauthig.flexandroid.audio.TxAudioCapture
import com.gauthig.flexandroid.audio.TxAudioUnavailableException
import com.gauthig.flexandroid.net.CertificatePinMismatch
import com.gauthig.flexandroid.net.FlexMessage
import com.gauthig.flexandroid.net.FlexMessageParser
import com.gauthig.flexandroid.net.PacketClass
import com.gauthig.flexandroid.net.RadioConnection
import com.gauthig.flexandroid.net.RadioTrustStore
import com.gauthig.flexandroid.net.VitaPacket
import com.gauthig.flexandroid.net.VitaTransport
import java.io.IOException
import kotlin.math.pow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** How a session reached its radio. */
sealed interface RadioTarget {
    /** A radio on the same network, addressed directly. */
    data class Lan(val host: String, val port: Int = RadioConnection.COMMAND_PORT) : RadioTarget

    /** A radio brokered by SmartLink, reached over TLS with a one-shot handle. */
    data class SmartLink(
        val host: String,
        val tlsPort: Int,
        val udpPort: Int,
        val wanHandle: String,
        val serial: String,
        val requiresHolePunch: Boolean,
    ) : RadioTarget
}

/**
 * Owns one connection to one radio and everything layered on it.
 *
 * The session is the only place that knows the order operations have to happen in,
 * and that order is not arbitrary:
 *
 * - `client gui` comes first, because the radio only lets a GUI client create a
 *   panadapter.
 * - `client udpport` comes before any stream is created, so the radio knows where
 *   to send before it has anything to send.
 * - For SmartLink, UDP hole punching happens before the streams start, so the NAT
 *   mapping already exists when the first spectrum packet is sent.
 * - Transmit setup points the radio's microphone input at the network before PTT is
 *   ever offered, so the first press cannot key a dead carrier.
 *
 * State is exposed as a single [RadioSessionState] and updated from both the TCP
 * status stream and the UDP data stream.
 */
class RadioSession(
    private val scope: CoroutineScope,
    private val clientId: String,
    private val trustStore: RadioTrustStore,
) {
    private val connection = RadioConnection(scope)
    private val transport = VitaTransport(scope)
    private val panadapter = PanadapterStream()
    private val meters = MeterRegistry()
    private val rxAudio = RxAudioPlayer()
    private val txAudio = TxAudioCapture(scope)

    private val _state = MutableStateFlow(RadioSessionState())
    val state: StateFlow<RadioSessionState> = _state

    private val _spectrum = MutableSharedFlow<SpectrumFrame>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** The latest complete spectrum frame. Replayed so a recompose is never blank. */
    val spectrum: SharedFlow<SpectrumFrame> = _spectrum

    private var rxAudioStreamId: Long = 0L
    private var txAudioStreamId: Long = 0L
    private var sliceIndex: Int = 0
    private var sMeterId: Int = -1
    private var forwardPowerMeterId: Int = -1
    private var swrMeterId: Int = -1

    private var statusJob: Job? = null
    private var keepAliveJob: Job? = null

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Connects to [target] and brings the session up to [ConnectionPhase.READY].
     *
     * Any failure leaves the session in [ConnectionPhase.FAILED] with the reason in
     * [RadioSessionState.error] and every resource released.
     */
    suspend fun open(target: RadioTarget) {
        try {
            _state.update { it.copy(phase = ConnectionPhase.CONNECTING, error = null) }

            // Bind UDP first: the port has to be known before the radio is told
            // about it, and for SmartLink it was already promised to the broker.
            when (target) {
                is RadioTarget.Lan -> transport.bind()
                is RadioTarget.SmartLink -> transport.bind(target.udpPort)
            }

            statusJob = scope.launch { collectStatus() }

            when (target) {
                is RadioTarget.Lan -> {
                    connection.connectDirect(target.host, target.port)
                    transport.setRemote(target.host, RadioConnection.VITA_PORT)
                }

                is RadioTarget.SmartLink -> {
                    connection.connectWan(
                        host = target.host,
                        tlsPort = target.tlsPort,
                        wanHandle = target.wanHandle,
                        trust = trustStore,
                    )
                    transport.setRemote(target.host, target.udpPort)
                    if (target.requiresHolePunch) transport.punchHole()
                    _state.update {
                        it.copy(identity = it.identity.copy(viaSmartLink = true, serial = target.serial))
                    }
                }
            }

            _state.update { it.copy(phase = ConnectionPhase.CONFIGURING, statusText = "Configuring…") }
            transport.start(::onVitaPacket)
            configure()

            keepAliveJob = scope.launch {
                while (isActive) {
                    delay(KEEPALIVE_INTERVAL_MS)
                    connection.sendAndForget("ping")
                }
            }

            _state.update { it.copy(phase = ConnectionPhase.READY, statusText = "") }
        } catch (e: CancellationException) {
            // The caller navigated away mid-connect; release and stay quiet.
            close()
            throw e
        } catch (e: CertificatePinMismatch) {
            // Not an error to display and forget: the operator has to decide
            // whether to trust the new certificate, so this propagates.
            close()
            _state.update { it.copy(phase = ConnectionPhase.IDLE) }
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "session open failed", e)
            close()
            _state.update {
                it.copy(
                    phase = ConnectionPhase.FAILED,
                    error = e.message ?: "Could not connect to the radio",
                )
            }
        }
    }

    /**
     * Runs the fixed bring-up sequence.
     *
     * Each step's failure is tolerated differently: a rejected subscription costs
     * a live reading, while a rejected panadapter costs the whole screen, so only
     * the latter aborts.
     */
    private suspend fun configure() {
        // Identify. A stable client_id is what makes the radio restore this
        // client's own slice and panadapter layout rather than treating each
        // launch as a new operator.
        connection.send("client gui $clientId")
        connection.send("client station $CLIENT_STATION")
        connection.send("client program $CLIENT_PROGRAM")
        connection.send("client udpport ${transport.localPort}")

        for (subscription in SUBSCRIPTIONS) {
            val reply = connection.send("sub $subscription")
            if (!reply.isSuccess) Log.d(TAG, "sub $subscription rejected: ${reply.code}")
        }

        // Meter definitions have to land before any meter packet can be scaled.
        connection.send("meter list").let { reply ->
            if (reply.isSuccess) meters.acceptDefinitions(reply.message)
        }

        createPanadapter()
        adoptOrCreateSlice()
        startReceiveAudio()
        prepareTransmit()
        resolveMeters()
    }

    private suspend fun createPanadapter() {
        // x/y are the pixel grid the radio bins into. The width is the number of
        // spectrum points we get; the height is the quantisation of the dBm axis,
        // because bins arrive as row indices into exactly this many pixels.
        val reply = connection.send("display pan create x=$PAN_X_PIXELS y=$PAN_Y_PIXELS")
        if (!reply.isSuccess) {
            throw IOException("radio refused to create a panadapter (0x%08X)".format(reply.code))
        }

        // "0x40000000,0x42000000" — panadapter stream, then its waterfall.
        val ids = reply.message.split(',').mapNotNull { FlexMessageParser.parseHex(it) }
        val panId = ids.firstOrNull()
            ?: throw IOException("panadapter reply did not contain a stream id")

        _state.update {
            it.copy(
                pan = it.pan.copy(
                    streamId = panId,
                    waterfallStreamId = ids.getOrElse(1) { 0L },
                    xPixels = PAN_X_PIXELS,
                    yPixels = PAN_Y_PIXELS,
                ),
            )
        }
        panadapter.configure(yPixels = PAN_Y_PIXELS)

        val pan = _state.value.pan
        connection.send(
            "display pan set 0x%08X center=%.6f bandwidth=%.6f min_dbm=%.1f max_dbm=%.1f fps=%d"
                .format(panId, pan.centerMHz, pan.bandwidthMHz, pan.minDbm, pan.maxDbm, pan.fps),
        )
    }

    /**
     * Uses slice 0 if the radio already has one, and creates it otherwise.
     *
     * Slice 0 is what SmartSDR labels "A". Adopting an existing slice matters
     * because another client may already own it — creating a second slice would
     * consume a licensed receiver for no reason.
     */
    private suspend fun adoptOrCreateSlice() {
        val list = connection.send("slice list")
        val existing = list.message.split(' ')
            .mapNotNull { it.trim().toIntOrNull() }
            .sorted()

        if (existing.contains(0)) {
            sliceIndex = 0
            _state.update { it.copy(slice = it.slice.copy(index = 0, exists = true)) }
        } else {
            val freq = _state.value.slice.frequencyMHz
            val mode = _state.value.slice.mode.wire
            val panId = _state.value.pan.streamId
            val reply = connection.send(
                "slice create freq=%.6f mode=%s pan=0x%08X".format(freq, mode, panId),
            )
            if (!reply.isSuccess) {
                throw IOException("radio refused to create a slice (0x%08X)".format(reply.code))
            }
            sliceIndex = reply.message.trim().toIntOrNull() ?: 0
            _state.update { it.copy(slice = it.slice.copy(index = sliceIndex, exists = true)) }
        }

        // Make slice A the selected one so front-panel and other-client actions
        // land where this UI is pointing.
        connection.send("slice set $sliceIndex active=1")
    }

    /**
     * Creates the receive audio stream and starts playback.
     *
     * `compression=none` is deliberate: it costs roughly 1.5 Mbit/s downstream but
     * needs no Opus codec, and Opus *encode* support is not guaranteed across the
     * Android device fleet. See docs/ARCHITECTURE.md for the bandwidth trade-off
     * and what enabling Opus would take.
     */
    private suspend fun startReceiveAudio() {
        val reply = connection.send("stream create type=remote_audio_rx compression=none")
        if (!reply.isSuccess) {
            Log.w(TAG, "receive audio stream refused: 0x%08X".format(reply.code))
            return
        }
        rxAudioStreamId = FlexMessageParser.parseHex(reply.message.trim()) ?: 0L
        connection.send("remote_audio rx_on on")
        rxAudio.gain = _state.value.audioGain
        rxAudio.start()
    }

    /**
     * Gets the radio ready to be keyed from this phone.
     *
     * Three things have to be true before PTT can work, and none of them are the
     * default: a transmit stream must exist, the radio must be told to accept
     * remote transmit audio, and its microphone input must be switched to the
     * network source. The last one is radio-wide state that other clients can see,
     * which is why it is done once here rather than on every PTT press.
     */
    private suspend fun prepareTransmit() {
        val reply = connection.send("stream create type=remote_audio_tx")
        if (!reply.isSuccess) {
            Log.w(TAG, "transmit audio stream refused: 0x%08X".format(reply.code))
            return
        }
        txAudioStreamId = FlexMessageParser.parseHex(reply.message.trim()) ?: 0L

        connection.send("remote_audio tx_audio on")
        // DAX would otherwise win as the transmit source over the remote stream.
        connection.send("transmit set dax=0")
        connection.send("mic input pc")
        connection.send("slice set $sliceIndex tx=1")

        _state.update { it.copy(transmitReady = txAudioStreamId != 0L) }
    }

    /** Looks up the meter IDs the UI displays, now that definitions have arrived. */
    private fun resolveMeters() {
        sMeterId = meters.sliceLevelMeter(sliceIndex)?.id ?: -1
        forwardPowerMeterId = meters.find("FWDPWR", "TX-")?.id ?: -1
        swrMeterId = meters.find("SWR", "TX-")?.id ?: -1
    }

    /** Tears the session down and releases the sockets and audio devices. */
    fun close() {
        keepAliveJob?.cancel()
        keepAliveJob = null
        statusJob?.cancel()
        statusJob = null
        txAudio.stop()
        rxAudio.stop()
        transport.close()
        connection.close()
        panadapter.reset()
        meters.clear()
        rxAudioStreamId = 0L
        txAudioStreamId = 0L
        _state.update {
            it.copy(phase = ConnectionPhase.IDLE, transmitReady = false, statusText = "")
        }
    }

    // ── Inbound: TCP status ──────────────────────────────────────────────────

    private suspend fun collectStatus() {
        connection.messages.collect { message ->
            when (message) {
                is FlexMessage.Status -> applyStatus(message)

                is FlexMessage.Version ->
                    _state.update { it.copy(identity = it.identity.copy(version = message.version)) }

                is FlexMessage.Notice ->
                    if (message.severity >= FlexMessage.Notice.Severity.ERROR) {
                        _state.update { it.copy(error = message.text) }
                    }

                is FlexMessage.Unknown ->
                    if (message.raw == RadioConnection.DISCONNECTED_SENTINEL) {
                        onDisconnected()
                    }

                else -> Unit
            }
        }
    }

    private fun applyStatus(status: FlexMessage.Status) {
        val tokens = status.obj.split(' ')
        when (tokens.firstOrNull()) {
            "slice" -> {
                val index = tokens.getOrNull(1)?.toIntOrNull() ?: return
                if (index == sliceIndex) applySliceFields(status.fields)
            }

            "display" -> {
                // "display pan 0x40000000"
                if (tokens.getOrNull(1) != "pan") return
                val streamId = tokens.getOrNull(2)?.let(FlexMessageParser::parseHex) ?: return
                if (streamId == _state.value.pan.streamId) applyPanFields(status.fields)
            }

            "interlock" -> applyInterlockFields(status.fields)

            "transmit" -> applyTransmitFields(status.fields)

            "radio" -> applyRadioFields(status.fields)

            // Meter definitions use '#' between pairs, so the generic field parser
            // cannot have split them. Re-read the raw line instead.
            "meter" -> MeterRegistry.meterPayloadOrNull(status.raw)
                ?.let { meters.acceptDefinitions(it); resolveMeters() }

            else -> Unit
        }
    }

    private fun applySliceFields(fields: Map<String, String>) {
        _state.update { current ->
            val slice = current.slice
            val modeRaw = fields["mode"] ?: slice.modeRaw
            current.copy(
                slice = slice.copy(
                    exists = fields["in_use"]?.let { it == "1" } ?: slice.exists,
                    frequencyMHz = fields["RF_frequency"]?.toDoubleOrNull() ?: slice.frequencyMHz,
                    mode = Mode.fromWire(modeRaw) ?: slice.mode,
                    modeRaw = modeRaw,
                    isTransmitSlice = fields["tx"]?.let { it == "1" } ?: slice.isTransmitSlice,
                    audioLevel = fields["audio_level"]?.toIntOrNull() ?: slice.audioLevel,
                    // Filter edges arrive in MHz on this object, unlike `filt`.
                    filterLowHz = fields["filter_lo"]?.toDoubleOrNull()
                        ?.let { (it * HZ_PER_MHZ).toInt() } ?: slice.filterLowHz,
                    filterHighHz = fields["filter_hi"]?.toDoubleOrNull()
                        ?.let { (it * HZ_PER_MHZ).toInt() } ?: slice.filterHighHz,
                    noiseReduction = fields["nr"]?.let { it == "1" } ?: slice.noiseReduction,
                    noiseBlanker = fields["nb"]?.let { it == "1" } ?: slice.noiseBlanker,
                ),
            )
        }
    }

    private fun applyPanFields(fields: Map<String, String>) {
        val yPixels = (fields["y_pixels"] ?: fields["y-pixels"])?.toFloatOrNull()?.toInt()
        val minDbm = fields["min_dbm"]?.toFloatOrNull()
        val maxDbm = fields["max_dbm"]?.toFloatOrNull()
        // The bin-to-dBm conversion depends on all three, so the decoder has to
        // learn about them at the same moment the state does.
        panadapter.configure(yPixels = yPixels, minDbm = minDbm, maxDbm = maxDbm)

        _state.update { current ->
            val pan = current.pan
            current.copy(
                pan = pan.copy(
                    centerMHz = fields["center"]?.toDoubleOrNull() ?: pan.centerMHz,
                    bandwidthMHz = fields["bandwidth"]?.toDoubleOrNull() ?: pan.bandwidthMHz,
                    minDbm = minDbm ?: pan.minDbm,
                    maxDbm = maxDbm ?: pan.maxDbm,
                    xPixels = (fields["x_pixels"] ?: fields["x-pixels"])
                        ?.toFloatOrNull()?.toInt() ?: pan.xPixels,
                    yPixels = yPixels ?: pan.yPixels,
                    fps = fields["fps"]?.toIntOrNull() ?: pan.fps,
                ),
            )
        }
    }

    private fun applyInterlockFields(fields: Map<String, String>) {
        val state = fields["state"]?.let(InterlockState::fromWire) ?: return
        _state.update {
            it.copy(
                transmit = it.transmit.copy(
                    interlock = state,
                    interlockReason = fields["reason"].orEmpty(),
                ),
            )
        }
    }

    private fun applyTransmitFields(fields: Map<String, String>) {
        _state.update { current ->
            val tx = current.transmit
            current.copy(
                transmit = tx.copy(
                    rfPowerPercent = fields["rfpower"]?.toFloatOrNull()?.toInt() ?: tx.rfPowerPercent,
                    tunePowerPercent = fields["tunepower"]?.toFloatOrNull()?.toInt()
                        ?: tx.tunePowerPercent,
                    micLevel = fields["mic_level"]?.toFloatOrNull()?.toInt()
                        ?: fields["miclevel"]?.toFloatOrNull()?.toInt() ?: tx.micLevel,
                    isTuning = fields["tune"]?.let { it == "1" } ?: tx.isTuning,
                ),
            )
        }
    }

    private fun applyRadioFields(fields: Map<String, String>) {
        _state.update { current ->
            current.copy(
                identity = current.identity.copy(
                    model = fields["model"] ?: current.identity.model,
                    nickname = (fields["nickname"] ?: fields["name"])?.replace('_', ' ')
                        ?: current.identity.nickname,
                    callsign = fields["callsign"] ?: current.identity.callsign,
                    serial = fields["serial"] ?: current.identity.serial,
                ),
            )
        }
    }

    private fun onDisconnected() {
        if (_state.value.phase == ConnectionPhase.IDLE) return
        _state.update {
            it.copy(
                phase = ConnectionPhase.FAILED,
                error = it.error ?: "The radio closed the connection.",
            )
        }
    }

    // ── Inbound: UDP data ────────────────────────────────────────────────────

    /**
     * Dispatches one VITA-49 packet.
     *
     * Runs on the UDP reader thread. Everything here is either a bounded decode or
     * a non-blocking hand-off, because a stall here drops spectrum and audio alike.
     */
    private fun onVitaPacket(packet: VitaPacket) {
        when (packet.packetClass) {
            PacketClass.FFT -> {
                if (packet.streamId != _state.value.pan.streamId) return
                panadapter.accept(packet.payload)?.let { _spectrum.tryEmit(it) }
            }

            PacketClass.METER -> applyMeterValues(meters.decode(packet.payload))

            PacketClass.AUDIO_FLOAT32 -> {
                if (rxAudioStreamId != 0L && packet.streamId != rxAudioStreamId) return
                rxAudio.playFloat32Stereo(packet.payload)
            }

            PacketClass.AUDIO_INT16_MONO -> {
                if (rxAudioStreamId != 0L && packet.streamId != rxAudioStreamId) return
                rxAudio.playInt16Mono(packet.payload)
            }

            PacketClass.AUDIO_OPUS -> logOpusOnce()

            // Waterfall tiles are requested implicitly with the panadapter but the
            // phone UI does not draw them, so they are dropped without comment.
            PacketClass.WATERFALL -> Unit

            else -> Unit
        }
    }

    private fun applyMeterValues(values: Map<Int, Float>) {
        if (values.isEmpty()) return
        val signal = values[sMeterId]
        val forward = values[forwardPowerMeterId]
        val swr = values[swrMeterId]
        if (signal == null && forward == null && swr == null) return

        _state.update { current ->
            current.copy(
                signalDbm = signal ?: current.signalDbm,
                transmit = current.transmit.copy(
                    // FWDPWR is reported in dBm; hams read watts.
                    forwardPowerWatts = forward?.let { dbmToWatts(it) }
                        ?: current.transmit.forwardPowerWatts,
                    swr = swr ?: current.transmit.swr,
                ),
            )
        }
    }

    private var opusWarned = false

    private fun logOpusOnce() {
        if (opusWarned) return
        opusWarned = true
        Log.w(TAG, "radio is sending Opus audio; this build requests uncompressed only")
    }

    // ── Outbound: operator actions ───────────────────────────────────────────

    /** Tunes slice A, clamping to the containing band when there is one. */
    fun tuneTo(frequencyMHz: Double) {
        val band = Band.of(frequencyMHz)
        val clamped = band?.let { frequencyMHz.coerceIn(it.lowMHz, it.highMHz) } ?: frequencyMHz
        // Reflect the change immediately; the radio's status will confirm it.
        _state.update { it.copy(slice = it.slice.copy(frequencyMHz = clamped)) }
        connection.sendAndForget("slice tune $sliceIndex %.6f autopan=1".format(clamped))
    }

    /** Nudges slice A by one [step], in the given direction. */
    fun stepFrequency(step: TuningStep, up: Boolean) {
        val delta = step.hz / HZ_PER_MHZ * (if (up) 1 else -1)
        tuneTo(_state.value.slice.frequencyMHz + delta)
    }

    fun setMode(mode: Mode) {
        _state.update { it.copy(slice = it.slice.copy(mode = mode, modeRaw = mode.wire)) }
        connection.sendAndForget("slice set $sliceIndex mode=${mode.wire}")
    }

    /**
     * Moves to [band]: its calling frequency, its usual mode, and a panadapter
     * window centred there.
     */
    fun selectBand(band: Band) {
        setMode(band.defaultMode)
        tuneTo(band.defaultFrequencyMHz)
        setPanCenter(band.defaultFrequencyMHz)
    }

    fun setPanCenter(centerMHz: Double) {
        val panId = _state.value.pan.streamId
        if (panId == 0L) return
        _state.update { it.copy(pan = it.pan.copy(centerMHz = centerMHz)) }
        connection.sendAndForget("display pan set 0x%08X center=%.6f".format(panId, centerMHz))
    }

    fun setPanBandwidth(bandwidthMHz: Double) {
        val panId = _state.value.pan.streamId
        if (panId == 0L) return
        val clamped = bandwidthMHz.coerceIn(MIN_PAN_BANDWIDTH_MHZ, MAX_PAN_BANDWIDTH_MHZ)
        panadapter.reset()
        _state.update { it.copy(pan = it.pan.copy(bandwidthMHz = clamped)) }
        connection.sendAndForget("display pan set 0x%08X bandwidth=%.6f".format(panId, clamped))
    }

    fun setRfPower(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        _state.update { it.copy(transmit = it.transmit.copy(rfPowerPercent = clamped)) }
        connection.sendAndForget("transmit set rfpower=$clamped")
    }

    fun setMicLevel(level: Int) {
        val clamped = level.coerceIn(0, 100)
        _state.update { it.copy(transmit = it.transmit.copy(micLevel = clamped)) }
        connection.sendAndForget("transmit set miclevel=$clamped")
    }

    /** Sets local playback gain. Does not touch the radio's own audio level. */
    fun setAudioGain(gain: Float) {
        val clamped = gain.coerceIn(0f, 1f)
        rxAudio.gain = clamped
        _state.update { it.copy(audioGain = clamped) }
    }

    /** Runs the transmitter at tune power, for adjusting an antenna or amplifier. */
    fun setTune(on: Boolean) {
        connection.sendAndForget("transmit tune ${if (on) "on" else "off"}")
    }

    /**
     * Keys or unkeys the transmitter, starting and stopping microphone capture.
     *
     * Ordering matters in both directions. On key-down the audio stream starts
     * first so samples are already flowing when the PA comes up, avoiding a
     * clipped first syllable. On key-up `xmit 0` goes first so the carrier drops
     * even if stopping capture is slow.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun setPtt(keyed: Boolean) {
        if (keyed) {
            if (!_state.value.canTransmit) return
            try {
                txAudio.start { payload, length -> sendTxAudio(payload, length) }
            } catch (e: TxAudioUnavailableException) {
                Log.w(TAG, "cannot open microphone", e)
                _state.update { it.copy(microphoneError = e.message) }
                return
            }
            connection.sendAndForget("xmit 1")
        } else {
            connection.sendAndForget("xmit 0")
            txAudio.stop()
        }
    }

    private fun sendTxAudio(payload: ByteArray, length: Int) {
        val streamId = txAudioStreamId
        if (streamId == 0L) return
        transport.send(streamId, PacketClass.AUDIO_FLOAT32, payload, length)
    }

    /** Peak microphone level from the last captured block, 0..1. */
    val micPeakLevel: Float get() = txAudio.peakLevel

    /** Peak receive audio level from the last played block, 0..1. */
    val rxPeakLevel: Float get() = rxAudio.peakLevel

    /** Clears a latched microphone error so PTT can be retried. */
    fun clearMicrophoneError() {
        _state.update { it.copy(microphoneError = null) }
    }

    private companion object {
        const val TAG = "RadioSession"

        const val CLIENT_STATION = "FlexAndroid"
        const val CLIENT_PROGRAM = "FlexAndroid"

        /**
         * Panadapter grid.
         *
         * 512 columns is more than a phone has pixels, which leaves headroom for
         * pinch-zoom without re-asking the radio. 200 rows sets the dBm
         * quantisation: bins arrive as row indices, so this is the vertical
         * resolution of the trace.
         */
        const val PAN_X_PIXELS = 512
        const val PAN_Y_PIXELS = 200

        const val MIN_PAN_BANDWIDTH_MHZ = 0.005
        const val MAX_PAN_BANDWIDTH_MHZ = 7.0

        const val HZ_PER_MHZ = 1_000_000.0
        const val KEEPALIVE_INTERVAL_MS = 5_000L

        val SUBSCRIPTIONS = listOf(
            "slice all",
            "pan all",
            "tx all",
            "radio all",
            "meter all",
            "audio_stream all",
        )

        /** dBm to watts, for the forward power meter. */
        fun dbmToWatts(dbm: Float): Float = 10f.pow((dbm - DBM_PER_WATT_OFFSET) / 10f)

        /** 0 dBm is 1 mW, so 1 W sits at +30 dBm. */
        const val DBM_PER_WATT_OFFSET = 30f
    }
}
