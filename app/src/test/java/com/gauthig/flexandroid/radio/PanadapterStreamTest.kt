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
import org.junit.Test

class PanadapterStreamTest {

    /**
     * Builds an FFT packet payload: the 12-byte sub-header followed by uint16 bins
     * that are Y pixel rows, not power values.
     */
    private fun fftPayload(
        startBin: Int,
        bins: IntArray,
        totalBins: Int,
        frameIndex: Long,
    ): ByteBuffer {
        val buf = ByteBuffer.allocate(12 + bins.size * 2).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(startBin.toShort())
        buf.putShort(bins.size.toShort())
        buf.putShort(2) // bin_size in bytes
        buf.putShort(totalBins.toShort())
        buf.putInt(frameIndex.toInt())
        bins.forEach { buf.putShort(it.toShort()) }
        buf.flip()
        return buf
    }

    @Test
    fun `converts pixel rows to dBm across the configured window`() {
        val stream = PanadapterStream()
        stream.configure(yPixels = 101, minDbm = -140f, maxDbm = -40f)

        // Row 0 is the top of the window, row 100 the bottom, row 50 the middle.
        val frame = stream.accept(fftPayload(0, intArrayOf(0, 50, 100), 3, 1))

        assertNotNull(frame)
        checkNotNull(frame)
        assertEquals(-40f, frame.bins[0], 0.01f)
        assertEquals(-90f, frame.bins[1], 0.01f)
        assertEquals(-140f, frame.bins[2], 0.01f)
    }

    @Test
    fun `assembles a frame that spans several packets`() {
        val stream = PanadapterStream()
        stream.configure(yPixels = 101, minDbm = -140f, maxDbm = -40f)

        assertNull(stream.accept(fftPayload(0, intArrayOf(0, 0), 6, 7)))
        assertNull(stream.accept(fftPayload(2, intArrayOf(50, 50), 6, 7)))

        val frame = stream.accept(fftPayload(4, intArrayOf(100, 100), 6, 7))
        assertNotNull(frame)
        checkNotNull(frame)
        assertEquals(6, frame.bins.size)
        assertEquals(7L, frame.frameIndex)
        assertEquals(-40f, frame.bins[0], 0.01f)
        assertEquals(-90f, frame.bins[3], 0.01f)
        assertEquals(-140f, frame.bins[5], 0.01f)
    }

    @Test
    fun `abandons a partial frame when a new frame index arrives`() {
        val stream = PanadapterStream()
        stream.configure(yPixels = 101, minDbm = -140f, maxDbm = -40f)

        // Half of frame 1 arrives and the rest is lost on the network.
        assertNull(stream.accept(fftPayload(0, intArrayOf(0, 0), 4, 1)))

        // Frame 2 starts. Completing it must not be contaminated by frame 1, and
        // must not require the two missing packets from it.
        assertNull(stream.accept(fftPayload(0, intArrayOf(100, 100), 4, 2)))
        val frame = stream.accept(fftPayload(2, intArrayOf(100, 100), 4, 2))

        assertNotNull(frame)
        checkNotNull(frame)
        assertEquals(2L, frame.frameIndex)
        frame.bins.forEach { assertEquals(-140f, it, 0.01f) }
    }

    @Test
    fun `rejects payloads that are malformed or claim an unsupported bin size`() {
        val stream = PanadapterStream()

        // Sub-header shorter than 12 bytes.
        assertNull(stream.accept(ByteBuffer.allocate(8)))

        // bin_size of 4 is a format this decoder has never seen; guessing at it
        // would silently render garbage.
        val badBinSize = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(0); putShort(2); putShort(4); putShort(2); putInt(1)
            flip()
        }
        assertNull(stream.accept(badBinSize))

        // start_bin + num_bins running past total_bins would overflow the frame.
        assertNull(stream.accept(fftPayload(3, intArrayOf(0, 0), 4, 1)))
    }
}
