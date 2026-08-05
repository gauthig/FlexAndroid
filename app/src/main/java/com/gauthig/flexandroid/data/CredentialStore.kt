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
package com.gauthig.flexandroid.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.UUID

/**
 * Storage for the few things worth remembering between sessions.
 *
 * There is exactly one secret here — the SmartLink refresh token — and it is
 * encrypted under a keystore-held key by [KeystoreCipher] before being written.
 * **The account password is never persisted at all**: it is exchanged for tokens
 * and dropped.
 *
 * Certificate pins deliberately live in plain preferences. They are public
 * fingerprints, and keeping them out of the encrypted path means a keystore fault
 * cannot silently reset pinning — which would turn a security control into a
 * device-dependent one.
 */
class CredentialStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val cipher = KeystoreCipher(KEY_ALIAS)

    /** Public certificate fingerprints. Backs [com.gauthig.flexandroid.net.PinningTrustStore]. */
    val pinPreferences: SharedPreferences =
        context.getSharedPreferences(PIN_FILE, Context.MODE_PRIVATE)

    /**
     * The SmartLink refresh token, encrypted at rest.
     *
     * Reads return `null` both when nothing is stored and when the stored value
     * can no longer be decrypted — a device lock change can invalidate the key.
     * Both mean the same thing to the caller: ask the operator to sign in.
     */
    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)?.let(cipher::decrypt)
        set(value) {
            // A keystore that cannot encrypt means the token is not persisted.
            // Falling back to plaintext would be worse than a re-login.
            val sealed = value?.let(cipher::encrypt)
            prefs.edit {
                if (sealed == null) remove(KEY_REFRESH_TOKEN)
                else putString(KEY_REFRESH_TOKEN, sealed)
            }
        }

    /** Remembered so the sign-in form can be pre-filled. Not a secret. */
    var email: String?
        get() = prefs.getString(KEY_EMAIL, null)
        set(value) = prefs.edit {
            if (value == null) remove(KEY_EMAIL) else putString(KEY_EMAIL, value)
        }

    /** Serial of the radio to offer first on the next launch. */
    var lastRadioSerial: String?
        get() = prefs.getString(KEY_LAST_SERIAL, null)
        set(value) = prefs.edit {
            if (value == null) remove(KEY_LAST_SERIAL) else putString(KEY_LAST_SERIAL, value)
        }

    /**
     * The client ID this app presents to v3+ radios.
     *
     * Generated once and reused forever. Radios key per-client persistence off it,
     * so a stable ID is what makes the radio restore this client's slice and
     * panadapter layout instead of treating every launch as a new operator.
     */
    val clientId: String
        get() = prefs.getString(KEY_CLIENT_ID, null)
            ?: UUID.randomUUID().toString()
                .also { id -> prefs.edit { putString(KEY_CLIENT_ID, id) } }

    /** Clears the stored token and cached email on sign-out. */
    fun clear() {
        prefs.edit {
            remove(KEY_REFRESH_TOKEN)
            remove(KEY_EMAIL)
        }
        cipher.deleteKey()
    }

    private companion object {
        const val PREFS_FILE = "flexandroid_prefs"
        const val PIN_FILE = "flexandroid_pins"
        const val KEY_ALIAS = "flexandroid_refresh_token_key"
        const val KEY_REFRESH_TOKEN = "smartlink_refresh_token"
        const val KEY_EMAIL = "smartlink_email"
        const val KEY_LAST_SERIAL = "last_radio_serial"
        const val KEY_CLIENT_ID = "client_id"
    }
}
