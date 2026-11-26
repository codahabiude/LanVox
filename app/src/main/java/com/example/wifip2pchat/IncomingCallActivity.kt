package com.example.wifip2pchat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.OvershootInterpolator
import android.view.animation.ScaleAnimation
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Incoming call screen — shows accept/decline UI with ringtone + vibration.
 */
class IncomingCallActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "IncomingCallActivity"
        const val EXTRA_CALL_TYPE = "extra_call_type"
        const val EXTRA_IS_GROUP_OWNER = "extra_is_group_owner"
        const val EXTRA_HOST_ADDRESS = "extra_host_address"
        const val EXTRA_CALLER_NAME = "extra_caller_name"
        const val EXTRA_PEER_ID = "extra_peer_id"
        private const val CALL_PERMISSION_REQUEST = 2002

        @Volatile
        private var liveInstance: IncomingCallActivity? = null

        fun isShowing(): Boolean = liveInstance != null

        /** Called when the caller cancels or the ring times out. */
        fun dismiss() {
            Handler(Looper.getMainLooper()).post {
                liveInstance?.finish()
            }
        }
    }

    private lateinit var tvAvatarInitial: TextView
    private lateinit var ivIncomingPhoto: ImageView
    private lateinit var tvCallerName: TextView
    private lateinit var tvCallType: TextView
    private lateinit var btnAccept: MaterialButton
    private lateinit var btnDecline: MaterialButton
    private lateinit var pulseRing: View

    private var isGroupOwner = false
    private var hostAddress: String? = null
    private var peerId: String? = null
    private var callerName: String = "Unknown"
    private var callType = Constants.CALL_TYPE_AUDIO
    private var waitingForPermissions = false

    private val pulseAnimation = AlphaAnimation(1.0f, 0.2f).apply {
        duration = 800
        repeatMode = Animation.REVERSE
        repeatCount = Animation.INFINITE
    }

    private val bounceAnimation = ScaleAnimation(
        0.9f, 1.0f, 0.9f, 1.0f,
        Animation.RELATIVE_TO_SELF, 0.5f,
        Animation.RELATIVE_TO_SELF, 0.5f
    ).apply {
        duration = 150
        interpolator = OvershootInterpolator(2f)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_incoming_call)
        liveInstance = this

        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        callType = intent.getStringExtra(EXTRA_CALL_TYPE) ?: Constants.CALL_TYPE_AUDIO
        isGroupOwner = intent.getBooleanExtra(EXTRA_IS_GROUP_OWNER, false)
        hostAddress = intent.getStringExtra(EXTRA_HOST_ADDRESS)
        peerId = intent.getStringExtra(EXTRA_PEER_ID)
        callerName = intent.getStringExtra(EXTRA_CALLER_NAME)?.takeIf { it.isNotBlank() } ?: "Unknown"

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Must explicitly accept or decline
            }
        })

        initViews()
        startRinging()
    }

    private fun initViews() {
        tvAvatarInitial = findViewById(R.id.tvAvatarInitial)
        ivIncomingPhoto = findViewById(R.id.ivIncomingPhoto)
        tvCallerName = findViewById(R.id.tvCallerName)
        tvCallType = findViewById(R.id.tvCallType)
        btnAccept = findViewById(R.id.btnAccept)
        btnDecline = findViewById(R.id.btnDecline)
        pulseRing = findViewById(R.id.pulseRing)

        tvCallerName.text = callerName
        AvatarManager.bindAvatar(this, ivIncomingPhoto, tvAvatarInitial, peerId, callerName)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AvatarManager.avatarVersion.collect {
                    AvatarManager.bindAvatar(this@IncomingCallActivity, ivIncomingPhoto, tvAvatarInitial, peerId, callerName)
                }
            }
        }
        tvCallType.text = when (callType) {
            Constants.CALL_TYPE_VIDEO -> "Video Call"
            else -> "Audio Call"
        }

        btnAccept.setOnClickListener {
            it.startAnimation(bounceAnimation)
            acceptCall()
        }
        btnDecline.setOnClickListener {
            it.startAnimation(bounceAnimation)
            declineCall()
        }

        pulseRing.startAnimation(pulseAnimation)
    }

    private fun startRinging() {
        CallRingtoneManager.startRinging(this)
        Log.d(TAG, "Ringing — $callType call from $callerName")
    }

    private fun sendControl(message: String) {
        if (CallListenerService.hasPendingCall() && CallListenerService.sendControl(message, peerId)) return
        if (MainActivity.sendControlToPeerStatic(peerId, message)) return
        CallListenerService.sendControl(message, peerId)
    }

    private fun acceptCall() {
        pulseRing.clearAnimation()

        if (!peerId.isNullOrBlank() && CryptoManager.isPeerKeyBlocked(this, peerId)) {
            Log.w(TAG, "Cannot accept call from $peerId: peer key is blocked")
            sendControl(Constants.MSG_TYPE_CALL_REJECT + callType)
            CallListenerService.onIncomingCallHandled()
            CallRingtoneManager.stopRinging()
            finish()
            return
        }

        val missing = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) missing.add(Manifest.permission.RECORD_AUDIO)
        if (callType == Constants.CALL_TYPE_VIDEO &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) missing.add(Manifest.permission.CAMERA)

        if (missing.isNotEmpty()) {
            waitingForPermissions = true
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), CALL_PERMISSION_REQUEST)
            return
        }
        proceedWithCall()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CALL_PERMISSION_REQUEST || !waitingForPermissions) return
        if (grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            proceedWithCall()
        } else {
            sendControl(Constants.MSG_TYPE_CALL_REJECT + callType)
            CallListenerService.onIncomingCallHandled()
            finish()
        }
    }

    private fun proceedWithCall() {
        waitingForPermissions = false

        sendControl(Constants.MSG_TYPE_CALL_ACCEPT + callType)
        CallListenerService.onIncomingCallHandled()
        CallRingtoneManager.stopRinging()

        val intent = when (callType) {
            Constants.CALL_TYPE_VIDEO -> Intent(this, VideoCallActivity::class.java).apply {
                putExtra(VideoCallActivity.EXTRA_IS_GROUP_OWNER, isGroupOwner)
                putExtra(VideoCallActivity.EXTRA_HOST_ADDRESS, hostAddress)
                putExtra(VideoCallActivity.EXTRA_PEER_ID, peerId)
                putExtra(VideoCallActivity.EXTRA_PEER_NAME, callerName)
                putExtra(VideoCallActivity.EXTRA_IS_OUTGOING, false)
            }
            else -> Intent(this, AudioCallActivity::class.java).apply {
                putExtra(AudioCallActivity.EXTRA_IS_GROUP_OWNER, isGroupOwner)
                putExtra(AudioCallActivity.EXTRA_HOST_ADDRESS, hostAddress)
                putExtra(AudioCallActivity.EXTRA_PEER_ID, peerId)
                putExtra(AudioCallActivity.EXTRA_PEER_NAME, callerName)
                putExtra(AudioCallActivity.EXTRA_IS_OUTGOING, false)
            }
        }

        startActivity(intent)
        overridePendingTransition(R.anim.slide_up, android.R.anim.fade_out)
        finish()
    }

    private fun declineCall() {
        pulseRing.clearAnimation()

        sendControl(Constants.MSG_TYPE_CALL_REJECT + callType)
        CallListenerService.onIncomingCallHandled()
        CallRingtoneManager.stopRinging()

        val pid = peerId?.takeIf { it.isNotBlank() }
        if (pid != null) {
            val appCtx = applicationContext
            val label = if (callType == Constants.CALL_TYPE_VIDEO) "Declined video call" else "Declined audio call"
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    ChatRepository.get(appCtx).insertCallLog(
                        peerId = pid,
                        peerName = callerName,
                        callType = callType,
                        summary = label,
                        isSentByMe = false
                    )
                }
            }
        }

        overridePendingTransition(0, R.anim.slide_down)
        finish()
    }

    override fun onDestroy() {
        if (liveInstance === this) liveInstance = null
        pulseRing.clearAnimation()
        CallRingtoneManager.stopRinging()
        super.onDestroy()
    }
}
