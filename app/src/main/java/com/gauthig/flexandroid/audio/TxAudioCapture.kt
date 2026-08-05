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

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Raised when no microphone configuration on this device can feed the radio. */
class TxAudioUnavailableException(message: String) : IllegalStateException(message)

/**
 * Captures microphone audio and hands it out as radio-ready payloads.
 *
 * The radio wants 24 kHz stereo. The microphone gives 16-bit mono at whichever
 * rate the device supports, so this class opens the best available rate,
 * decimates 48 kHz down by two when 24 kHz is not offered, and duplicates mono to
 * both channels. Voice is band-limited well below 12 kHz, so dropping alternate
 * samples after the hardware's own anti-alias filtering is adequate — and unlike a
 * fractional resampler it introduces no timing drift over a long transmission.
 *
 * Frames are emitted in fixed [FRAMES_PER_PACKET] chunks so each one fits a
 * single VITA-49 datagram, matching the cadence FlexRadio's own clients use.
 *
 * Capture uses [MediaRecorder.AudioSource.VOICE_COMMUNICATION], which enables the
 * platform's echo cancellation and noise suppression. On a phone held near a
 * speaker playing receive audio, that is the difference between usable and not.
 */
class TxAudioCapture(private val scope: CoroutineScope) {

    private var record: AudioRecord? = null
    private var job: Job? = null
    private var decimate = false

    /** Linear gain applied before packetisation. Radio-side mic gain is separate. */
    @Volatile
    var gain: Float = 1.0f

    /** Peak absolute sample of the last captured block, for a mic level indicator. */
    @Volatile
    var peakLevel: Float = 0f
        private set

    /**
     * Opens the microphone and starts producing payloads.
     *
     * [onPayload] is called on a capture thread with a big-endian float32 stereo
     * buffer ready to be framed as packet class `0x03E3`. The array is reused
     * between calls, so the callback must send or copy before returning.
     *
     * @throws TxAudioUnavailableException if no supported capture rate exists
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(onPayload: (ByteArray, Int) -> Unit) {
        if (record != null) return

        val rate = pickSampleRate()
            ?: throw TxAudioUnavailableException("device offers no usable microphone rate")
        decimate = rate != RADIO_SAMPLE_RATE

        val minBytes = AudioRecord.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBytes <= 0) {
            throw TxAudioUnavailableException("microphone rejected $rate Hz mono")
        }

        val recorder = buildRecord(rate, minBytes * BUFFER_MULTIPLIER)
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw TxAudioUnavailableException("microphone could not be initialised")
        }
        record = recorder
        recorder.startRecording()

        // Samples read per callback: one packet's worth, doubled when decimating.
        val readSamples = FRAMES_PER_PACKET * if (decimate) 2 else 1
        val pcm = ShortArray(readSamples)
        val payload = ByteArray(FRAMES_PER_PACKET * CHANNELS * BYTES_PER_FLOAT)
        val out = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)

        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val read = recorder.read(pcm, 0, readSamples)
                if (read <= 0) {
                    if (read < 0) Log.w(TAG, "microphone read error: $read")
                    continue
                }
                val frames = encode(pcm, read, out)
                if (frames > 0) onPayload(payload, frames * CHANNELS * BYTES_PER_FLOAT)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun buildRecord(rate: Int, bufferBytes: Int): AudioRecord =
        AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .build()

    /**
     * Converts captured mono PCM into the radio's big-endian float32 stereo layout.
     *
     * @return the number of stereo frames written to [out]
     */
    private fun encode(pcm: ShortArray, read: Int, out: ByteBuffer): Int {
        out.clear()
        val g = gain
        var peak = 0f
        var frames = 0
        var i = 0
        val stride = if (decimate) 2 else 1
        while (i < read && out.remaining() >= CHANNELS * BYTES_PER_FLOAT) {
            val sample = ((pcm[i] / SHORT_FULL_SCALE) * g).coerceIn(-1f, 1f)
            peak = maxOf(peak, abs(sample))
            out.putFloat(sample)
            out.putFloat(sample)
            frames++
            i += stride
        }
        peakLevel = peak
        return frames
    }

    fun stop() {
        job?.cancel()
        job = null
        record?.let { r ->
            runCatching { r.stop() }
            r.release()
        }
        record = null
        peakLevel = 0f
    }

    /** Prefers the radio's native rate; falls back to a 2:1 decimation from 48 kHz. */
    private fun pickSampleRate(): Int? = CANDIDATE_RATES.firstOrNull { rate ->
        AudioRecord.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ) > 0
    }

    companion object {
        private const val TAG = "TxAudioCapture"

        /** The rate FlexRadio expects for remote transmit audio. */
        const val RADIO_SAMPLE_RATE = 24_000

        /**
         * Stereo frames per VITA-49 packet.
         *
         * 128 frames is ~5.3 ms at 24 kHz and yields a 1024-byte payload, so a
         * packet stays far below any path MTU while keeping PTT latency low.
         */
        const val FRAMES_PER_PACKET = 128

        private val CANDIDATE_RATES = intArrayOf(RADIO_SAMPLE_RATE, 48_000).toList()
        private const val CHANNELS = 2
        private const val BYTES_PER_FLOAT = 4
        private const val SHORT_FULL_SCALE = 32_768f
        private const val BUFFER_MULTIPLIER = 4
    }
}
