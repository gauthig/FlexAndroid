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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.gauthig.flexandroid.R
import com.gauthig.flexandroid.net.WanRadio

/**
 * Sign-in, with a direct-IP escape hatch.
 *
 * SmartLink is the intended path, but a radio on the same network can be reached
 * without going through FlexRadio's servers at all — useful in the shack, and the
 * only way to use the app if the broker is unreachable.
 */
@Composable
fun SignInScreen(
    state: ConnectUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onStaySignedInChange: (Boolean) -> Unit,
    onSignIn: () -> Unit,
    onToggleDirect: () -> Unit,
    onLanHostChange: (String) -> Unit,
    onConnectLan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.smartlink_sign_in),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.smartlink_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = state.email,
            onValueChange = onEmailChange,
            label = { Text(stringResource(R.string.email)) },
            singleLine = true,
            enabled = !state.busy,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = state.password,
            onValueChange = onPasswordChange,
            label = { Text(stringResource(R.string.password)) },
            singleLine = true,
            enabled = !state.busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = state.staySignedIn,
                onCheckedChange = onStaySignedInChange,
                enabled = !state.busy,
            )
            Text(
                text = stringResource(R.string.remember_me),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = onSignIn,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    if (state.busy) R.string.signing_in else R.string.sign_in,
                ),
            )
        }

        if (state.busy) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        state.error?.let { error ->
            Spacer(Modifier.height(16.dp))
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        TextButton(onClick = onToggleDirect) {
            Text(stringResource(R.string.direct_lan))
        }

        if (state.showDirectEntry) {
            OutlinedTextField(
                value = state.lanHost,
                onValueChange = onLanHostChange,
                label = { Text(stringResource(R.string.radio_ip)) },
                singleLine = true,
                enabled = !state.busy,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onConnectLan,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.connect))
            }
        }
    }
}

/** The account's radios, with their availability made explicit. */
@Composable
fun RadioPickerScreen(
    state: ConnectUiState,
    onConnect: (WanRadio) -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.your_radios),
                style = MaterialTheme.typography.headlineSmall,
            )
            TextButton(onClick = onSignOut) { Text(stringResource(R.string.sign_out)) }
        }

        state.message?.let { message ->
            Spacer(Modifier.height(8.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }

        state.error?.let { error ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.height(12.dp))

        if (state.radios.isEmpty()) {
            Text(
                text = stringResource(R.string.no_radios),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.radios, key = { it.serial }) { radio ->
                    RadioCard(radio = radio, enabled = !state.busy, onConnect = onConnect)
                }
            }
        }
    }
}

@Composable
private fun RadioCard(radio: WanRadio, enabled: Boolean, onConnect: (WanRadio) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(radio.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                text = buildString {
                    append(radio.model)
                    if (radio.callsign.isNotBlank()) append("  ·  ${radio.callsign}")
                    append("  ·  ${radio.status}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (radio.inUseBy.isNotBlank()) {
                Text(
                    text = "In use by ${radio.inUseBy}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            if (radio.requiresHolePunch) {
                Text(
                    // Worth saying out loud: this is the connection type that fails
                    // on restrictive carrier NATs, and knowing that up front saves
                    // an operator from blaming the app.
                    text = "No forwarded ports — will try UDP hole punching",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onConnect(radio) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.connect))
            }
        }
    }
}

/**
 * Asks the operator what to do about a radio whose certificate changed.
 *
 * Both fingerprints are shown in full. A factory reset legitimately produces a new
 * certificate, and the only way to tell that apart from interception is for a human
 * who knows what they did to the radio to look at it.
 */
@Composable
fun CertificateMismatchDialog(
    mismatch: CertificateMismatch,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    var acknowledged by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onReject,
        title = { Text(stringResource(R.string.cert_changed_title)) },
        text = {
            Column {
                Text(stringResource(R.string.cert_changed_body))
                Spacer(Modifier.height(12.dp))
                Text("Host: ${mismatch.host}", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Text("Expected", style = MaterialTheme.typography.labelSmall)
                Text(
                    text = mismatch.expected,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(8.dp))
                Text("Presented", style = MaterialTheme.typography.labelSmall)
                Text(
                    text = mismatch.presented,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = acknowledged, onCheckedChange = { acknowledged = it })
                    Text(
                        text = "I reset or replaced this radio",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept, enabled = acknowledged) {
                Text(stringResource(R.string.trust_new_cert))
            }
        },
        dismissButton = {
            TextButton(onClick = onReject) { Text(stringResource(R.string.cancel)) }
        },
    )
}
