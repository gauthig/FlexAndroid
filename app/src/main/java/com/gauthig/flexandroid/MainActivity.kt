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
package com.gauthig.flexandroid

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gauthig.flexandroid.radio.ConnectionPhase
import com.gauthig.flexandroid.service.RadioSessionService
import com.gauthig.flexandroid.ui.CertificateMismatchDialog
import com.gauthig.flexandroid.ui.FlexViewModel
import com.gauthig.flexandroid.ui.RadioPickerScreen
import com.gauthig.flexandroid.ui.RadioScreen
import com.gauthig.flexandroid.ui.Screen
import com.gauthig.flexandroid.ui.SignInScreen
import com.gauthig.flexandroid.ui.theme.FlexAndroidTheme

/**
 * The only activity.
 *
 * Navigation is a `when` over three states rather than a nav graph: the app has one
 * linear path — sign in, pick a radio, operate — and a graph would add indirection
 * without buying a single back-stack behaviour worth having. Operating screens
 * should not be reachable by back button from a live transmission.
 */
class MainActivity : ComponentActivity() {

    // A denial is not handled here: PTT stays disabled, and Android's own
    // "don't ask again" behaviour is a better nag policy than one we invent.
    private val requestMicrophone = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Operating a radio means watching the spectrum while not touching the
        // screen. Letting it sleep mid-contact would be actively hostile.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            FlexAndroidTheme {
                val viewModel: FlexViewModel = viewModel()
                val ui by viewModel.ui.collectAsState()
                val radio by viewModel.radioState.collectAsState()
                val frame by viewModel.spectrum.collectAsState()

                // The microphone is requested when the operating screen first
                // appears, not at launch: asking before there is a radio to talk
                // to has no context the operator can evaluate.
                LaunchedEffect(ui.screen) {
                    if (ui.screen == Screen.RADIO && !hasMicrophonePermission()) {
                        requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }

                // Promote the process while a session is live so audio and the
                // radio keepalive survive the screen going off.
                LaunchedEffect(radio.phase) {
                    if (radio.phase == ConnectionPhase.READY) {
                        RadioSessionService.start(
                            this@MainActivity,
                            radio.identity.nickname.ifBlank { radio.identity.model },
                        )
                    } else {
                        RadioSessionService.stop(this@MainActivity)
                    }
                }

                Scaffold { insets ->
                    Box(Modifier.fillMaxSize().padding(insets)) {
                        when (ui.screen) {
                            Screen.SIGN_IN -> SignInScreen(
                                state = ui,
                                onEmailChange = viewModel::onEmailChange,
                                onPasswordChange = viewModel::onPasswordChange,
                                onStaySignedInChange = viewModel::onStaySignedInChange,
                                onSignIn = viewModel::signIn,
                                onToggleDirect = viewModel::toggleDirectEntry,
                                onLanHostChange = viewModel::onLanHostChange,
                                onConnectLan = viewModel::connectLan,
                            )

                            Screen.RADIO_PICKER -> RadioPickerScreen(
                                state = ui,
                                onConnect = viewModel::connectSmartLink,
                                onSignOut = viewModel::signOut,
                            )

                            Screen.RADIO -> RadioScreen(
                                state = radio,
                                frame = frame,
                                micLevelProvider = { viewModel.micPeakLevel },
                                onTune = viewModel::tuneTo,
                                onStep = viewModel::stepFrequency,
                                onSelectBand = viewModel::selectBand,
                                onSelectMode = viewModel::setMode,
                                onZoom = { factor ->
                                    viewModel.setPanBandwidth(radio.pan.bandwidthMHz * factor)
                                },
                                onPan = { delta ->
                                    viewModel.tuneTo(radio.slice.frequencyMHz + delta)
                                },
                                onRfPower = viewModel::setRfPower,
                                onMicLevel = viewModel::setMicLevel,
                                onAudioGain = viewModel::setAudioGain,
                                onPtt = { keyed -> keyTransmitter(viewModel, keyed) },
                                onTuneCarrier = viewModel::setTune,
                                onDisconnect = viewModel::disconnect,
                            )
                        }

                        ui.certificateMismatch?.let { mismatch ->
                            CertificateMismatchDialog(
                                mismatch = mismatch,
                                onAccept = viewModel::acceptCertificate,
                                onReject = viewModel::rejectCertificate,
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        RadioSessionService.stop(this)
        super.onDestroy()
    }

    /**
     * Keys or unkeys the transmitter, asking for the microphone if it is missing.
     *
     * The suppression is load-bearing rather than lazy: lint cannot see through
     * [hasMicrophonePermission] to prove the guard, but a press with no permission
     * cannot reach [FlexViewModel.setPtt] — it launches the request instead.
     */
    @SuppressLint("MissingPermission")
    private fun keyTransmitter(viewModel: FlexViewModel, keyed: Boolean) {
        if (hasMicrophonePermission()) {
            viewModel.setPtt(keyed)
        } else if (keyed) {
            requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
}
