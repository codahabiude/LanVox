package com.example.wifip2pchat

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Manages ringtone playback and vibration for incoming calls.
 *
 * Plays the user's current system ringtone in a loop through the
 * notification stream (respects silent / vibrate-only ringer modes) and
 * vibrates in a phone-like pattern. Works identically whether the call
 * arrives with the app open ([IncomingCallActivity]) or closed
 * ([CallListenerService]) — the isRinging guard makes overlapping calls
 * from both paths safe.
 */
object CallRingtoneManager {

    private const val TAG = "CallRingtone"

    private var vibrator: Vibrator? = null
    private var player: MediaPlayer? = null
    private var isRinging = false

    // Vibration pattern: {delay, vibrate, sleep, vibrate, sleep, ...}
    // 0ms delay, 500ms vibrate, 300ms pause, 500ms vibrate, 800ms pause (repeat)
    private val vibrationPattern = longArrayOf(0, 500, 300, 500, 800)

    /**
     * Start ringing with the system ringtone and vibration.
     */
    fun startRinging(context: Context) {
        if (isRinging) return
        isRinging = true
        Log.d(TAG, "Starting ringtone + vibration")

        startVibration(context.applicationContext)
        startSound(context.applicationContext)
    }

    private fun startVibration(context: Context) {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            mgr?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

        if (vibrator?.hasVibrator() != true) {
            Log.w(TAG, "Device does not support vibration")
            return
        }

        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(
                    VibrationEffect.createWaveform(vibrationPattern, 0),
                    attrs
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(vibrationPattern, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibration error: ${e.message}")
        }
    }

    private fun startSound(context: Context) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            // Silent mode → nothing; vibrate-only mode → vibration already running
            if (audioManager?.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
                Log.d(TAG, "Ringer not normal — sound suppressed")
                return
            }

            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: run {
                    Log.w(TAG, "No ringtone available on this device")
                    return
                }

            val p = MediaPlayer()
            p.setDataSource(context, uri)
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            p.isLooping = true
            p.prepare()
            p.start()
            player = p
            Log.d(TAG, "Ringtone playing")
        } catch (e: Exception) {
            Log.e(TAG, "Ringtone error: ${e.message}")
            player = null
        }
    }

    /**
     * Stop ringing and vibration immediately. Safe to call multiple times
     * and from any component (activity or service).
     */
    fun stopRinging() {
        if (!isRinging) return
        isRinging = false
        Log.d(TAG, "Stopping ringtone + vibration")

        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null

        try { vibrator?.cancel() } catch (_: Exception) {}
        vibrator = null
    }
}
