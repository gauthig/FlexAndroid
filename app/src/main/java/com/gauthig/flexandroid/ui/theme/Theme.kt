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
package com.gauthig.flexandroid.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * A fixed dark scheme.
 *
 * Radio work happens at night and in the dark corners of field days, and a
 * spectrum trace needs a black background to be readable. Following the system
 * light theme would make the panadapter unusable, so this app does not offer one.
 */
private val FlexColors = darkColorScheme(
    primary = Color(0xFF4FD1C5),
    onPrimary = Color(0xFF00201C),
    primaryContainer = Color(0xFF00504A),
    onPrimaryContainer = Color(0xFF71F7EA),
    secondary = Color(0xFFF2A03D),
    onSecondary = Color(0xFF3F2200),
    tertiary = Color(0xFF9FCAFF),
    onTertiary = Color(0xFF00325B),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE2E6EA),
    surface = Color(0xFF11161D),
    onSurface = Color(0xFFE2E6EA),
    surfaceVariant = Color(0xFF1B2530),
    onSurfaceVariant = Color(0xFFA9B4C0),
    outline = Color(0xFF35485C),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3B0000),
)

/** Palette entries the panadapter draws with, kept out of the Material scheme. */
object PanadapterColors {
    val Trace = Color(0xFF4FD1C5)
    val TraceFill = Color(0x334FD1C5)
    val Grid = Color(0xFF1E2A36)
    val GridText = Color(0xFF7C8B9A)
    val SliceMarker = Color(0xFFF2A03D)
    val PassBand = Color(0x26F2A03D)
    val Transmitting = Color(0xFFFF6B6B)
}

@Composable
fun FlexAndroidTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FlexColors,
        content = content,
    )
}
