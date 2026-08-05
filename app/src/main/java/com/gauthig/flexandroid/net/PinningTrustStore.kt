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
package com.gauthig.flexandroid.net

import android.content.SharedPreferences
import androidx.core.content.edit
import java.io.IOException
import java.security.MessageDigest
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Trust-on-first-use pinning for the TLS session to a radio.
 *
 * FLEX radios present a self-signed certificate, so ordinary CA validation has
 * nothing to anchor against. Instead the first certificate seen for a given host
 * is remembered by SHA-256 fingerprint, and any later mismatch stops the
 * handshake before `wan validate` is sent. That ordering is the point: an
 * attacker who intercepts the path never receives an authenticated session, only
 * a dropped one.
 *
 * A mismatch is not automatically fatal to the app — a radio that was factory
 * reset legitimately gets a new certificate — but clearing the pin is an
 * explicit operator decision, made through [forget].
 */
class PinningTrustStore(
    private val prefs: SharedPreferences,
    private val socketFactory: SSLSocketFactory =
        SSLSocketFactory.getDefault() as SSLSocketFactory,
) : RadioTrustStore {

    override fun createSocket(host: String, port: Int): SSLSocket =
        (socketFactory.createSocket(host, port) as SSLSocket).apply {
            // Radio firmware negotiates TLS 1.2; leave 1.3 enabled for newer builds.
            enabledProtocols = supportedProtocols
                .filter { it == "TLSv1.2" || it == "TLSv1.3" }
                .toTypedArray()
        }

    override fun verifyPin(host: String, socket: SSLSocket) {
        val chain = socket.session.peerCertificates
        if (chain.isEmpty()) throw IOException("radio presented no certificate")
        val presented = fingerprint(chain[0].encoded)

        val stored = prefs.getString(key(host), null)
        if (stored == null) {
            prefs.edit { putString(key(host), presented) }
            return
        }
        if (!stored.equals(presented, ignoreCase = true)) {
            socket.close()
            throw CertificatePinMismatch(host, expected = stored, presented = presented)
        }
    }

    /** Drops the pin for [host] so the next connection re-learns it. */
    fun forget(host: String) {
        prefs.edit { remove(key(host)) }
    }

    /** Replaces the pin for [host] after the operator accepted a new certificate. */
    fun trust(host: String, fingerprint: String) {
        prefs.edit { putString(key(host), fingerprint) }
    }

    private fun key(host: String) = "pin:$host"

    private companion object {
        fun fingerprint(der: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(der)
                .joinToString(":") { "%02X".format(it) }
    }
}

/** Raised when a radio's certificate differs from the one pinned for its host. */
class CertificatePinMismatch(
    val host: String,
    val expected: String,
    val presented: String,
) : IOException("certificate for $host changed")
