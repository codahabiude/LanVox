package com.example.wifip2pchat

import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * Full-screen Activity for an audio call.
 *
 * Shows call status, duration timer, and controls:
 *  - Mute/Unmute microphone (icon swaps mic ↔ mic-off)
 *  - Speaker on/off (icon swaps volume ↔ volume-off)
 *  - End call
 *
 * v4.1: switches to MODE_IN_COMMUNICATION for the low-latency voice-call HAL
 * path (~30ms instead of the media pipeline's ~150ms), restores mode on exit.
 */
class AudioCallActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AudioCallActivity"
        const val EXTRA_IS_GROUP_OWNER = "extra_is_group_owner"
        const val EXTRA_HOST_ADDRESS = "extra_host_address"
    }

    private lateinit var tvCallerName: TextView
    private lateinit var tvCallStatus: TextView
    private lateinit var tvCallDuration: TextView
    private lateinit var btnMute: MaterialButton
    private lateinit var btnEndCall: MaterialButton
    private lateinit var btnSpeaker: MaterialButton

    private var isMuted = false
    private var isSpeakerOn = false
    private var callStartTime = 0L
    private val durationHandler = Handler(Looper.getMainLooper())
    private var durationRunnable: Runnable? = null
    private var previousAudioMode = AudioManager.MODE_NORMAL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audio_call)

        // Keep screen on during call
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        initViews()
        configureCallAudioMode()
        startAudioCall()
    }

    private fun initViews() {
        tvCallerName = findViewById(R.id.tvCallerName)
        tvCallStatus = findViewById(R.id.tvCallStatus)
        tvCallDuration = findViewById(R.id.tvCallDuration)
        btnMute = findViewById(R.id.btnMute)
        btnEndCall = findViewById(R.id.btnEndCall)
        btnSpeaker = findViewById(R.id.btnSpeaker)

        btnMute.setOnClickListener { toggleMute() }
        btnEndCall.setOnClickListener { endCall() }
        btnSpeaker.setOnClickListener { toggleSpeaker() }
    }

    /**
     * Put Android in MODE_IN_COMMUNICATION. Without this the device routes
     * audio through the media pipeline (~150ms) instead of the voice-call
     * pipeline (~30ms). Speaker defaults OFF (earpiece, less echo).
     */
    private fun configureCallAudioMode() {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        previousAudioMode = audioManager.mode
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = false
            isSpeakerOn = false
        } catch (e: Exception) {
            Log.w(TAG, "Could not switch to MODE_IN_COMMUNICATION: ${e.message}")
        }
    }

    private fun restoreAudioMode() {
        try {
            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            audioManager.mode = previousAudioMode
            audioManager.isSpeakerphoneOn = false
            audioManager.isMicrophoneMute = false
        } catch (_: Exception) {}
    }

    private fun startAudioCall() {
        val isGroupOwner = intent.getBooleanExtra(EXTRA_IS_GROUP_OWNER, false)
        val hostAddress = intent.getStringExtra(EXTRA_HOST_ADDRESS)

        tvCallStatus.text = "Connecting..."

        // Start the audio call service
        val serviceIntent = Intent(this, AudioCallService::class.java)
        startService(serviceIntent)

        // Give the service a moment to initialize, then start streaming
        Handler(Looper.getMainLooper()).postDelayed({
            if (isGroupOwner) {
                AudioCallService.startAsGroupOwner()
            } else if (hostAddress != null) {
                AudioCallService.connectToGroupOwner(hostAddress)
            }

            tvCallStatus.text = "Connected"
            callStartTime = System.currentTimeMillis()
            startDurationTimer()
        }, 500)
    }

    private fun toggleMute() {
        isMuted = !isMuted
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.isMicrophoneMute = isMuted
        // Swap the icon so it's obvious whether the mic is live or muted.
        btnMute.setIconResource(if (isMuted) R.drawable.ic_mic_off else R.drawable.ic_mic)
        btnMute.alpha = if (isMuted) 0.6f else 1.0f
        Toast.makeText(this, if (isMuted) "Muted" else "Unmuted", Toast.LENGTH_SHORT).show()
    }

    private fun toggleSpeaker() {
        isSpeakerOn = !isSpeakerOn
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.isSpeakerphoneOn = isSpeakerOn
        btnSpeaker.setIconResource(if (isSpeakerOn) R.drawable.ic_volume else R.drawable.ic_volume_off)
        btnSpeaker.alpha = if (isSpeakerOn) 1.0f else 0.6f
        Toast.makeText(this, if (isSpeakerOn) "Speaker on" else "Speaker off", Toast.LENGTH_SHORT).show()
    }

    private fun endCall() {
        AudioCallService.endCall()
        durationRunnable?.let { durationHandler.removeCallbacks(it) }
        restoreAudioMode()
        finish()
    }

    // ======================== Duration Timer ========================

    private fun startDurationTimer() {
        durationRunnable = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - callStartTime
                val minutes = (elapsed / 1000) / 60
                val seconds = (elapsed / 1000) % 60
                tvCallDuration.text = String.format("%02d:%02d", minutes, seconds)
                durationHandler.postDelayed(this, 1000)
            }
        }
        durationHandler.post(durationRunnable!!)
    }

    override fun onDestroy() {
        durationRunnable?.let { durationHandler.removeCallbacks(it) }
        // If the activity is killed without End being tapped, still restore audio.
        if (AudioCallService.isRunning.not()) restoreAudioMode()
        super.onDestroy()
    }

    @Deprecated("Use OnBackInvokedCallback for API 33+")
    override fun onBackPressed() {
        // Prevent accidental back press during call — must use End button
        Toast.makeText(this, "Tap End Call to hang up", Toast.LENGTH_SHORT).show()
    }
}
