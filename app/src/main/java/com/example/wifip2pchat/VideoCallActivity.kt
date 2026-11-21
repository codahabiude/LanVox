package com.example.wifip2pchat

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * Full-screen Activity for a video call.
 *
 * Shows:
 *  - Remote peer's video on the full-screen SurfaceView
 *  - Local camera as a small picture-in-picture in the corner
 *  - Controls: mute, end call, switch camera, speaker
 */
class VideoCallActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "VideoCallActivity"
        const val EXTRA_IS_GROUP_OWNER = "extra_is_group_owner"
        const val EXTRA_HOST_ADDRESS = "extra_host_address"
    }

    // Views
    private lateinit var surfaceRemote: SurfaceView
    private lateinit var surfaceLocal: SurfaceView
    private lateinit var tvCallerName: TextView
    private lateinit var tvCallDuration: TextView
    private lateinit var btnMute: MaterialButton
    private lateinit var btnEndCall: MaterialButton
    private lateinit var btnSwitchCamera: MaterialButton
    private lateinit var btnSpeaker: MaterialButton

    private var isMuted = false
    private var isSpeakerOn = false
    private var callStartTime = 0L
    private val durationHandler = Handler(Looper.getMainLooper())
    private var durationRunnable: Runnable? = null
    private var remoteSurfaceReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_call)

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        initViews()
        setupRemoteSurface()
        startVideoCall()
    }

    private fun initViews() {
        surfaceRemote = findViewById(R.id.surfaceRemote)
        surfaceLocal = findViewById(R.id.surfaceLocal)
        tvCallerName = findViewById(R.id.tvCallerName)
        tvCallDuration = findViewById(R.id.tvCallDuration)
        btnMute = findViewById(R.id.btnMute)
        btnEndCall = findViewById(R.id.btnEndCall)
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera)
        btnSpeaker = findViewById(R.id.btnSpeaker)

        btnMute.setOnClickListener { toggleMute() }
        btnEndCall.setOnClickListener { endCall() }
        btnSwitchCamera.setOnClickListener { VideoCallService.switchCamera() }
        btnSpeaker.setOnClickListener { toggleSpeaker() }
    }

    private fun setupRemoteSurface() {
        surfaceRemote.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                remoteSurfaceReady = true
                Log.d(TAG, "Remote surface ready")
            }

            override fun surfaceChanged(holder: SurfaceHolder, fmt: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                remoteSurfaceReady = false
            }
        })
    }

    private fun startVideoCall() {
        val isGroupOwner = intent.getBooleanExtra(EXTRA_IS_GROUP_OWNER, false)
        val hostAddress = intent.getStringExtra(EXTRA_HOST_ADDRESS)

        // Set local preview surface for the camera
        surfaceLocal.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                VideoCallService.localPreviewSurface = holder.surface
            }
            override fun surfaceChanged(holder: SurfaceHolder, fmt: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                VideoCallService.localPreviewSurface = null
            }
        })

        // Start video call service
        val videoIntent = Intent(this, VideoCallService::class.java)
        startService(videoIntent)

        // Start audio call service (simultaneous audio)
        val audioIntent = Intent(this, AudioCallService::class.java)
        startService(audioIntent)

        // Setup callback for received video frames
        VideoCallService.onRemoteVideoFrame = { frameBytes ->
            displayRemoteFrame(frameBytes)
        }

        // Give services time to initialize, then start streaming
        Handler(Looper.getMainLooper()).postDelayed({
            // Start video
            if (isGroupOwner) {
                VideoCallService.startAsGroupOwner()
            } else if (hostAddress != null) {
                VideoCallService.connectToGroupOwner(hostAddress)
            }

            // Start audio
            if (isGroupOwner) {
                AudioCallService.startAsGroupOwner()
            } else if (hostAddress != null) {
                AudioCallService.connectToGroupOwner(hostAddress)
            }

            callStartTime = System.currentTimeMillis()
            startDurationTimer()
        }, 800)
    }

    /**
     * Decode and display a received JPEG frame on the remote SurfaceView.
     */
    private fun displayRemoteFrame(jpegBytes: ByteArray) {
        if (!remoteSurfaceReady) return

        try {
            val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return
            val canvas = surfaceRemote.holder.lockCanvas() ?: run {
                bitmap.recycle()
                return
            }

            try {
                // Scale bitmap to fill the surface
                val scaledBitmap = Bitmap.createScaledBitmap(
                    bitmap, canvas.width, canvas.height, true
                )
                canvas.drawBitmap(scaledBitmap, 0f, 0f, null)
                if (scaledBitmap !== bitmap) scaledBitmap.recycle()
            } finally {
                surfaceRemote.holder.unlockCanvasAndPost(canvas)
                bitmap.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Display frame error: ${e.message}")
        }
    }

    private fun toggleMute() {
        isMuted = !isMuted
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.isMicrophoneMute = isMuted
        btnMute.alpha = if (isMuted) 0.5f else 1.0f
        Toast.makeText(this, if (isMuted) "Muted" else "Unmuted", Toast.LENGTH_SHORT).show()
    }

    private fun toggleSpeaker() {
        isSpeakerOn = !isSpeakerOn
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.isSpeakerphoneOn = isSpeakerOn
        btnSpeaker.alpha = if (isSpeakerOn) 1.0f else 0.5f
        Toast.makeText(this, if (isSpeakerOn) "Speaker on" else "Speaker off", Toast.LENGTH_SHORT).show()
    }

    private fun endCall() {
        VideoCallService.endCall()
        AudioCallService.endCall()
        VideoCallService.onRemoteVideoFrame = null
        VideoCallService.localPreviewSurface = null
        durationRunnable?.let { durationHandler.removeCallbacks(it) }
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
        VideoCallService.onRemoteVideoFrame = null
        VideoCallService.localPreviewSurface = null
        durationRunnable?.let { durationHandler.removeCallbacks(it) }
        super.onDestroy()
    }

    @Deprecated("Use OnBackInvokedCallback for API 33+")
    override fun onBackPressed() {
        Toast.makeText(this, "Tap End Call to hang up", Toast.LENGTH_SHORT).show()
    }
}
