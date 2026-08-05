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

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

/** Application entry point. Registers the notification channel the service needs. */
class FlexApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                SESSION_CHANNEL_ID,
                getString(R.string.app_name),
                // Low importance: the notification exists to keep the session
                // alive and let the operator return to it, not to interrupt.
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        const val SESSION_CHANNEL_ID = "radio_session"
    }
}
