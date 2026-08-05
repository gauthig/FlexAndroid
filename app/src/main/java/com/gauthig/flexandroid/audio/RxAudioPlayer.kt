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
package com.gauthig.flexandroid.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Plays the radio's receive audio through whatever output the phone is using —
 * speaker, wired headset, or a Bluetooth headset the system has routed to.
 *
 * The radio sends 24 kHz stereo. Playback is opened at that rate when the device
 * accepts it and at 48 kHz otherwise, in which case samples are duplicated on the
 * way out. That is a crude resampler, but the source is 24 kHz voice-bandwidth
 * audio and integer-ratio duplication introduces no pitch error — only some HF
 * image content well above anything the radio passed.
 *
 * Volume is applied here rather than on the radio so that changing it does not
 * fight with another client connected to the same slice.
 */
class RxAudioPlayer {

    private var track: AudioTrack? = null
    private var duplicateSamples = false

    /** Linear gain applied to every sample. 1.0 is the radio's own level. */
    @Volatile
    var gain: Float = 1.0f

    /** Peak absolute sample of the most recent buffer, for a level indicator. */
    @Volatile
    var peakLevel: Float = 0f
        private set

    private var scratch = FloatArray(INITIAL_SCRATCH_SAMPLES)

    /** Opens the output device. Safe to call again; a second call is a no-op. */
    fun start() {
        if (track != null) return

        val (sampleRate, duplicate) = if (isRateSupported(RADIO_SAMPLE_RATE)) {
            RADIO_SAMPLE_RATE to false
        } else {
            FALLBACK_SAMPLE_RATE to true
        }
        duplicateSamples = duplicate

        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        if (minBytes <= 0) {
            Log.w(TAG, "no usable output configuration at $sampleRate Hz")
            return
        }

        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // VOICE_COMMUNICATION so the system routes RX to a headset
                    // and applies the in-call volume stream the operator expects.
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            // A few packets of slack absorbs network jitter without adding
            // latency an operator would notice while working a contact.
            .setBufferSizeInBytes(minBytes * BUFFER_MULTIPLIER)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
            .also { it.play() }
    }

    /**
     * Decodes and plays an uncompressed audio payload.
     *
     * Handles both shapes the radio emits on the uncompressed paths: big-endian
     * float32 stereo (`0x03E3`) and big-endian int16 mono (`0x0123`).
     */
    fun playFloat32Stereo(payload: ByteBuffer) {
        val frames = payload.remaining() / (BYTES_PER_FLOAT * CHANNELS)
        if (frames == 0) return
        val samples = ensureScratch(frames * CHANNELS * if (duplicateSamples) 2 else 1)

        val buf = payload.duplicate()
        var peak = 0f
        var out = 0
        val g = gain
        repeat(frames) {
            val left = buf.float * g
            val right = buf.float * g
            peak = maxOf(peak, abs(left), abs(right))
            samples[out++] = left
            samples[out++] = right
            if (duplicateSamples) {
                samples[out++] = left
                samples[out++] = right
            }
        }
        peakLevel = peak
        write(samples, out)
    }

    /** Decodes and plays a big-endian int16 mono payload, duplicated to stereo. */
    fun playInt16Mono(payload: ByteBuffer) {
        val frames = payload.remaining() / BYTES_PER_SHORT
        if (frames == 0) return
        val samples = ensureScratch(frames * CHANNELS * if (duplicateSamples) 2 else 1)

        val buf = payload.duplicate()
        var peak = 0f
        var out = 0
        val g = gain
        repeat(frames) {
            val value = (buf.short / SHORT_FULL_SCALE) * g
            peak = maxOf(peak, abs(value))
            samples[out++] = value
            samples[out++] = value
            if (duplicateSamples) {
                samples[out++] = value
                samples[out++] = value
            }
        }
        peakLevel = peak
        write(samples, out)
    }

    private fun write(samples: FloatArray, count: Int) {
        val t = track ?: return
        // WRITE_NON_BLOCKING: dropping a buffer when the sink is full keeps the
        // UDP reader thread moving. Blocking here would stall spectrum too.
        t.write(samples, 0, count, AudioTrack.WRITE_NON_BLOCKING)
    }

    private fun ensureScratch(size: Int): FloatArray {
        if (scratch.size < size) scratch = FloatArray(size)
        return scratch
    }

    fun stop() {
        track?.let { t ->
            runCatching { t.pause() }
            runCatching { t.flush() }
            runCatching { t.stop() }
            t.release()
        }
        track = null
        peakLevel = 0f
    }

    private fun isRateSupported(rate: Int): Boolean =
        AudioTrack.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_FLOAT,
        ) > 0

    private companion object {
        const val TAG = "RxAudioPlayer"

        /** The rate FlexRadio streams remote audio at. Not configurable. */
        const val RADIO_SAMPLE_RATE = 24_000
        const val FALLBACK_SAMPLE_RATE = 48_000

        const val CHANNELS = 2
        const val BYTES_PER_FLOAT = 4
        const val BYTES_PER_SHORT = 2
        const val SHORT_FULL_SCALE = 32_768f
        const val BUFFER_MULTIPLIER = 4
        const val INITIAL_SCRATCH_SAMPLES = 8_192
    }
}
