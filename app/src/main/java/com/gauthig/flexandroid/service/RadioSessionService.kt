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
package com.gauthig.flexandroid.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.gauthig.flexandroid.FlexApp
import com.gauthig.flexandroid.MainActivity
import com.gauthig.flexandroid.R

/**
 * Keeps a radio session alive while the app is not in the foreground.
 *
 * Android aggressively freezes background processes, which would stall the
 * keepalive the radio expects and silence the audio streams. Promoting the process
 * with a media-playback foreground service is what lets an operator listen with the
 * screen off — the same allowance any streaming audio app relies on.
 *
 * The service holds no session state of its own. It exists purely for the process
 * priority; the session lives in the ViewModel, which outlives configuration
 * changes on its own.
 */
class RadioSessionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(intent?.getStringExtra(EXTRA_RADIO)))
        // START_STICKY would restart the service with a null intent after a kill,
        // reviving a notification for a session that no longer exists.
        return START_NOT_STICKY
    }

    private fun buildNotification(radioName: String?): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, FlexApp.SESSION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(radioName ?: getString(R.string.slice_a))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_RADIO = "radio_name"

        fun start(context: Context, radioName: String?) {
            context.startForegroundService(
                Intent(context, RadioSessionService::class.java)
                    .putExtra(EXTRA_RADIO, radioName),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RadioSessionService::class.java))
        }
    }
}
