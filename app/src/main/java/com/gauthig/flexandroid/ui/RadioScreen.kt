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

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gauthig.flexandroid.R
import com.gauthig.flexandroid.radio.Band
import com.gauthig.flexandroid.radio.InterlockState
import com.gauthig.flexandroid.radio.MeterRegistry
import com.gauthig.flexandroid.radio.Mode
import com.gauthig.flexandroid.radio.RadioSessionState
import com.gauthig.flexandroid.radio.SpectrumFrame
import com.gauthig.flexandroid.radio.TransmitState
import com.gauthig.flexandroid.radio.TuningStep
import com.gauthig.flexandroid.ui.theme.PanadapterColors
import kotlinx.coroutines.delay

/**
 * The operating screen: spectrum on top, controls below, PTT at the bottom.
 *
 * The layout is deliberately vertical and thumb-oriented. On a phone the operator
 * has one hand, and the controls that get touched while a contact is in progress —
 * tuning, PTT — are placed where a thumb reaches without regripping. Settings that
 * are adjusted once per session, like microphone level, are behind the controls
 * that are not.
 */
@Composable
fun RadioScreen(
    state: RadioSessionState,
    frame: SpectrumFrame?,
    micLevelProvider: () -> Float,
    onTune: (Double) -> Unit,
    onStep: (TuningStep, Boolean) -> Unit,
    onSelectBand: (Band) -> Unit,
    onSelectMode: (Mode) -> Unit,
    onZoom: (Double) -> Unit,
    onPan: (Double) -> Unit,
    onRfPower: (Int) -> Unit,
    onMicLevel: (Int) -> Unit,
    onAudioGain: (Float) -> Unit,
    onPtt: (Boolean) -> Unit,
    onTuneCarrier: (Boolean) -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by remember { mutableStateOf(TuningStep.KHZ1) }

    Column(modifier = modifier.fillMaxSize()) {
        RadioHeader(state = state, onDisconnect = onDisconnect)

        PanadapterView(
            frame = frame,
            pan = state.pan,
            slice = state.slice,
            transmitting = state.isTransmitting,
            contentDescription = stringResource(R.string.cd_panadapter),
            onTune = onTune,
            onPan = onPan,
            onZoom = onZoom,
            modifier = Modifier
                .fillMaxWidth()
                .height(PANADAPTER_HEIGHT_DP.dp)
                .background(MaterialTheme.colorScheme.background),
        )

        SMeter(signalDbm = state.signalDbm, transmit = state.transmit, transmitting = state.isTransmitting)

        FrequencyRow(
            frequencyMHz = state.slice.frequencyMHz,
            step = step,
            onStepChange = { step = it },
            onStep = { up -> onStep(step, up) },
        )

        BandRow(current = state.slice.band, onSelectBand = onSelectBand)
        ModeRow(current = state.slice.mode, onSelectMode = onSelectMode)

        Column(Modifier.padding(horizontal = 12.dp)) {
            LabelledSlider(
                label = stringResource(R.string.power),
                value = state.transmit.rfPowerPercent.toFloat(),
                valueText = "${state.transmit.rfPowerPercent}%",
                range = 0f..100f,
                onValueChange = { onRfPower(it.toInt()) },
            )
            LabelledSlider(
                label = stringResource(R.string.volume),
                icon = { Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null) },
                value = state.audioGain,
                valueText = "${(state.audioGain * 100).toInt()}%",
                range = 0f..1f,
                onValueChange = onAudioGain,
            )
            LabelledSlider(
                label = "Mic",
                icon = { Icon(Icons.Filled.Mic, contentDescription = null) },
                value = state.transmit.micLevel.toFloat(),
                valueText = "${state.transmit.micLevel}",
                range = 0f..100f,
                onValueChange = { onMicLevel(it.toInt()) },
            )
        }

        Spacer(Modifier.height(4.dp))
        MicLevelBar(transmitting = state.isTransmitting, levelProvider = micLevelProvider)

        state.microphoneError?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }

        Spacer(Modifier.height(8.dp))
        TransmitRow(
            state = state,
            onPtt = onPtt,
            onTuneCarrier = onTuneCarrier,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun RadioHeader(state: RadioSessionState, onDisconnect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                text = state.identity.nickname.ifBlank {
                    state.identity.model.ifBlank { "FlexRadio" }
                },
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = buildString {
                    append(if (state.identity.viaSmartLink) "SmartLink" else "Local network")
                    if (state.identity.serial.isNotBlank()) append("  ·  ${state.identity.serial}")
                    val interlock = state.transmit.interlock
                    if (interlock != InterlockState.UNKNOWN) append("  ·  ${interlock.name}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onDisconnect) { Text(stringResource(R.string.disconnect)) }
    }
}

/**
 * Signal strength while receiving, forward power and SWR while transmitting.
 *
 * One widget serves both because they are never both interesting at once, and the
 * space a phone has is better spent on the spectrum.
 */
@Composable
private fun SMeter(
    signalDbm: Float,
    transmit: TransmitState,
    transmitting: Boolean,
) {
    val meterDescription = stringResource(R.string.cd_s_meter)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            // Screen readers otherwise announce the bar and the two numbers as
            // three unrelated items; one label for the row reads as one meter.
            .semantics(mergeDescendants = true) { contentDescription = meterDescription },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (transmitting) {
            Text(
                text = "%.0f W".format(transmit.forwardPowerWatts),
                style = MaterialTheme.typography.titleMedium,
                color = PanadapterColors.Transmitting,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "SWR %.1f".format(transmit.swr),
                style = MaterialTheme.typography.bodyMedium,
                color = if (transmit.swr > SWR_WARNING) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val sUnits = MeterRegistry.dbmToSUnits(signalDbm)
            val over = MeterRegistry.dbOverS9(signalDbm)
            Text(
                text = if (over > 0f) "S9+%.0f".format(over) else "S%.0f".format(sUnits),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.width(72.dp),
            )
            LinearProgressIndicator(
                progress = { (sUnits / S_METER_FULL_SCALE).coerceIn(0f, 1f) },
                modifier = Modifier
                    .weight(1f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "%.0f dBm".format(signalDbm),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FrequencyRow(
    frequencyMHz: Double,
    step: TuningStep,
    onStepChange: (TuningStep) -> Unit,
    onStep: (Boolean) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            // Fixed-width digits so the readout does not shuffle while tuning.
            text = formatFrequency(frequencyMHz),
            style = MaterialTheme.typography.headlineMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 30.sp,
            ),
            modifier = Modifier.weight(1f),
        )

        Box {
            OutlinedButton(onClick = { menuOpen = true }) { Text(step.label) }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                TuningStep.entries.forEach { candidate ->
                    DropdownMenuItem(
                        text = { Text(candidate.label) },
                        onClick = {
                            onStepChange(candidate)
                            menuOpen = false
                        },
                    )
                }
            }
        }

        IconButton(onClick = { onStep(false) }) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Tune down")
        }
        IconButton(onClick = { onStep(true) }) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Tune up")
        }
    }
}

@Composable
private fun BandRow(current: Band?, onSelectBand: (Band) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Band.entries.forEach { band ->
            FilterChip(
                selected = band == current,
                onClick = { onSelectBand(band) },
                label = { Text(band.label) },
            )
        }
    }
}

@Composable
private fun ModeRow(current: Mode, onSelectMode: (Mode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Mode.entries.forEach { mode ->
            FilterChip(
                selected = mode == current,
                onClick = { onSelectMode(mode) },
                label = { Text(mode.label) },
            )
        }
    }
}

@Composable
private fun LabelledSlider(
    label: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    icon: (@Composable () -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            icon()
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(44.dp),
        )
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = valueText,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(44.dp),
        )
    }
}

/**
 * Live microphone level while transmitting.
 *
 * The level is read by polling rather than pushed through state: it updates at
 * audio-block rate, and routing that through the state flow would recompose the
 * whole screen dozens of times a second for one bar.
 */
@Composable
private fun MicLevelBar(transmitting: Boolean, levelProvider: () -> Float) {
    var level by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(transmitting) {
        while (transmitting) {
            level = levelProvider()
            delay(MIC_POLL_INTERVAL_MS)
        }
        level = 0f
    }

    LinearProgressIndicator(
        progress = { level.coerceIn(0f, 1f) },
        color = if (level > MIC_CLIP_THRESHOLD) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp)),
    )
}

/**
 * TUNE and PTT.
 *
 * PTT is press-and-hold rather than a toggle, and is wired to raw pointer events
 * instead of a click listener so that losing the gesture — a call arriving, the
 * finger sliding off — unkeys the transmitter. A latched toggle that survived the
 * app losing focus would leave a carrier on the air.
 */
@Composable
private fun TransmitRow(
    state: RadioSessionState,
    onPtt: (Boolean) -> Unit,
    onTuneCarrier: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    var tuning by remember { mutableStateOf(false) }

    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = {
                tuning = !tuning
                onTuneCarrier(tuning)
            },
            enabled = state.canTransmit && !state.isTransmitting,
            modifier = Modifier.size(width = 84.dp, height = PTT_HEIGHT_DP.dp),
        ) {
            Text(stringResource(R.string.tune))
        }

        Spacer(Modifier.width(12.dp))

        // A Box rather than a Button: Button's own clickable would consume the
        // press, and PTT needs the raw down/up edges, not a completed click.
        Box(
            modifier = Modifier
                .weight(1f)
                .height(PTT_HEIGHT_DP.dp)
                .alpha(if (state.canTransmit) 1f else DISABLED_ALPHA)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (state.isTransmitting) {
                        PanadapterColors.Transmitting
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                .pointerInput(state.canTransmit) {
                    if (!state.canTransmit) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onPtt(true)
                        try {
                            // Returns null when the gesture is cancelled — the
                            // finger sliding off, a call arriving, the window
                            // losing focus. Every one of those must unkey, which
                            // is why the release lives in `finally`.
                            waitForUpOrCancellation()
                        } finally {
                            onPtt(false)
                        }
                    }
                }
                .semanticsPtt(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (state.isTransmitting) {
                    stringResource(R.string.tx)
                } else {
                    stringResource(R.string.ptt)
                },
                style = MaterialTheme.typography.titleLarge,
                color = Color.Black,
            )
        }
    }
}

/** Labels the PTT surface for screen readers, which see no text on a press target. */
@Composable
private fun Modifier.semanticsPtt(): Modifier {
    val description = stringResource(R.string.cd_ptt)
    return semantics { contentDescription = description }
}

/** Renders a frequency as MHz with kHz grouping, e.g. `14.250.000`. */
private fun formatFrequency(frequencyMHz: Double): String {
    val hz = (frequencyMHz * 1_000_000.0).toLong().coerceAtLeast(0L)
    val mhz = hz / 1_000_000
    val khz = (hz % 1_000_000) / 1_000
    val remainder = hz % 1_000
    return "%d.%03d.%03d".format(mhz, khz, remainder)
}

private const val PANADAPTER_HEIGHT_DP = 220
private const val PTT_HEIGHT_DP = 64
private const val S_METER_FULL_SCALE = 9f
private const val SWR_WARNING = 2.0f
private const val MIC_POLL_INTERVAL_MS = 60L
private const val MIC_CLIP_THRESHOLD = 0.95f
private const val DISABLED_ALPHA = 0.4f
