package com.example.wifip2pchat

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.Locale

/**
 * Coordinates live audio/video call signaling state, active call timing,
 * audio focus, and communication-mode routing across services and call activities.
 */
object CallSessionBus {

    private const val TAG = "CallSessionBus"
    private val mainHandler = Handler(Looper.getMainLooper())

    enum class CallState {
        RINGING,
        CONNECTING,
        CONNECTED,
        RECONNECTING,
        ENDED
    }

    interface Listener {
        fun onCallStateChanged(state: CallState) {}
        fun onCallEnded(reason: String) {}
        fun onMuteChanged(muted: Boolean) {}
    }

    @Volatile var activeCallType: String? = null
        private set
    @Volatile var activePeerId: String? = null
        private set
    @Volatile var currentState: CallState = CallState.ENDED
        private set
    @Volatile var currentMuted: Boolean = false
        private set
    @Volatile var connectedAtElapsedMs: Long? = null
        private set

    private val listeners = mutableSetOf<Listener>()

    private var savedAudioMode: Int? = null
    private var focusRequest: AudioFocusRequest? = null
    private var legacyFocusListener: AudioManager.OnAudioFocusChangeListener? = null

    fun startSession(callType: String, peerId: String?, initialState: CallState) {
        activeCallType = callType
        activePeerId = peerId
        currentMuted = false
        connectedAtElapsedMs = if (initialState == CallState.CONNECTED) {
            SystemClock.elapsedRealtime()
        } else {
            null
        }
        updateState(initialState)
    }

    fun clearSession() {
        activeCallType = null
        activePeerId = null
        currentMuted = false
        connectedAtElapsedMs = null
        currentState = CallState.ENDED
    }

    fun isCallActive(): Boolean = currentState != CallState.ENDED

    fun elapsedCallMs(): Long {
        val startedAt = connectedAtElapsedMs ?: return 0L
        return (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
    }

    fun formatDuration(elapsedMs: Long): String {
        val totalSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    fun addListener(listener: Listener) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun removeListener(listener: Listener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    fun updateState(state: CallState) {
        if (state == CallState.CONNECTED && connectedAtElapsedMs == null) {
            connectedAtElapsedMs = SystemClock.elapsedRealtime()
        } else if (state == CallState.ENDED) {
            connectedAtElapsedMs = null
        }
        currentState = state
        val snapshot = synchronized(listeners) { listeners.toList() }
        mainHandler.post {
            snapshot.forEach { it.onCallStateChanged(state) }
        }
    }

    fun notifyAccepted() {
        if (currentState == CallState.RINGING) {
            updateState(CallState.CONNECTING)
        }
    }

    fun notifyConnected() {
        if (connectedAtElapsedMs == null) {
            connectedAtElapsedMs = SystemClock.elapsedRealtime()
        }
        updateState(CallState.CONNECTED)
    }

    fun notifyReconnecting() {
        if (currentState != CallState.ENDED) {
            updateState(CallState.RECONNECTING)
        }
    }

    fun notifyMuteChanged(muted: Boolean) {
        currentMuted = muted
        val snapshot = synchronized(listeners) { listeners.toList() }
        mainHandler.post {
            snapshot.forEach { it.onMuteChanged(muted) }
        }
    }

    fun notifyEnded(reason: String) {
        currentState = CallState.ENDED
        currentMuted = false
        connectedAtElapsedMs = null
        val snapshot = synchronized(listeners) { listeners.toList() }
        mainHandler.post {
            snapshot.forEach { it.onCallEnded(reason) }
        }
    }

    /**
     * Request voice-communication audio focus and switch AudioManager to
     * [AudioManager.MODE_IN_COMMUNICATION] for hardware echo cancellation
     * and low-latency routing.
     */
    fun configureVoiceAudio(context: Context, speakerOnByDefault: Boolean) {
        val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return
        try {
            if (savedAudioMode == null) {
                savedAudioMode = am.mode
            }
            requestVoiceFocus(am)
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isMicrophoneMute = false
            am.isSpeakerphoneOn = speakerOnByDefault
        } catch (e: Exception) {
            Log.w(TAG, "Failed to configure voice audio mode: ${e.message}")
        }
    }

    fun restoreVoiceAudio(context: Context) {
        val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return
        try {
            am.isMicrophoneMute = false
            am.isSpeakerphoneOn = false
            savedAudioMode?.let { mode ->
                am.mode = mode
            }
            savedAudioMode = null
            abandonVoiceFocus(am)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore voice audio mode: ${e.message}")
        }
    }

    private fun requestVoiceFocus(am: AudioManager) {
        val focusListener = AudioManager.OnAudioFocusChangeListener { /* keep call focus */ }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            focusRequest = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
            legacyFocusListener = focusListener
        }
    }

    private fun abandonVoiceFocus(am: AudioManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            legacyFocusListener?.let { am.abandonAudioFocus(it) }
            legacyFocusListener = null
        }
    }
}
