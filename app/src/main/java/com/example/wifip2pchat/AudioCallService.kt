package com.example.wifip2pchat

import android.app.*
import android.content.Intent
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.*
import java.net.*

/**
 * Foreground service that handles audio calling over WiFi Direct.
 *
 * Architecture:
 *   Group Owner: listens on [Constants.AUDIO_PORT], accepts one connection
 *   Client: connects to GO's address on [Constants.AUDIO_PORT]
 *
 * Audio flow:
 *   Mic (AudioRecord) → OutputStream → [WiFi Direct] → InputStream → Speaker (AudioTrack)
 *
 * v4.1 latency fixes:
 *   - TCP_NODELAY on both sockets (kills Nagle's ~40ms batching delay)
 *   - AudioRecord/AudioTrack Builders with PERFORMANCE_MODE_LOW_LATENCY (API 26+)
 *   - Small playback buffer (~16ms) instead of the ~150ms default
 *   - Echo cancellation + noise suppression + AGC attached (zero extra latency)
 */
class AudioCallService : Service() {

    companion object {
        private const val TAG = "AudioCallService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "audio_call_channel"

        var isRunning = false
            private set
        var onRemoteAudioData: ((ByteArray, Int) -> Unit)? = null

        private var instance: AudioCallService? = null
        fun sendAudio(data: ByteArray, length: Int) {
            instance?.sendAudioInternal(data, length)
        }
        fun endCall() {
            instance?.endCallInternal()
        }
        fun startAsGroupOwner() {
            instance?.startAsGroupOwnerInternal()
        }
        fun connectToGroupOwner(hostAddress: String) {
            instance?.connectToGroupOwnerInternal(hostAddress)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var serverSocket: ServerSocket? = null
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null

    private var audioRecorder: android.media.AudioRecord? = null
    private var audioTrack: android.media.AudioTrack? = null
    private var isRecording = false
    private var isPlaying = false

    private var recordJob: Job? = null
    private var playJob: Job? = null
    private val audioEffects = mutableListOf<android.media.audiofx.AudioEffect>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        instance = this
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Audio call active"))
        Log.d(TAG, "Audio call service created")
    }

    override fun onDestroy() {
        isRunning = false
        instance = null
        stopAudio()
        closeSockets()
        scope.cancel()
        super.onDestroy()
        Log.d(TAG, "Audio call service destroyed")
    }

    // ======================== Server/Client Setup ========================

    fun startAsGroupOwnerInternal() {
        scope.launch {
            try {
                serverSocket = ServerSocket(Constants.AUDIO_PORT)
                Log.d(TAG, "Audio server listening on port ${Constants.AUDIO_PORT}")
                val conn = serverSocket?.accept() ?: return@launch
                // Kill Nagle's algorithm — biggest single latency win for TCP audio.
                conn.tcpNoDelay = true
                socket = conn
                outputStream = conn.getOutputStream()
                inputStream = conn.getInputStream()
                Log.d(TAG, "Audio client connected")
                startAudioStreaming()
                startAudioPlayback()
            } catch (e: Exception) {
                if (scope.isActive) Log.e(TAG, "GO audio error: ${e.message}")
            }
        }
    }

    fun connectToGroupOwnerInternal(hostAddress: String) {
        scope.launch {
            repeat(30) { attempt ->
                if (!isRunning) return@launch
                try {
                    val conn = Socket()
                    // Kill Nagle's algorithm — biggest single latency win for TCP audio.
                    conn.tcpNoDelay = true
                    conn.connect(InetSocketAddress(hostAddress, Constants.AUDIO_PORT), 3_000)
                    socket = conn
                    outputStream = conn.getOutputStream()
                    inputStream = conn.getInputStream()
                    Log.d(TAG, "Connected to GO audio at $hostAddress:${Constants.AUDIO_PORT}")
                    startAudioStreaming()
                    startAudioPlayback()
                    return@launch
                } catch (e: Exception) {
                    Log.d(TAG, "Audio connect attempt ${attempt + 1} failed: ${e.message}")
                    delay(1_000)
                }
            }
            Log.e(TAG, "Could not reach peer audio port after 30 attempts")
        }
    }

    // ======================== Audio Recording (Mic → Socket) ========================

    private fun startAudioStreaming() {
        val minBuf = android.media.AudioRecord.getMinBufferSize(
            Constants.AUDIO_SAMPLE_RATE,
            Constants.AUDIO_CHANNEL_CONFIG,
            Constants.AUDIO_FORMAT
        )

        if (minBuf == android.media.AudioRecord.ERROR || minBuf == android.media.AudioRecord.ERROR_BAD_VALUE || minBuf <= 0) {
            Log.e(TAG, "Invalid AudioRecord buffer size")
            return
        }

        audioRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.media.AudioRecord.Builder()
                .setAudioSource(android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    android.media.AudioFormat.Builder()
                        .setSampleRate(Constants.AUDIO_SAMPLE_RATE)
                        .setChannelMask(Constants.AUDIO_CHANNEL_CONFIG)
                        .setEncoding(Constants.AUDIO_FORMAT)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .build()
        } else {
            android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                Constants.AUDIO_SAMPLE_RATE,
                Constants.AUDIO_CHANNEL_CONFIG,
                Constants.AUDIO_FORMAT,
                minBuf * 2
            )
        }

        if (audioRecorder?.state != android.media.AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized")
            return
        }

        attachAudioEffects(audioRecorder?.audioSessionId ?: 0)

        audioRecorder?.startRecording()
        isRecording = true
        Log.d(TAG, "Audio recording started")

        val buffer = ByteArray(Constants.AUDIO_BUFFER_SIZE)
        recordJob = scope.launch {
            while (isRecording && isActive) {
                val read = audioRecorder?.read(buffer, 0, buffer.size) ?: -1
                if (read > 0) {
                    sendAudioInternal(buffer, read)
                }
            }
        }
    }

    private fun attachAudioEffects(sessionId: Int) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(sessionId)?.let {
                    it.enabled = true
                    audioEffects.add(it)
                }
            }
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(sessionId)?.let {
                    it.enabled = true
                    audioEffects.add(it)
                }
            }
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl.create(sessionId)?.let {
                    it.enabled = true
                    audioEffects.add(it)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Audio effects skipped: ${e.message}")
        }
    }

    // ======================== Audio Playback (Socket → Speaker) ========================

    private fun startAudioPlayback() {
        val minBuf = android.media.AudioTrack.getMinBufferSize(
            Constants.AUDIO_SAMPLE_RATE,
            android.media.AudioFormat.CHANNEL_OUT_MONO,
            Constants.AUDIO_FORMAT
        )

        if (minBuf <= 0) {
            Log.e(TAG, "Invalid AudioTrack buffer size")
            return
        }

        val targetFrames = 256  // ~16ms at 16 kHz
        val bufBytes = maxOf(targetFrames * 2, minBuf / 2)

        audioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.media.AudioTrack.Builder()
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    android.media.AudioFormat.Builder()
                        .setSampleRate(Constants.AUDIO_SAMPLE_RATE)
                        .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(Constants.AUDIO_FORMAT)
                        .build()
                )
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(android.media.AudioTrack.MODE_STREAM)
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        setPerformanceMode(android.media.AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    }
                }
                .build()
        } else {
            android.media.AudioTrack(
                android.media.AudioManager.STREAM_VOICE_CALL,
                Constants.AUDIO_SAMPLE_RATE,
                android.media.AudioFormat.CHANNEL_OUT_MONO,
                Constants.AUDIO_FORMAT,
                bufBytes,
                android.media.AudioTrack.MODE_STREAM
            )
        }

        if (audioTrack?.state != android.media.AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack not initialized")
            return
        }

        audioTrack?.play()
        isPlaying = true
        Log.d(TAG, "Audio playback started")

        val buffer = ByteArray(Constants.AUDIO_BUFFER_SIZE)
        playJob = scope.launch {
            try {
                while (isPlaying && isActive) {
                    val read = inputStream?.read(buffer) ?: -1
                    if (read > 0) {
                        audioTrack?.write(buffer, 0, read)
                        onRemoteAudioData?.invoke(buffer, read)
                    } else if (read == -1) {
                        break
                    }
                }
            } catch (e: Exception) {
                if (scope.isActive) Log.e(TAG, "Playback error: ${e.message}")
            }
        }
    }

    // ======================== Send Audio Data ========================

    private fun sendAudioInternal(data: ByteArray, length: Int) {
        try {
            outputStream?.write(data, 0, length)
            outputStream?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Audio send error: ${e.message}")
        }
    }

    // ======================== Cleanup ========================

    private fun stopAudio() {
        isRecording = false
        isPlaying = false
        recordJob?.cancel()
        playJob?.cancel()

        audioEffects.forEach { runCatching { it.enabled = false; it.release() } }
        audioEffects.clear()

        try { audioRecorder?.stop() } catch (_: Exception) {}
        try { audioRecorder?.release() } catch (_: Exception) {}
        audioRecorder = null

        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
    }

    private fun closeSockets() {
        try { outputStream?.close() } catch (_: Exception) {}
        try { inputStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        outputStream = null
        inputStream = null
        socket = null
        serverSocket = null
    }

    private fun endCallInternal() {
        stopAudio()
        closeSockets()
        stopSelf()
    }

    // ======================== Notification ========================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Call",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Audio call in progress"
                setSound(null, null)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LANVOX")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }
}
