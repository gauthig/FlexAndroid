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

/** One complete spectrum frame, in dBm, left-to-right across the panadapter. */
class SpectrumFrame(val bins: FloatArray, val frameIndex: Long)

/**
 * Reassembles panadapter FFT frames from VITA-49 packets and converts them to dBm.
 *
 * Two things make this more than a memcpy.
 *
 * **Frames span packets.** A 12-byte sub-header names the slice of the frame each
 * packet carries, and a frame is only complete once the received bin count
 * reaches `total_bins_in_frame`. Packets for a new frame index abandon whatever
 * was partially assembled, because the radio never resends.
 *
 * **The values are pixel rows, not power.** Despite being uint16 on the wire, each
 * sample is a Y coordinate in the display the radio thinks it is drawing: 0 is the
 * top of the window and `y_pixels - 1` the bottom. Recovering dBm therefore needs
 * `min_dbm`, `max_dbm` and `y_pixels` from the panadapter's own `display pan`
 * status — which is why [configure] must be called from the status handler before
 * the numbers mean anything.
 */
class PanadapterStream {

    private var yPixels: Int = DEFAULT_Y_PIXELS
    private var minDbm: Float = DEFAULT_MIN_DBM
    private var maxDbm: Float = DEFAULT_MAX_DBM

    private var assembling: FloatArray? = null
    private var assemblingIndex: Long = -1
    private var receivedBins: Int = 0

    /**
     * Applies the display geometry the radio reported for this panadapter.
     *
     * Called for every `display pan` status update; each parameter is optional
     * there, so unchanged values are left alone.
     */
    fun configure(yPixels: Int? = null, minDbm: Float? = null, maxDbm: Float? = null) {
        yPixels?.takeIf { it > 1 }?.let { this.yPixels = it }
        minDbm?.let { this.minDbm = it }
        maxDbm?.let { this.maxDbm = it }
    }

    /**
     * Feeds one FFT packet payload in.
     *
     * @return the frame once the last packet of it arrives, otherwise `null`
     */
    fun accept(payload: ByteBuffer): SpectrumFrame? {
        if (payload.remaining() < SUB_HEADER_BYTES) return null
        val buf = payload.duplicate()

        val startBin = buf.short.toInt() and 0xFFFF
        val numBins = buf.short.toInt() and 0xFFFF
        val binSize = buf.short.toInt() and 0xFFFF
        val totalBins = buf.short.toInt() and 0xFFFF
        val frameIndex = buf.int.toLong() and 0xFFFFFFFFL

        // bin_size is the width of one sample in bytes. Anything but 2 is a
        // format we have never seen and cannot safely guess at.
        if (binSize != BIN_SIZE_BYTES) return null
        if (totalBins == 0 || numBins == 0) return null
        if (startBin + numBins > totalBins) return null
        if (buf.remaining() < numBins * BIN_SIZE_BYTES) return null

        // A different frame index means the previous frame will never complete.
        var target = assembling
        if (target == null || assemblingIndex != frameIndex || target.size != totalBins) {
            target = FloatArray(totalBins)
            assembling = target
            assemblingIndex = frameIndex
            receivedBins = 0
        }

        val scale = (maxDbm - minDbm) / (yPixels - 1).toFloat()
        for (i in 0 until numBins) {
            val sample = buf.short.toInt() and 0xFFFF
            target[startBin + i] = maxDbm - sample * scale
        }
        receivedBins += numBins

        if (receivedBins < totalBins) return null

        assembling = null
        assemblingIndex = -1
        receivedBins = 0
        return SpectrumFrame(target, frameIndex)
    }

    /** Drops any partial frame. Called when the panadapter is reconfigured. */
    fun reset() {
        assembling = null
        assemblingIndex = -1
        receivedBins = 0
    }

    private companion object {
        /** start_bin, num_bins, bin_size, total_bins (uint16 each) + frame_index (uint32). */
        const val SUB_HEADER_BYTES = 12
        const val BIN_SIZE_BYTES = 2

        // Stand-ins until the radio's first `display pan` status lands. They match
        // SmartSDR's own defaults, so an early frame is plausible rather than wild.
        const val DEFAULT_Y_PIXELS = 200
        const val DEFAULT_MIN_DBM = -135f
        const val DEFAULT_MAX_DBM = -40f
    }
}
