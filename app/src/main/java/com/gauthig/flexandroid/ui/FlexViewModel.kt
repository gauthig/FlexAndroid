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

import android.Manifest
import android.app.Application
import androidx.annotation.RequiresPermission
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gauthig.flexandroid.data.CredentialStore
import com.gauthig.flexandroid.net.CertificatePinMismatch
import com.gauthig.flexandroid.net.PinningTrustStore
import com.gauthig.flexandroid.net.SmartLinkClient
import com.gauthig.flexandroid.net.WanRadio
import com.gauthig.flexandroid.radio.Band
import com.gauthig.flexandroid.radio.Mode
import com.gauthig.flexandroid.radio.RadioSession
import com.gauthig.flexandroid.radio.RadioSessionState
import com.gauthig.flexandroid.radio.RadioTarget
import com.gauthig.flexandroid.radio.TuningStep
import java.io.IOException
import java.net.DatagramSocket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Which screen the app is showing. */
enum class Screen { SIGN_IN, RADIO_PICKER, RADIO }

/** Everything the sign-in and radio-picker screens need. */
data class ConnectUiState(
    val screen: Screen = Screen.SIGN_IN,
    val email: String = "",
    val password: String = "",
    val staySignedIn: Boolean = true,
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val radios: List<WanRadio> = emptyList(),
    val lanHost: String = "",
    val showDirectEntry: Boolean = false,
    val certificateMismatch: CertificateMismatch? = null,
)

/** A pending decision about a radio whose TLS certificate changed. */
data class CertificateMismatch(
    val host: String,
    val expected: String,
    val presented: String,
    val retry: RadioTarget,
)

/**
 * Drives the whole app: sign-in, radio selection, and the live session.
 *
 * The SmartLink client and the radio session are separate objects with separate
 * lifetimes because they fail independently — losing the broker connection does
 * not drop an established radio session, and vice versa. This class is what knows
 * how the two relate.
 */
class FlexViewModel(application: Application) : AndroidViewModel(application) {

    private val credentials = CredentialStore(application)
    private val trustStore = PinningTrustStore(credentials.pinPreferences)
    private val smartLink = SmartLinkClient(viewModelScope)

    private val session = RadioSession(
        scope = viewModelScope,
        clientId = credentials.clientId,
        trustStore = trustStore,
    )

    private val _ui = MutableStateFlow(
        ConnectUiState(email = credentials.email.orEmpty()),
    )
    val ui: StateFlow<ConnectUiState> = _ui

    val radioState: StateFlow<RadioSessionState> = session.state

    /** Latest spectrum frame, or `null` before the first one arrives. */
    val spectrum = session.spectrum.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = null,
    )

    init {
        viewModelScope.launch {
            smartLink.radios.collect { radios -> _ui.update { it.copy(radios = radios) } }
        }
        viewModelScope.launch {
            smartLink.errors.collect { error -> _ui.update { it.copy(error = error, busy = false) } }
        }
        // A stored refresh token means the operator already chose to stay signed
        // in, so go straight to the radio list.
        credentials.refreshToken?.let { token -> viewModelScope.launch { silentSignIn(token) } }
    }

    // ── Sign-in ──────────────────────────────────────────────────────────────

    fun onEmailChange(value: String) = _ui.update { it.copy(email = value, error = null) }
    fun onPasswordChange(value: String) = _ui.update { it.copy(password = value, error = null) }
    fun onStaySignedInChange(value: Boolean) = _ui.update { it.copy(staySignedIn = value) }
    fun onLanHostChange(value: String) = _ui.update { it.copy(lanHost = value, error = null) }
    fun toggleDirectEntry() = _ui.update { it.copy(showDirectEntry = !it.showDirectEntry) }
    fun dismissError() = _ui.update { it.copy(error = null) }

    fun signIn() {
        val state = _ui.value
        if (state.email.isBlank() || state.password.isBlank()) {
            _ui.update { it.copy(error = "Enter your SmartLink email and password.") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null, message = "Signing in…") }
            try {
                val tokens = smartLink.login(state.email.trim(), state.password)
                credentials.email = state.email.trim()
                credentials.refreshToken =
                    if (state.staySignedIn) tokens.refreshToken else null
                // The password is not retained anywhere past this point.
                _ui.update { it.copy(password = "") }
                afterAuthentication()
            } catch (e: IOException) {
                _ui.update {
                    it.copy(busy = false, message = null, error = e.message ?: "Sign-in failed.")
                }
            }
        }
    }

    private suspend fun silentSignIn(refreshToken: String) {
        _ui.update { it.copy(busy = true, message = "Reconnecting…") }
        try {
            val tokens = smartLink.loginWithRefreshToken(refreshToken)
            credentials.refreshToken = tokens.refreshToken
            afterAuthentication()
        } catch (e: IOException) {
            // A rejected refresh token means the grant was revoked or expired;
            // drop it so the operator is asked once rather than on every launch.
            credentials.refreshToken = null
            _ui.update {
                it.copy(busy = false, message = null, error = "Please sign in again: ${e.message}")
            }
        }
    }

    private suspend fun afterAuthentication() {
        try {
            smartLink.connect()
            _ui.update {
                it.copy(busy = false, message = null, screen = Screen.RADIO_PICKER)
            }
        } catch (e: IOException) {
            _ui.update {
                it.copy(
                    busy = false,
                    message = null,
                    error = e.message ?: "Could not reach the SmartLink server.",
                )
            }
        }
    }

    fun signOut() {
        session.close()
        smartLink.signOut()
        credentials.clear()
        _ui.value = ConnectUiState(screen = Screen.SIGN_IN)
    }

    // ── Connecting to a radio ────────────────────────────────────────────────

    /**
     * Connects to a SmartLink radio.
     *
     * The UDP port is bound here rather than inside the session because the
     * broker has to be told which port to have the radio target *before* the
     * radio is contacted — so the port must be reserved first, then released for
     * the session to bind for real.
     */
    fun connectSmartLink(radio: WanRadio) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, message = "Contacting ${radio.displayName}…") }
            credentials.lastRadioSerial = radio.serial

            val udpPort = reserveUdpPort()
            try {
                smartLink.requestConnect(radio.serial, udpPort)
            } catch (e: IOException) {
                _ui.update { it.copy(busy = false, message = null, error = e.message) }
                return@launch
            }

            // The broker answers asynchronously. Without a bound wait the UI would
            // hang forever on a radio that is powered off.
            val ready = withTimeoutOrNull(BROKER_TIMEOUT_MS) {
                smartLink.connectReady.first { it.serial == radio.serial }
            }
            if (ready == null) {
                _ui.update {
                    it.copy(
                        busy = false,
                        message = null,
                        error = "${radio.displayName} did not answer. Is it powered on?",
                    )
                }
                return@launch
            }

            val target = RadioTarget.SmartLink(
                host = radio.publicIp,
                tlsPort = radio.publicTlsPort.takeIf { it > 0 } ?: DEFAULT_WAN_TLS_PORT,
                udpPort = udpPort,
                wanHandle = ready.wanHandle,
                serial = radio.serial,
                requiresHolePunch = radio.requiresHolePunch,
            )
            openSession(target)
        }
    }

    /** Connects to a radio on the same network by address. */
    fun connectLan() {
        val host = _ui.value.lanHost.trim()
        if (host.isEmpty()) {
            _ui.update { it.copy(error = "Enter the radio's IP address.") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, message = "Connecting to $host…") }
            openSession(RadioTarget.Lan(host))
        }
    }

    private suspend fun openSession(target: RadioTarget) {
        try {
            session.open(target)
            _ui.update { it.copy(busy = false, message = null, screen = Screen.RADIO) }
        } catch (mismatch: CertificatePinMismatch) {
            _ui.update {
                it.copy(
                    busy = false,
                    message = null,
                    certificateMismatch = CertificateMismatch(
                        host = mismatch.host,
                        expected = mismatch.expected,
                        presented = mismatch.presented,
                        retry = target,
                    ),
                )
            }
        }
    }

    /** Accepts a changed radio certificate and retries the connection. */
    fun acceptCertificate() {
        val pending = _ui.value.certificateMismatch ?: return
        trustStore.trust(pending.host, pending.presented)
        _ui.update { it.copy(certificateMismatch = null, busy = true) }
        viewModelScope.launch { openSession(pending.retry) }
    }

    fun rejectCertificate() {
        _ui.update { it.copy(certificateMismatch = null, busy = false) }
    }

    fun disconnect() {
        session.close()
        _ui.update {
            it.copy(
                screen = if (smartLink.connected.value) Screen.RADIO_PICKER else Screen.SIGN_IN,
            )
        }
    }

    // ── Radio controls ───────────────────────────────────────────────────────

    fun tuneTo(frequencyMHz: Double) = session.tuneTo(frequencyMHz)
    fun stepFrequency(step: TuningStep, up: Boolean) = session.stepFrequency(step, up)
    fun setMode(mode: Mode) = session.setMode(mode)
    fun selectBand(band: Band) = session.selectBand(band)
    fun setPanBandwidth(bandwidthMHz: Double) = session.setPanBandwidth(bandwidthMHz)
    fun setRfPower(percent: Int) = session.setRfPower(percent)
    fun setMicLevel(level: Int) = session.setMicLevel(level)
    fun setAudioGain(gain: Float) = session.setAudioGain(gain)
    fun setTune(on: Boolean) = session.setTune(on)
    fun clearMicrophoneError() = session.clearMicrophoneError()

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun setPtt(keyed: Boolean) = session.setPtt(keyed)

    val micPeakLevel: Float get() = session.micPeakLevel
    val rxPeakLevel: Float get() = session.rxPeakLevel

    override fun onCleared() {
        // ViewModel.onCleared is documented as empty, so there is nothing to
        // delegate to; releasing the sockets and audio devices is all this needs.
        session.close()
        smartLink.disconnect()
    }

    /**
     * Binds and immediately releases a UDP port to learn a free number.
     *
     * There is an unavoidable race here — another process could take the port in
     * between — but it is the only way to tell the broker a port before the
     * session owns one, and a collision simply surfaces as a failed connect the
     * operator can retry.
     */
    private fun reserveUdpPort(): Int =
        runCatching { DatagramSocket(0).use { it.localPort } }
            .getOrDefault(FALLBACK_UDP_PORT)

    private companion object {
        const val BROKER_TIMEOUT_MS = 20_000L
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
        const val DEFAULT_WAN_TLS_PORT = 4992
        const val FALLBACK_UDP_PORT = 4993
    }
}
