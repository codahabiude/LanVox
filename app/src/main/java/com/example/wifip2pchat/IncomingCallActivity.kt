package com.example.wifip2pchat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

/**
 * Incoming call screen — shows accept/decline UI with ringtone + vibration.
 *
 * Works in BOTH modes:
 *  - App open:      launched by MainActivity when the peer calls
 *  - App closed:    launched by the full-screen notification of
 *                   [CallListenerService] (answer/decline go back over the
 *                   standby socket instead of the app's chat channel)
 *
 * Features:
 *  - Pulsing ring animation around avatar
 *  - System ringtone playback via CallRingtoneManager
 *  - Vibration pattern for haptic feedback
 *  - Accept → launches call activity, Decline → notifies peer
 *  - Screen wakes and shows over lock screen
 */
class IncomingCallActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "IncomingCallActivity"
        const val EXTRA_CALL_TYPE = "extra_call_type"
        const val EXTRA_IS_GROUP_OWNER = "extra_is_group_owner"
        const val EXTRA_HOST_ADDRESS = "extra_host_address"
        const val EXTRA_CALLER_NAME = "extra_caller_name"
        private const val CALL_PERMISSION_REQUEST = 2002

        @Volatile
        private var liveInstance: IncomingCallActivity? = null

        /** Called by CallListenerService when the caller cancels / timeout. */
        fun dismiss() {
            Handler(Looper.getMainLooper()).post {
                liveInstance?.finish()
            }
        }
    }

    private lateinit var tvCallerName: TextView
    private lateinit var tvCallType: TextView
    private lateinit var btnAccept: MaterialButton
    private lateinit var btnDecline: MaterialButton
    private lateinit var pulseRing: View

    private var isGroupOwner = false
    private var hostAddress: String? = null
    private var callType = Constants.CALL_TYPE_AUDIO
    private var waitingForPermissions = false

    // Pulse animation
    private val pulseAnimation = AlphaAnimation(1.0f, 0.2f).apply {
        duration = 800
        repeatMode = Animation.REVERSE
        repeatCount = Animation.INFINITE
    }

    // Scale bounce for accept/decline buttons
    private val bounceAnimation = android.view.animation.ScaleAnimation(
        0.9f, 1.0f, 0.9f, 1.0f,
        Animation.RELATIVE_TO_SELF, 0.5f,
        Animation.RELATIVE_TO_SELF, 0.5f
    ).apply {
        duration = 150
        interpolator = android.view.animation.OvershootInterpolator(2f)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_incoming_call)
        liveInstance = this

        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        callType = intent.getStringExtra(EXTRA_CALL_TYPE) ?: Constants.CALL_TYPE_AUDIO
        isGroupOwner = intent.getBooleanExtra(EXTRA_IS_GROUP_OWNER, false)
        hostAddress = intent.getStringExtra(EXTRA_HOST_ADDRESS)

        initViews()
        startRinging()
    }

    private fun initViews() {
        tvCallerName = findViewById(R.id.tvCallerName)
        tvCallType = findViewById(R.id.tvCallType)
        btnAccept = findViewById(R.id.btnAccept)
        btnDecline = findViewById(R.id.btnDecline)
        pulseRing = findViewById(R.id.pulseRing)

        val callerName = intent.getStringExtra(EXTRA_CALLER_NAME) ?: "Unknown"
        tvCallerName.text = callerName
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

        // Start pulse animation
        pulseRing.startAnimation(pulseAnimation)
    }

    private fun startRinging() {
        CallRingtoneManager.startRinging(this)
        Log.d(TAG, "Ringing — $callType call from peer")
    }

    /**
     * Send a control line to the peer. Prefers the standby channel while a
     * standby call is pending (its socket is the live one), falls back to
     * the app channel, then to the standby anyway.
     */
    private fun sendControl(message: String) {
        if (CallListenerService.hasPendingCall() && CallListenerService.sendControl(message)) return
        if (MainActivity.sendControlIfPossible(message)) return
        CallListenerService.sendControl(message)
    }

    private fun acceptCall() {
        pulseRing.clearAnimation()

        // Missing runtime permissions (app killed & relaunched from the
        // notification without MainActivity ever running)? Ask first.
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
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CALL_PERMISSION_REQUEST || !waitingForPermissions) return
        if (grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            proceedWithCall()
        } else {
            // Cannot take a call without mic (and camera for video)
            sendControl(Constants.MSG_TYPE_CALL_REJECT + callType)
            CallListenerService.onIncomingCallHandled()
            finish()
        }
    }

    private fun proceedWithCall() {
        waitingForPermissions = false

        // Notify peer that we accepted
        sendControl(Constants.MSG_TYPE_CALL_ACCEPT + callType)
        CallListenerService.onIncomingCallHandled()
        CallRingtoneManager.stopRinging()

        // Launch the appropriate call activity
        val intent = when (callType) {
            Constants.CALL_TYPE_VIDEO -> Intent(this, VideoCallActivity::class.java).apply {
                putExtra(VideoCallActivity.EXTRA_IS_GROUP_OWNER, isGroupOwner)
                putExtra(VideoCallActivity.EXTRA_HOST_ADDRESS, hostAddress)
            }
            else -> Intent(this, AudioCallActivity::class.java).apply {
                putExtra(AudioCallActivity.EXTRA_IS_GROUP_OWNER, isGroupOwner)
                putExtra(AudioCallActivity.EXTRA_HOST_ADDRESS, hostAddress)
            }
        }

        startActivity(intent)
        overridePendingTransition(R.anim.slide_up, android.R.anim.fade_out)
        finish()
    }

    private fun declineCall() {
        pulseRing.clearAnimation()

        // Notify peer that we declined
        sendControl(Constants.MSG_TYPE_CALL_REJECT + callType)
        CallListenerService.onIncomingCallHandled()
        CallRingtoneManager.stopRinging()

        overridePendingTransition(0, R.anim.slide_down)
        finish()
    }

    override fun onDestroy() {
        if (liveInstance === this) liveInstance = null
        pulseRing.clearAnimation()
        CallRingtoneManager.stopRinging()
        super.onDestroy()
    }

    override fun onBackPressed() {
        // Must explicitly accept or decline — tap a button
    }
}
