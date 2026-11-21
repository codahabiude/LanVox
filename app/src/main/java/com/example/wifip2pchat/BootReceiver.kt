package com.example.wifip2pchat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Restarts the standby listener after the phone reboots so LANVOX can
 * receive calls with the app never having been opened.
 *
 * Listens for:
 *  - ACTION_BOOT_COMPLETED        — normal device boot
 *  - QUICKBOOT_POWERON            — some OEM fast boots (HTC/OnePlus style)
 *  - ACTION_MY_PACKAGE_REPLACED   — the app was updated while a session
 *                                   may still be alive on the peer side
 *
 * Note: if the user force-stops the app from system settings, Android
 * will not deliver BOOT_COMPLETED until the app is opened once again —
 * this is a platform limitation for every sideloaded app.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            ACTION_QUICKBOOT_POWERON,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.d(TAG, "Boot event (${intent.action}) — starting call listener")
                try {
                    ContextCompat.startForegroundService(
                        context, Intent(context, CallListenerService::class.java)
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Could not start listener: ${e.message}")
                }
            }
        }
    }
}
