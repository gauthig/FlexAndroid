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
package com.gauthig.flexandroid.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.gauthig.flexandroid.radio.PanadapterState
import com.gauthig.flexandroid.radio.SliceState
import com.gauthig.flexandroid.radio.SpectrumFrame
import com.gauthig.flexandroid.ui.theme.PanadapterColors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Draws the spectrum for slice A, with a grid, the slice marker and its passband.
 *
 * The trace is filled rather than left as a bare line: on a phone-sized display a
 * hairline against a dark background is hard to follow while the radio is being
 * tuned, and the fill reads as signal at a glance.
 *
 * Gestures map to the two things worth doing directly on a spectrum:
 * - a tap tunes the slice to the frequency under the finger
 * - a horizontal drag pans the window; a pinch changes its span
 *
 * @param onTune receives a frequency in MHz when the operator taps the spectrum
 * @param onPan receives a frequency delta in MHz to shift the window by
 * @param onZoom receives a multiplier to apply to the displayed bandwidth
 */
@Composable
fun PanadapterView(
    frame: SpectrumFrame?,
    pan: PanadapterState,
    slice: SliceState,
    transmitting: Boolean,
    contentDescription: String,
    onTune: (Double) -> Unit,
    onPan: (Double) -> Unit,
    onZoom: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = remember { TextStyle(color = PanadapterColors.GridText, fontSize = 9.sp) }

    Box(modifier = modifier.semantics { this.contentDescription = contentDescription }) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(pan.centerMHz, pan.bandwidthMHz) {
                    detectTapGestures { offset ->
                        val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                        onTune(pan.lowMHz + fraction * pan.bandwidthMHz)
                    }
                }
                .pointerInput(pan.centerMHz, pan.bandwidthMHz) {
                    detectTransformGestures { _, dragAmount, zoom, _ ->
                        if (abs(zoom - 1f) > ZOOM_DEADBAND) {
                            // Pinching apart shows less spectrum, so invert the ratio.
                            onZoom(1.0 / zoom)
                        }
                        if (dragAmount.x != 0f && size.width > 0) {
                            onPan(-dragAmount.x * pan.bandwidthMHz / size.width)
                        }
                    }
                },
        ) {
            drawGrid(pan, textMeasurer, labelStyle)
            frame?.let { drawTrace(it, pan) }
            drawSliceMarker(pan, slice, transmitting)
        }
    }
}

private fun DrawScope.drawGrid(
    pan: PanadapterState,
    textMeasurer: TextMeasurer,
    labelStyle: TextStyle,
) {
    // Horizontal dBm lines every 20 dB, labelled on the left.
    var dbm = (pan.maxDbm / DBM_GRID_STEP).toInt() * DBM_GRID_STEP
    while (dbm > pan.minDbm) {
        val y = dbmToY(dbm, pan, size.height)
        drawLine(
            color = PanadapterColors.Grid,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = GRID_STROKE_PX,
        )
        val label = textMeasurer.measure(dbm.toInt().toString(), labelStyle)
        drawText(
            textLayoutResult = label,
            topLeft = Offset(LABEL_INSET_PX, y - label.size.height.toFloat()),
        )
        dbm -= DBM_GRID_STEP
    }

    // Vertical frequency lines at round divisions of the visible span.
    val stepMHz = frequencyGridStep(pan.bandwidthMHz)
    var freq = ceil(pan.lowMHz / stepMHz) * stepMHz
    while (freq < pan.highMHz) {
        val x = ((freq - pan.lowMHz) / pan.bandwidthMHz * size.width).toFloat()
        drawLine(
            color = PanadapterColors.Grid,
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = GRID_STROKE_PX,
        )
        val label = textMeasurer.measure(formatGridFrequency(freq, stepMHz), labelStyle)
        drawText(
            textLayoutResult = label,
            topLeft = Offset(
                x - label.size.width / 2f,
                size.height - label.size.height.toFloat() - LABEL_INSET_PX,
            ),
        )
        freq += stepMHz
    }
}

private fun DrawScope.drawTrace(frame: SpectrumFrame, pan: PanadapterState) {
    val bins = frame.bins
    if (bins.isEmpty() || size.width <= 0f) return

    val outline = Path()
    val fill = Path()
    fill.moveTo(0f, size.height)

    // There are normally more bins than pixels, so each pixel column collapses to
    // the strongest bin in it. Peaks are what an operator is scanning for, and
    // averaging would erase a narrow CW signal entirely.
    val columns = size.width.roundToInt().coerceAtLeast(1)
    val binsPerColumn = bins.size.toFloat() / columns

    for (column in 0 until columns) {
        val start = (column * binsPerColumn).toInt().coerceIn(0, bins.size - 1)
        val end = ((column + 1) * binsPerColumn).toInt().coerceIn(start + 1, bins.size)
        var peak = bins[start]
        for (i in start until end) if (bins[i] > peak) peak = bins[i]

        val x = column.toFloat()
        val y = dbmToY(peak, pan, size.height)
        if (column == 0) outline.moveTo(x, y) else outline.lineTo(x, y)
        fill.lineTo(x, y)
    }

    fill.lineTo(size.width, size.height)
    fill.close()

    drawPath(path = fill, color = PanadapterColors.TraceFill)
    drawPath(path = outline, color = PanadapterColors.Trace, style = Stroke(width = TRACE_STROKE_PX))
}

private fun DrawScope.drawSliceMarker(
    pan: PanadapterState,
    slice: SliceState,
    transmitting: Boolean,
) {
    if (pan.bandwidthMHz <= 0.0 || size.width <= 0f) return
    val fraction = (slice.frequencyMHz - pan.lowMHz) / pan.bandwidthMHz
    if (fraction < -MARKER_MARGIN || fraction > 1.0 + MARKER_MARGIN) return

    val x = (fraction * size.width).toFloat()
    val markerColor: Color =
        if (transmitting) PanadapterColors.Transmitting else PanadapterColors.SliceMarker

    // Filter edges are offsets from the carrier, so drawing them relative to the
    // marker puts the passband on the correct side for LSB and USB alike, with no
    // mode-specific special casing.
    val hzPerPixel = pan.bandwidthMHz * HZ_PER_MHZ / size.width
    if (hzPerPixel > 0) {
        val left = x + slice.filterLowHz / hzPerPixel
        val right = x + slice.filterHighHz / hzPerPixel
        drawRect(
            color = PanadapterColors.PassBand,
            topLeft = Offset(minOf(left, right).toFloat(), 0f),
            size = Size(
                width = abs(right - left).toFloat().coerceAtLeast(1f),
                height = size.height,
            ),
        )
    }

    drawLine(
        color = markerColor,
        start = Offset(x, 0f),
        end = Offset(x, size.height),
        strokeWidth = MARKER_STROKE_PX,
    )
}

/** Maps a dBm value onto a Y coordinate, clamped to the drawing area. */
private fun dbmToY(dbm: Float, pan: PanadapterState, height: Float): Float {
    val span = (pan.maxDbm - pan.minDbm).takeIf { it != 0f } ?: 1f
    return ((pan.maxDbm - dbm) / span).coerceIn(0f, 1f) * height
}

/** Picks a frequency grid interval giving at most [MAX_GRID_DIVISIONS] divisions. */
private fun frequencyGridStep(bandwidthMHz: Double): Double {
    val candidates = doubleArrayOf(
        0.001, 0.002, 0.005, 0.010, 0.020, 0.050, 0.100, 0.200, 0.500, 1.0, 2.0,
    )
    return candidates.firstOrNull { bandwidthMHz / it <= MAX_GRID_DIVISIONS } ?: candidates.last()
}

/** Shows only as many decimals as the grid interval actually distinguishes. */
private fun formatGridFrequency(freqMHz: Double, stepMHz: Double): String = when {
    stepMHz >= 1.0 -> "%.0f".format(freqMHz)
    stepMHz >= 0.1 -> "%.1f".format(freqMHz)
    stepMHz >= 0.01 -> "%.2f".format(freqMHz)
    else -> "%.3f".format(freqMHz)
}

private const val DBM_GRID_STEP = 20f
private const val MAX_GRID_DIVISIONS = 8
private const val MARKER_MARGIN = 0.02
private const val ZOOM_DEADBAND = 0.01f
private const val HZ_PER_MHZ = 1_000_000.0
private const val TRACE_STROKE_PX = 2f
private const val MARKER_STROKE_PX = 2f
private const val LABEL_INSET_PX = 4f
private const val GRID_STROKE_PX = 1f
