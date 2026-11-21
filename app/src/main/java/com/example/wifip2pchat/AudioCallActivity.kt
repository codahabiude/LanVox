package com.example.wifip2pchat

import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import java.util.Locale

/**
 * Full-screen Activity for an audio call.
 *
 * Improvements:
 *  - Immediate foreground service start with Intent extras (no 500ms postDelayed race)
 *  - Accurate Ringing → Connecting → Connected status driven by [CallSessionBus]
 *  - Software mic mute via [AudioCallService.setMuted] + AudioFocus & communication routing
 *  - Bidirectional hangup signaling ([Constants.MSG_TYPE_CALL_END]) & clean auto-dismissal
 *  - Modern [OnBackPressedCallback]
 */
class AudioCallActivity : AppCompatActivity(), CallSessionBus.Listener {

    companion object {
        const val EXTRA_IS_GROUP_OWNER = "extra_is_group_owner"
        const val EXTRA_HOST_ADDRESS = "extra_host_address"
        const val EXTRA_PEER_ID = "extra_peer_id"
        const val EXTRA_PEER_NAME = "extra_peer_name"
        const val EXTRA_IS_OUTGOING = "extra_is_outgoing"
    }

    private lateinit var ivAvatar: TextView
    private lateinit var tvCallerName: TextView
    private lateinit var tvCallStatus: TextView
    private lateinit var tvCallDuration: TextView
    private lateinit var btnMute: MaterialButton
    private lateinit var btnEndCall: MaterialButton
    private lateinit var btnSpeaker: MaterialButton

    private var peerId: String? = null
    private var peerName: String = "Peer"
    private var isOutgoing = false

    private var isMuted = false
    private var isSpeakerOn = false
    private var callStartTime = 0L
    private var timerStarted = false
    private var endedLocally = false

    private val durationHandler = Handler(Looper.getMainLooper())
    private var durationRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audio_call)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        peerId = intent.getStringExtra(EXTRA_PEER_ID)
        peerName = intent.getStringExtra(EXTRA_PEER_NAME)?.takeIf { it.isNotBlank() } ?: "Peer"
        isOutgoing = intent.getBooleanExtra(EXTRA_IS_OUTGOING, false)

        initViews()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Toast.makeText(
                    this@AudioCallActivity,
                    "Call continuing in background — tap notification to return",
                    Toast.LENGTH_SHORT
                ).show()
                moveTaskToBack(true)
            }
        })

        CallSessionBus.configureVoiceAudio(this, speakerOnByDefault = false)
        CallSessionBus.addListener(this)
        CallSessionBus.startSession(
            callType = Constants.CALL_TYPE_AUDIO,
            peerId = peerId,
            initialState = if (isOutgoing) CallSessionBus.CallState.RINGING else CallSessionBus.CallState.CONNECTING
        )
        startAudioCall()
    }

    private fun initViews() {
        ivAvatar = findViewById(R.id.ivAvatar)
        tvCallerName = findViewById(R.id.tvCallerName)
        tvCallStatus = findViewById(R.id.tvCallStatus)
        tvCallDuration = findViewById(R.id.tvCallDuration)
        btnMute = findViewById(R.id.btnMute)
        btnEndCall = findViewById(R.id.btnEndCall)
        btnSpeaker = findViewById(R.id.btnSpeaker)

        tvCallerName.text = peerName
        ivAvatar.text = peerName.trim().take(1).uppercase(Locale.getDefault()).ifBlank { "L" }
        updateSpeakerUi()
        updateMuteUi()

        btnMute.setOnClickListener { toggleMute() }
        btnEndCall.setOnClickListener { endCall() }
        btnSpeaker.setOnClickListener { toggleSpeaker() }
    }

    private fun startAudioCall() {
        val isGroupOwner = intent.getBooleanExtra(EXTRA_IS_GROUP_OWNER, false)
        val hostAddress = intent.getStringExtra(EXTRA_HOST_ADDRESS)

        tvCallStatus.text = if (isOutgoing) "Ringing…" else "Connecting…"

        val serviceIntent = Intent(this, AudioCallService::class.java).apply {
            putExtra(AudioCallService.EXTRA_IS_GROUP_OWNER, isGroupOwner)
            putExtra(AudioCallService.EXTRA_HOST_ADDRESS, hostAddress)
            putExtra(AudioCallService.EXTRA_NOTIFY_CALL_BUS, true)
            putExtra(AudioCallService.EXTRA_PEER_ID, peerId)
            putExtra(AudioCallService.EXTRA_PEER_NAME, peerName)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    override fun onCallStateChanged(state: CallSessionBus.CallState) {
        if (isFinishing || isDestroyed) return
        when (state) {
            CallSessionBus.CallState.RINGING -> tvCallStatus.text = "Ringing…"
            CallSessionBus.CallState.CONNECTING -> tvCallStatus.text = getString(R.string.call_connecting)
            CallSessionBus.CallState.CONNECTED -> {
                tvCallStatus.text = getString(R.string.call_connected_webrtc)
                startDurationTimerIfNeeded()
            }
            CallSessionBus.CallState.RECONNECTING -> {
                tvCallStatus.text = getString(R.string.call_reconnecting)
            }
            CallSessionBus.CallState.ENDED -> tvCallStatus.text = getString(R.string.call_ended)
        }
    }

    override fun onMuteChanged(muted: Boolean) {
        if (isFinishing || isDestroyed) return
        if (isMuted != muted) {
            isMuted = muted
            updateMuteUi()
        }
    }

    override fun onCallEnded(reason: String) {
        if (endedLocally || isFinishing || isDestroyed) return
        endedLocally = true
        Toast.makeText(this, reason, Toast.LENGTH_SHORT).show()
        cleanupAndFinish()
    }

    private fun toggleMute() {
        isMuted = !isMuted
        AudioCallService.setMuted(isMuted)
        updateMuteUi()
        Toast.makeText(this, if (isMuted) "Muted" else "Unmuted", Toast.LENGTH_SHORT).show()
    }

    private fun updateMuteUi() {
        btnMute.setIconResource(if (isMuted) R.drawable.ic_mic_off else R.drawable.ic_mic)
        btnMute.alpha = if (isMuted) 0.6f else 1.0f
    }

    private fun toggleSpeaker() {
        isSpeakerOn = !isSpeakerOn
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.isSpeakerphoneOn = isSpeakerOn
        updateSpeakerUi()
        Toast.makeText(this, if (isSpeakerOn) "Speaker on" else "Speaker off", Toast.LENGTH_SHORT).show()
    }

    private fun updateSpeakerUi() {
        btnSpeaker.setIconResource(if (isSpeakerOn) R.drawable.ic_volume else R.drawable.ic_volume_off)
        btnSpeaker.alpha = if (isSpeakerOn) 1.0f else 0.65f
    }

    private fun endCall() {
        if (endedLocally) return
        endedLocally = true
        val hangupPayload = Constants.MSG_TYPE_CALL_END + Constants.CALL_TYPE_AUDIO
        if (!MainActivity.sendControlToPeerStatic(peerId, hangupPayload)) {
            CallListenerService.sendControl(hangupPayload, peerId)
        }
        cleanupAndFinish()
    }

    private fun cleanupAndFinish() {
        AudioCallService.endCall()
        durationRunnable?.let { durationHandler.removeCallbacks(it) }
        CallSessionBus.removeListener(this)
        CallSessionBus.restoreVoiceAudio(this)
        CallSessionBus.clearSession()
        finish()
    }

    // ======================== Duration Timer ========================

    private fun startDurationTimerIfNeeded() {
        if (timerStarted) return
        timerStarted = true
        callStartTime = System.currentTimeMillis()
        durationRunnable = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - callStartTime
                val minutes = (elapsed / 1000) / 60
                val seconds = (elapsed / 1000) % 60
                tvCallDuration.text = String.format(Locale.US, "%02d:%02d", minutes, seconds)
                durationHandler.postDelayed(this, 1000)
            }
        }
        durationHandler.post(durationRunnable!!)
    }

    override fun onDestroy() {
        durationRunnable?.let { durationHandler.removeCallbacks(it) }
        CallSessionBus.removeListener(this)
        if (!endedLocally) {
            AudioCallService.endCall()
            CallSessionBus.restoreVoiceAudio(this)
            CallSessionBus.clearSession()
        }
        super.onDestroy()
    }
}
