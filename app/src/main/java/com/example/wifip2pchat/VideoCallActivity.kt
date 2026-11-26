package com.example.wifip2pchat

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Full-screen Activity for a bidirectional video call with a floating glassmorphic
 * active call timer overlay synchronized with [CallSessionBus].
 */
class VideoCallActivity : AppCompatActivity(), CallSessionBus.Listener {

    companion object {
        private const val TAG = "VideoCallActivity"
        const val EXTRA_IS_GROUP_OWNER = "extra_is_group_owner"
        const val EXTRA_HOST_ADDRESS = "extra_host_address"
        const val EXTRA_PEER_ID = "extra_peer_id"
        const val EXTRA_PEER_NAME = "extra_peer_name"
        const val EXTRA_IS_OUTGOING = "extra_is_outgoing"
        private const val TIMER_TICK_MS = 500L
    }

    // Views
    private lateinit var surfaceRemote: SurfaceView
    private lateinit var surfaceLocal: SurfaceView
    private lateinit var cardLocalPreview: MaterialCardView
    private lateinit var videoTopOverlay: LinearLayout
    private lateinit var videoBottomControls: LinearLayout
    private lateinit var viewTimerDot: View
    private lateinit var tvTimerState: TextView
    private lateinit var tvCallerName: TextView
    private lateinit var tvCallDuration: TextView
    private lateinit var btnMute: MaterialButton
    private lateinit var btnEndCall: MaterialButton
    private lateinit var btnSwitchCamera: MaterialButton
    private lateinit var btnSpeaker: MaterialButton

    private var peerId: String? = null
    private var peerName: String = "Connected Peer"
    private var isOutgoing = false

    private var isMuted = false
    private var isSpeakerOn = true
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

    @Volatile private var remoteSurfaceReady = false
    private val surfaceLock = Any()
    private val framePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val remoteFrameChannel = Channel<ByteArray>(Channel.CONFLATED)
    private var renderJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_call)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        peerId = intent.getStringExtra(EXTRA_PEER_ID)
        peerName = intent.getStringExtra(EXTRA_PEER_NAME)?.takeIf { it.isNotBlank() } ?: "Connected Peer"
        isOutgoing = intent.getBooleanExtra(EXTRA_IS_OUTGOING, false)

        initViews()
        applySafeAreaInsets()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Toast.makeText(this@VideoCallActivity, "Tap End Call to hang up", Toast.LENGTH_SHORT).show()
            }
        })

        val reattachingToLiveCall = CallSessionBus.isCallActive() &&
            VideoCallService.isRunning &&
            CallSessionBus.activeCallType == Constants.CALL_TYPE_VIDEO &&
            (peerId == null || CallSessionBus.activePeerId == peerId)

        CallSessionBus.addListener(this)
        if (reattachingToLiveCall) {
            isMuted = CallSessionBus.currentMuted
            updateMuteUi()
            onCallStateChanged(CallSessionBus.currentState)
            VideoCallService.onRemoteVideoFrame = { frameBytes ->
                remoteFrameChannel.trySend(frameBytes)
            }
        } else {
            CallSessionBus.configureVoiceAudio(this, speakerOnByDefault = true)
            CallSessionBus.startSession(
                callType = Constants.CALL_TYPE_VIDEO,
                peerId = peerId,
                initialState = if (isOutgoing) CallSessionBus.CallState.RINGING else CallSessionBus.CallState.CONNECTING
            )
            onCallStateChanged(CallSessionBus.currentState)
            startVideoCall()
        }

        setupRemoteSurface()
        setupLocalSurface()
        startFrameDecoder()
    }

    private fun initViews() {
        surfaceRemote = findViewById(R.id.surfaceRemote)
        surfaceLocal = findViewById(R.id.surfaceLocal)
        cardLocalPreview = findViewById(R.id.cardLocalPreview)
        videoTopOverlay = findViewById(R.id.videoTopOverlay)
        videoBottomControls = findViewById(R.id.videoBottomControls)
        viewTimerDot = findViewById(R.id.viewTimerDot)
        tvTimerState = findViewById(R.id.tvTimerState)
        tvCallerName = findViewById(R.id.tvCallerName)
        tvCallDuration = findViewById(R.id.tvCallDuration)
        btnMute = findViewById(R.id.btnMute)
        btnEndCall = findViewById(R.id.btnEndCall)
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera)
        btnSpeaker = findViewById(R.id.btnSpeaker)

        // Ensure local PiP SurfaceView always renders on top of surfaceRemote
        surfaceLocal.setZOrderMediaOverlay(true)

        tvCallerName.text = peerName
        tvCallDuration.text = getString(R.string.call_timer_default)
        updateMuteUi()
        updateSpeakerUi()

        btnMute.setOnClickListener { toggleMute() }
        btnEndCall.setOnClickListener { endCall() }
        btnSwitchCamera.setOnClickListener { VideoCallService.switchCamera() }
        btnSpeaker.setOnClickListener { toggleSpeaker() }
    }

    private fun applySafeAreaInsets() {
        val density = resources.displayMetrics.density
        val baseTopPad = (14 * density).toInt()
        val baseBottomPad = (24 * density).toInt()
        val baseSidePad = (16 * density).toInt()
        val baseBottomSidePad = (24 * density).toInt()
        val basePipTop = (86 * density).toInt()
        val basePipEnd = (14 * density).toInt()

        val root = findViewById<View>(R.id.videoCallRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            videoTopOverlay.setPadding(
                baseSidePad + bars.left,
                baseTopPad + bars.top,
                baseSidePad + bars.right,
                videoTopOverlay.paddingBottom
            )
            videoBottomControls.setPadding(
                baseBottomSidePad + bars.left,
                baseBottomPad,
                baseBottomSidePad + bars.right,
                baseBottomPad + bars.bottom
            )
            val pipLp = cardLocalPreview.layoutParams as ViewGroup.MarginLayoutParams
            pipLp.topMargin = basePipTop + bars.top
            pipLp.marginEnd = basePipEnd + bars.right
            cardLocalPreview.layoutParams = pipLp
            insets
        }
    }

    private fun setupRemoteSurface() {
        surfaceRemote.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                synchronized(surfaceLock) {
                    remoteSurfaceReady = true
                }
                Log.d(TAG, "Remote surface ready")
            }

            override fun surfaceChanged(holder: SurfaceHolder, fmt: Int, w: Int, h: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                synchronized(surfaceLock) {
                    remoteSurfaceReady = false
                }
            }
        })
    }

    private fun setupLocalSurface() {
        surfaceLocal.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                VideoCallService.attachLocalPreviewSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, fmt: Int, w: Int, h: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                VideoCallService.attachLocalPreviewSurface(null)
            }
        })
    }

    private fun startVideoCall() {
        if (!peerId.isNullOrBlank() && CryptoManager.isPeerKeyBlocked(this, peerId)) {
            finish()
            return
        }
        val isGroupOwner = intent.getBooleanExtra(EXTRA_IS_GROUP_OWNER, false)
        val hostAddress = intent.getStringExtra(EXTRA_HOST_ADDRESS)

        VideoCallService.onRemoteVideoFrame = { frameBytes ->
            remoteFrameChannel.trySend(frameBytes)
        }

        val videoIntent = Intent(this, VideoCallService::class.java).apply {
            putExtra(VideoCallService.EXTRA_IS_GROUP_OWNER, isGroupOwner)
            putExtra(VideoCallService.EXTRA_HOST_ADDRESS, hostAddress)
            putExtra(VideoCallService.EXTRA_PEER_ID, peerId)
        }
        ContextCompat.startForegroundService(this, videoIntent)

        val audioIntent = Intent(this, AudioCallService::class.java).apply {
            putExtra(AudioCallService.EXTRA_IS_GROUP_OWNER, isGroupOwner)
            putExtra(AudioCallService.EXTRA_HOST_ADDRESS, hostAddress)
            // VideoCallService drives primary CallSessionBus connection state
            putExtra(AudioCallService.EXTRA_NOTIFY_CALL_BUS, false)
            putExtra(AudioCallService.EXTRA_PEER_ID, peerId)
            putExtra(AudioCallService.EXTRA_PEER_NAME, peerName)
        }
        ContextCompat.startForegroundService(this, audioIntent)
    }

    private fun startFrameDecoder() {
        renderJob = lifecycleScope.launch(Dispatchers.Default) {
            for (jpegBytes in remoteFrameChannel) {
                if (!isActive || endedLocally) break
                displayRemoteFrame(jpegBytes)
            }
        }
    }

    /**
     * Decode and render a received JPEG frame onto [surfaceRemote] off the main thread,
     * preserving aspect ratio via center-crop.
     */
    private fun displayRemoteFrame(jpegBytes: ByteArray) {
        if (!remoteSurfaceReady) return

        val bitmap = try {
            BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        } catch (_: OutOfMemoryError) {
            null
        } ?: return

        try {
            synchronized(surfaceLock) {
                if (!remoteSurfaceReady) return
                val canvas: Canvas = surfaceRemote.holder.lockCanvas() ?: return
                try {
                    val cw = canvas.width
                    val ch = canvas.height
                    val bw = bitmap.width
                    val bh = bitmap.height
                    if (cw > 0 && ch > 0 && bw > 0 && bh > 0) {
                        val canvasRatio = cw.toFloat() / ch.toFloat()
                        val bitmapRatio = bw.toFloat() / bh.toFloat()
                        val srcRect = if (bitmapRatio > canvasRatio) {
                            val cropW = (bh * canvasRatio).toInt().coerceAtLeast(1)
                            val left = (bw - cropW) / 2
                            Rect(left, 0, left + cropW, bh)
                        } else {
                            val cropH = (bw / canvasRatio).toInt().coerceAtLeast(1)
                            val top = (bh - cropH) / 2
                            Rect(0, top, bw, top + cropH)
                        }
                        val dstRect = Rect(0, 0, cw, ch)
                        canvas.drawColor(Color.BLACK)
                        canvas.drawBitmap(bitmap, srcRect, dstRect, framePaint)
                    }
                } finally {
                    surfaceRemote.holder.unlockCanvasAndPost(canvas)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Display frame error: ${e.message}")
        } finally {
            bitmap.recycle()
        }
    }

    override fun onCallStateChanged(state: CallSessionBus.CallState) {
        if (isFinishing || isDestroyed) return
        val mint = ContextCompat.getColor(this, R.color.status_online)
        val amber = ContextCompat.getColor(this, R.color.status_discovering)
        val rose = ContextCompat.getColor(this, R.color.status_offline)

        when (state) {
            CallSessionBus.CallState.RINGING -> {
                tvTimerState.text = getString(R.string.call_timer_ringing)
                styleTimerBadge(amber)
                updateDurationUi()
            }
            CallSessionBus.CallState.CONNECTING -> {
                tvTimerState.text = getString(R.string.call_timer_connecting)
                styleTimerBadge(amber)
                updateDurationUi()
            }
            CallSessionBus.CallState.CONNECTED -> {
                tvTimerState.text = getString(R.string.call_timer_live)
                styleTimerBadge(mint)
                startDurationTimerLoop()
            }
            CallSessionBus.CallState.RECONNECTING -> {
                tvTimerState.text = getString(R.string.call_timer_reconnecting)
                styleTimerBadge(amber)
                updateDurationUi()
            }
            CallSessionBus.CallState.ENDED -> {
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
        btnMute.alpha = if (isMuted) 0.55f else 1.0f
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
        btnSpeaker.alpha = if (isSpeakerOn) 1.0f else 0.55f
    }

    private fun endCall() {
        if (endedLocally) return
        endedLocally = true
        val hangupPayload = Constants.MSG_TYPE_CALL_END + Constants.CALL_TYPE_VIDEO
        if (!MainActivity.sendControlToPeerStatic(peerId, hangupPayload)) {
            CallListenerService.sendControl(hangupPayload, peerId)
        }
        cleanupAndFinish()
    }

    private fun cleanupAndFinish() {
        recordCallHistoryIfNeeded()
        stopDurationTimerLoop()
        VideoCallService.onRemoteVideoFrame = null
        VideoCallService.attachLocalPreviewSurface(null)
        VideoCallService.endCall()
        AudioCallService.endCall()
        renderJob?.cancel()
        remoteFrameChannel.close()
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
            if (isOutgoing) "Outgoing video call · $dur" else "Incoming video call · $dur"
        } else {
            if (isOutgoing) "Outgoing video call (unanswered)" else "Incoming video call"
        }
        val appCtx = applicationContext
        val name = peerName
        val outgoing = isOutgoing
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                ChatRepository.get(appCtx).insertCallLog(
                    peerId = targetPid,
                    peerName = name,
                    callType = Constants.CALL_TYPE_VIDEO,
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
        tvCallDuration.text = CallSessionBus.formatDuration(CallSessionBus.elapsedCallMs())
    }

    override fun onDestroy() {
        stopDurationTimerLoop()
        renderJob?.cancel()
        remoteFrameChannel.close()
        CallSessionBus.removeListener(this)
        if (!endedLocally && isFinishing) {
            recordCallHistoryIfNeeded()
            VideoCallService.onRemoteVideoFrame = null
            VideoCallService.attachLocalPreviewSurface(null)
            VideoCallService.endCall()
            AudioCallService.endCall()
            CallSessionBus.restoreVoiceAudio(this)
            CallSessionBus.clearSession()
        }
        super.onDestroy()
    }
}
