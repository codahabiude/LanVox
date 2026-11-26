package com.example.wifip2pchat

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Full-screen Activity for a WebRTC audio call featuring a floating glassmorphic
 * active call timer overlay synchronized with [CallSessionBus].
 */
class AudioCallActivity : AppCompatActivity(), CallSessionBus.Listener {

    companion object {
        const val EXTRA_IS_GROUP_OWNER = "extra_is_group_owner"
        const val EXTRA_HOST_ADDRESS = "extra_host_address"
        const val EXTRA_PEER_ID = "extra_peer_id"
        const val EXTRA_PEER_NAME = "extra_peer_name"
        const val EXTRA_IS_OUTGOING = "extra_is_outgoing"
        private const val TIMER_TICK_MS = 500L
    }

    private lateinit var ivAvatar: TextView
    private lateinit var ivAudioCallPhoto: ImageView
    private lateinit var tvCallerName: TextView
    private lateinit var tvCallStatus: TextView
    private lateinit var tvCallDuration: TextView
    private lateinit var audioTopOverlay: LinearLayout
    private lateinit var audioMainContent: LinearLayout
    private lateinit var viewTimerDot: View
    private lateinit var tvTimerState: TextView
    private lateinit var tvTimerOverlayDuration: TextView
    private lateinit var btnMute: MaterialButton
    private lateinit var btnEndCall: MaterialButton
    private lateinit var btnSpeaker: MaterialButton

    private var peerId: String? = null
    private var peerName: String = "Peer"
    private var isOutgoing = false

    private var isMuted = false
    private var isSpeakerOn = false
    private var timerLoopRunning = false
    private var pulseBright = true
    private var endedLocally = false
    private var callLogRecorded = false

    private val durationHandler = Handler(Looper.getMainLooper())
    private val durationRunnable = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed || endedLocally) return
            updateDurationUi()
            if (CallSessionBus.currentState == CallSessionBus.CallState.CONNECTED) {
                pulseBright = !pulseBright
                viewTimerDot.alpha = if (pulseBright) 1.0f else 0.35f
            } else {
                viewTimerDot.alpha = 1.0f
            }
            durationHandler.postDelayed(this, TIMER_TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audio_call)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        peerId = intent.getStringExtra(EXTRA_PEER_ID)
        peerName = intent.getStringExtra(EXTRA_PEER_NAME)?.takeIf { it.isNotBlank() } ?: "Peer"
        isOutgoing = intent.getBooleanExtra(EXTRA_IS_OUTGOING, false)

        initViews()
        applySafeAreaInsets()

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

        val reattachingToLiveCall = CallSessionBus.isCallActive() &&
            AudioCallService.isRunning &&
            CallSessionBus.activeCallType == Constants.CALL_TYPE_AUDIO &&
            (peerId == null || CallSessionBus.activePeerId == peerId)

        CallSessionBus.addListener(this)
        if (reattachingToLiveCall) {
            isMuted = CallSessionBus.currentMuted
            updateMuteUi()
            onCallStateChanged(CallSessionBus.currentState)
        } else {
            CallSessionBus.configureVoiceAudio(this, speakerOnByDefault = false)
            CallSessionBus.startSession(
                callType = Constants.CALL_TYPE_AUDIO,
                peerId = peerId,
                initialState = if (isOutgoing) CallSessionBus.CallState.RINGING else CallSessionBus.CallState.CONNECTING
            )
            onCallStateChanged(CallSessionBus.currentState)
            startAudioCall()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        updateDurationUi()
        onCallStateChanged(CallSessionBus.currentState)
    }

    private fun initViews() {
        ivAvatar = findViewById(R.id.ivAvatar)
        ivAudioCallPhoto = findViewById(R.id.ivAudioCallPhoto)
        tvCallerName = findViewById(R.id.tvCallerName)
        tvCallStatus = findViewById(R.id.tvCallStatus)
        tvCallDuration = findViewById(R.id.tvCallDuration)
        audioTopOverlay = findViewById(R.id.audioTopOverlay)
        audioMainContent = findViewById(R.id.audioMainContent)
        viewTimerDot = findViewById(R.id.viewTimerDot)
        tvTimerState = findViewById(R.id.tvTimerState)
        tvTimerOverlayDuration = findViewById(R.id.tvTimerOverlayDuration)
        btnMute = findViewById(R.id.btnMute)
        btnEndCall = findViewById(R.id.btnEndCall)
        btnSpeaker = findViewById(R.id.btnSpeaker)

        tvCallerName.text = peerName
        AvatarManager.bindAvatar(this, ivAudioCallPhoto, ivAvatar, peerId, peerName)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AvatarManager.avatarVersion.collect {
                    AvatarManager.bindAvatar(this@AudioCallActivity, ivAudioCallPhoto, ivAvatar, peerId, peerName)
                }
            }
        }
        updateDurationUi()
        updateSpeakerUi()
        updateMuteUi()

        btnMute.setOnClickListener { toggleMute() }
        btnEndCall.setOnClickListener { endCall() }
        btnSpeaker.setOnClickListener { toggleSpeaker() }
    }

    private fun applySafeAreaInsets() {
        val density = resources.displayMetrics.density
        val baseOverlaySide = (18 * density).toInt()
        val baseOverlayTop = (14 * density).toInt()
        val baseMainSide = (24 * density).toInt()
        val baseMainTop = (80 * density).toInt()
        val baseMainBottom = (32 * density).toInt()

        val root = findViewById<View>(R.id.audioCallRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            audioTopOverlay.setPadding(
                baseOverlaySide + bars.left,
                baseOverlayTop + bars.top,
                baseOverlaySide + bars.right,
                audioTopOverlay.paddingBottom
            )
            audioMainContent.setPadding(
                baseMainSide + bars.left,
                baseMainTop + bars.top,
                baseMainSide + bars.right,
                baseMainBottom + bars.bottom
            )
            insets
        }
    }

    private fun startAudioCall() {
        if (!peerId.isNullOrBlank() && CryptoManager.isPeerKeyBlocked(this, peerId)) {
            tvCallStatus.text = "Call blocked: Peer identity key changed"
            finish()
            return
        }
        val isGroupOwner = intent.getBooleanExtra(EXTRA_IS_GROUP_OWNER, false)
        val hostAddress = intent.getStringExtra(EXTRA_HOST_ADDRESS)

        tvCallStatus.text = if (isOutgoing) "Ringing…" else getString(R.string.call_connecting)

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
        val mint = ContextCompat.getColor(this, R.color.status_online)
        val amber = ContextCompat.getColor(this, R.color.status_discovering)
        val rose = ContextCompat.getColor(this, R.color.status_offline)

        when (state) {
            CallSessionBus.CallState.RINGING -> {
                tvCallStatus.text = "Ringing…"
                tvTimerState.text = getString(R.string.call_timer_ringing)
                styleTimerBadge(amber)
                updateDurationUi()
            }
            CallSessionBus.CallState.CONNECTING -> {
                tvCallStatus.text = getString(R.string.call_connecting)
                tvTimerState.text = getString(R.string.call_timer_connecting)
                styleTimerBadge(amber)
                updateDurationUi()
            }
            CallSessionBus.CallState.CONNECTED -> {
                tvCallStatus.text = getString(R.string.call_connected_webrtc)
                tvTimerState.text = getString(R.string.call_timer_live)
                styleTimerBadge(mint)
                startDurationTimerLoop()
            }
            CallSessionBus.CallState.RECONNECTING -> {
                tvCallStatus.text = getString(R.string.call_reconnecting)
                tvTimerState.text = getString(R.string.call_timer_reconnecting)
                styleTimerBadge(amber)
                updateDurationUi()
            }
            CallSessionBus.CallState.ENDED -> {
                tvCallStatus.text = getString(R.string.call_ended)
                tvTimerState.text = getString(R.string.call_timer_ended)
                styleTimerBadge(rose)
                stopDurationTimerLoop()
            }
        }
    }

    private fun styleTimerBadge(color: Int) {
        tvTimerState.setTextColor(color)
        (viewTimerDot.background?.mutate() as? GradientDrawable)?.setColor(color)
        viewTimerDot.alpha = 1.0f
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
        recordCallHistoryIfNeeded()
        stopDurationTimerLoop()
        AudioCallService.endCall()
        CallSessionBus.removeListener(this)
        CallSessionBus.restoreVoiceAudio(this)
        CallSessionBus.clearSession()
        finish()
    }

    private fun recordCallHistoryIfNeeded() {
        if (callLogRecorded) return
        callLogRecorded = true
        val targetPid = peerId?.takeIf { it.isNotBlank() } ?: return
        val elapsedMs = CallSessionBus.elapsedCallMs()
        val label = if (elapsedMs > 0L) {
            val dur = CallSessionBus.formatDuration(elapsedMs)
            if (isOutgoing) "Outgoing audio call · $dur" else "Incoming audio call · $dur"
        } else {
            if (isOutgoing) "Outgoing audio call (unanswered)" else "Incoming audio call"
        }
        val appCtx = applicationContext
        val name = peerName
        val outgoing = isOutgoing
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                ChatRepository.get(appCtx).insertCallLog(
                    peerId = targetPid,
                    peerName = name,
                    callType = Constants.CALL_TYPE_AUDIO,
                    summary = label,
                    isSentByMe = outgoing
                )
            }
        }
    }

    // ======================== Duration Timer ========================

    private fun startDurationTimerLoop() {
        updateDurationUi()
        if (timerLoopRunning) return
        timerLoopRunning = true
        durationHandler.removeCallbacks(durationRunnable)
        durationHandler.postDelayed(durationRunnable, TIMER_TICK_MS)
    }

    private fun stopDurationTimerLoop() {
        timerLoopRunning = false
        durationHandler.removeCallbacks(durationRunnable)
    }

    private fun updateDurationUi() {
        val formatted = CallSessionBus.formatDuration(CallSessionBus.elapsedCallMs())
        tvCallDuration.text = formatted
        tvTimerOverlayDuration.text = formatted
    }

    override fun onDestroy() {
        stopDurationTimerLoop()
        CallSessionBus.removeListener(this)
        if (!endedLocally && isFinishing) {
            recordCallHistoryIfNeeded()
            AudioCallService.endCall()
            CallSessionBus.restoreVoiceAudio(this)
            CallSessionBus.clearSession()
        }
        super.onDestroy()
    }
}
