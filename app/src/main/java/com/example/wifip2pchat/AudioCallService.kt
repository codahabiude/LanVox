package com.example.wifip2pchat

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.SecretKey

/**
 * Foreground service (`foregroundServiceType="microphone"`) that hosts the
 * WebRTC P2P audio stack for stable background voice streaming over Wi-Fi Direct.
 *
 * Key architecture:
 *  - Uses [WebRtcAudioCallManager] (`org.webrtc.PeerConnection`, `JavaAudioDeviceModule`,
 *    hardware AEC/NS/AGC, Opus RTP/SRTP transport & NetEQ adaptive jitter buffer)
 *  - Exchanges WebRTC SDP Offer/Answer and ICE candidates as newline-delimited JSON
 *    over a dedicated TCP signaling link on [Constants.AUDIO_PORT]
 *  - Holds [PowerManager.PARTIAL_WAKE_LOCK] and a high-perf / low-latency [WifiManager.WifiLock]
 *    so WebRTC audio keeps streaming cleanly when the screen locks or app is backgrounded
 *  - Exposes an ongoing call notification with return-to-call tap target, Mute/Unmute,
 *    and End Call actions synchronized with [CallSessionBus]
 */
class AudioCallService : Service(), WebRtcAudioCallManager.Listener {

    companion object {
        private const val TAG = "AudioCallService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "audio_call_channel"

        const val EXTRA_IS_GROUP_OWNER = "extra_is_group_owner"
        const val EXTRA_HOST_ADDRESS = "extra_host_address"
        const val EXTRA_NOTIFY_CALL_BUS = "extra_notify_call_bus"
        const val EXTRA_PEER_ID = "extra_peer_id"
        const val EXTRA_PEER_NAME = "extra_peer_name"

        private const val ACTION_TOGGLE_MUTE = "com.example.lanvox.ACTION_CALL_TOGGLE_MUTE"
        private const val ACTION_END_CALL = "com.example.lanvox.ACTION_CALL_END"
        private const val WAKE_LOCK_TIMEOUT_MS = 4 * 60 * 60 * 1000L

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var isMuted = false
            private set

        private var instance: AudioCallService? = null

        fun setMuted(muted: Boolean) {
            isMuted = muted
            CallSessionBus.notifyMuteChanged(muted)
            instance?.onMuteUpdated(muted)
        }

        fun endCall() {
            instance?.endCallInternal(notifyBus = false, sendBye = true, reason = "Call ended")
        }

        fun startAsGroupOwner(notifyBus: Boolean = true) {
            instance?.startSessionInternal(
                isGroupOwner = true,
                hostAddress = null,
                notifyBus = notifyBus,
                peerId = null,
                peerName = null
            )
        }

        fun connectToGroupOwner(hostAddress: String, notifyBus: Boolean = true) {
            instance?.startSessionInternal(
                isGroupOwner = false,
                hostAddress = hostAddress,
                notifyBus = notifyBus,
                peerId = null,
                peerName = null
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionStarted = AtomicBoolean(false)
    private val terminating = AtomicBoolean(false)
    private val signalWriteLock = Any()

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var signalingSocket: Socket? = null
    @Volatile private var signalWriter: BufferedWriter? = null
    @Volatile private var signalingSessionKey: SecretKey? = null
    private val outSignalSeq = AtomicLong(0L)
    private val lastInSignalSeq = AtomicLong(0L)
    private val pendingOutgoingSignals = mutableListOf<String>()

    private var webRtcManager: WebRtcAudioCallManager? = null
    private var connectJob: Job? = null
    private var readJob: Job? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile private var notifyCallBus = true
    @Volatile private var isGroupOwnerRole = false
    @Volatile private var peerId: String? = null
    @Volatile private var peerName: String = "Peer"
    @Volatile private var isWebRtcConnected = false
    @Volatile private var isReconnecting = false
    @Volatile private var connectedSinceMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        isMuted = false
        terminating.set(false)
        instance = this
        createNotificationChannel()

        val hasMicPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMicPermission) {
            Log.e(TAG, "RECORD_AUDIO permission missing — stopping AudioCallService")
            CallSessionBus.notifyEnded("Microphone permission required")
            stopSelf()
            return
        }

        try {
            val notification = buildNotification(getString(R.string.call_notif_connecting))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (se: SecurityException) {
            Log.e(TAG, "Cannot start microphone foreground service: ${se.message}")
            CallSessionBus.notifyEnded("Microphone permission denied")
            stopSelf()
            return
        }

        acquireBackgroundLocks()
        Log.d(TAG, "WebRTC AudioCallService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_MUTE -> {
                setMuted(!isMuted)
                return START_NOT_STICKY
            }
            ACTION_END_CALL -> {
                val hangupType = if (notifyCallBus) Constants.CALL_TYPE_AUDIO else Constants.CALL_TYPE_VIDEO
                val hangupPayload = Constants.MSG_TYPE_CALL_END + hangupType
                if (!MainActivity.sendControlToPeerStatic(peerId, hangupPayload)) {
                    CallListenerService.sendControl(hangupPayload, peerId)
                }
                endCallInternal(notifyBus = true, sendBye = true, reason = "Call ended")
                return START_NOT_STICKY
            }
        }

        if (intent != null && intent.hasExtra(EXTRA_IS_GROUP_OWNER)) {
            val isGo = intent.getBooleanExtra(EXTRA_IS_GROUP_OWNER, false)
            val host = intent.getStringExtra(EXTRA_HOST_ADDRESS)
            val notify = intent.getBooleanExtra(EXTRA_NOTIFY_CALL_BUS, true)
            val targetPeerId = intent.getStringExtra(EXTRA_PEER_ID)
            val targetPeerName = intent.getStringExtra(EXTRA_PEER_NAME)
            startSessionInternal(isGo, host, notify, targetPeerId, targetPeerName)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        isMuted = false
        instance = null
        connectJob?.cancel()
        readJob?.cancel()
        webRtcManager?.dispose()
        webRtcManager = null
        closeSignalingSockets()
        releaseBackgroundLocks()
        scope.cancel()
        super.onDestroy()
        Log.d(TAG, "WebRTC AudioCallService destroyed")
    }

    // ======================== Session Lifecycle ========================

    private fun startSessionInternal(
        isGroupOwner: Boolean,
        hostAddress: String?,
        notifyBus: Boolean,
        peerId: String?,
        peerName: String?
    ) {
        if (!isRunning) return
        if (!sessionStarted.compareAndSet(false, true)) return

        notifyCallBus = notifyBus
        isGroupOwnerRole = isGroupOwner
        this.peerId = peerId
        this.peerName = peerName?.takeIf { it.isNotBlank() } ?: "Peer"

        webRtcManager = WebRtcAudioCallManager(this, this)
        refreshNotification()

        if (isGroupOwner) {
            startSignalingServer()
        } else if (!hostAddress.isNullOrBlank()) {
            connectSignalingClient(hostAddress)
        } else {
            Log.e(TAG, "Missing hostAddress for client WebRTC audio call")
            if (notifyCallBus) CallSessionBus.notifyEnded("Missing peer address")
            stopSelf()
        }
    }

    private fun startSignalingServer() {
        connectJob = scope.launch {
            try {
                val srv = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(Constants.AUDIO_PORT))
                }
                serverSocket = srv
                Log.d(TAG, "WebRTC audio signaling server listening on port ${Constants.AUDIO_PORT}")
                val conn = srv.accept()
                onSignalingSocketReady(conn)
            } catch (e: Exception) {
                if (scope.isActive && !terminating.get()) {
                    Log.e(TAG, "GO WebRTC signaling error: ${e.message}")
                    endCallInternal(
                        notifyBus = true,
                        sendBye = false,
                        reason = "Audio connection lost"
                    )
                }
            }
        }
    }

    private fun connectSignalingClient(hostAddress: String) {
        connectJob = scope.launch {
            repeat(30) { attempt ->
                if (!isRunning || terminating.get() || !isActive) return@launch
                try {
                    val conn = Socket()
                    conn.tcpNoDelay = true
                    conn.connect(InetSocketAddress(hostAddress, Constants.AUDIO_PORT), 3_000)
                    Log.d(TAG, "Connected to GO WebRTC signaling at $hostAddress:${Constants.AUDIO_PORT}")
                    onSignalingSocketReady(conn)
                    return@launch
                } catch (e: Exception) {
                    Log.d(TAG, "WebRTC signaling connect attempt ${attempt + 1} failed: ${e.message}")
                    delay(1_000)
                }
            }
            Log.e(TAG, "Could not reach peer audio signaling port after 30 attempts")
            endCallInternal(notifyBus = true, sendBye = false, reason = "Peer did not answer")
        }
    }

    private fun onSignalingSocketReady(conn: Socket) {
        conn.tcpNoDelay = true
        conn.keepAlive = true
        signalingSocket = conn

        val writer = BufferedWriter(OutputStreamWriter(conn.getOutputStream(), Charsets.UTF_8))
        val reader = BufferedReader(InputStreamReader(conn.getInputStream(), Charsets.UTF_8))

        val sessionKey = try {
            conn.soTimeout = 10_000
            val ephKp = CryptoManager.generateEcP256KeyPair()
            val selfPub = ephKp.public.encoded
            writer.write(
                JSONObject()
                    .put("type", "ecdh")
                    .put("pub", CryptoManager.b64UrlEncode(selfPub))
                    .toString()
            )
            writer.newLine()
            writer.flush()

            val hsLine = reader.readLine()
                ?: throw IllegalStateException("Peer closed socket during audio ECDH handshake")
            val hsJson = JSONObject(hsLine)
            require(hsJson.optString("type") == "ecdh") { "Unexpected audio signaling handshake" }
            val remotePubBytes = CryptoManager.b64UrlDecode(hsJson.getString("pub"))
            val remoteEphPub = CryptoManager.decodeEcPublicKey(remotePubBytes)
            val ephSecret = CryptoManager.computeEcdhSecret(ephKp.private, remoteEphPub)
            CryptoManager.deriveAudioSignalingSessionKey(
                context = applicationContext,
                peerId = peerId,
                ephSecret = ephSecret,
                ephPubA = selfPub,
                ephPubB = remoteEphPub.encoded
            )
        } finally {
            runCatching { conn.soTimeout = 0 }
        }

        outSignalSeq.set(0L)
        lastInSignalSeq.set(0L)
        signalingSessionKey = sessionKey

        synchronized(signalWriteLock) {
            signalWriter = writer
            if (pendingOutgoingSignals.isNotEmpty()) {
                pendingOutgoingSignals.forEach { msg ->
                    val encLine = CryptoManager.encryptSignalingLine(
                        sessionKey,
                        outSignalSeq.incrementAndGet(),
                        msg
                    )
                    writer.write(encLine)
                    writer.newLine()
                }
                writer.flush()
                pendingOutgoingSignals.clear()
            }
        }

        // Start WebRTC engine (DTLS-SRTP media); Group Owner sends the initial SDP offer
        webRtcManager?.initialize(isOfferer = isGroupOwnerRole)
        startSignalingReader(reader)
    }

    private fun startSignalingReader(reader: BufferedReader) {
        readJob = scope.launch {
            try {
                while (isActive && !terminating.get()) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    handleSignalingMessage(line)
                }
                if (!terminating.get()) {
                    Log.d(TAG, "Peer closed WebRTC signaling socket")
                    endCallInternal(notifyBus = true, sendBye = false, reason = "Call ended")
                }
            } catch (e: Exception) {
                if (scope.isActive && !terminating.get()) {
                    Log.w(TAG, "WebRTC signaling reader ended: ${e.message}")
                    endCallInternal(notifyBus = true, sendBye = false, reason = "Call ended")
                }
            }
        }
    }

    private fun handleSignalingMessage(rawLine: String) {
        try {
            val key = signalingSessionKey ?: return
            val (seq, rawJson) = CryptoManager.decryptSignalingLine(key, rawLine) ?: run {
                Log.w(TAG, "Dropped unencrypted or tampered signaling payload")
                return
            }
            if (seq <= lastInSignalSeq.get()) {
                Log.w(TAG, "Dropped out-of-order/replayed signaling seq=$seq")
                return
            }
            lastInSignalSeq.set(seq)

            val json = JSONObject(rawJson)
            when (json.optString("type")) {
                "offer" -> {
                    val sdp = json.getString("sdp")
                    Log.d(TAG, "Received E2EE WebRTC SDP offer")
                    webRtcManager?.onRemoteOfferReceived(sdp)
                }
                "answer" -> {
                    val sdp = json.getString("sdp")
                    Log.d(TAG, "Received E2EE WebRTC SDP answer")
                    webRtcManager?.onRemoteAnswerReceived(sdp)
                }
                "ice" -> {
                    val sdpMid = json.optString("sdpMid")
                    val mLineIndex = json.optInt("sdpMLineIndex", 0)
                    val candidate = json.getString("candidate")
                    webRtcManager?.onRemoteIceCandidateReceived(sdpMid, mLineIndex, candidate)
                }
                "bye" -> {
                    Log.d(TAG, "Peer sent E2EE WebRTC bye")
                    endCallInternal(notifyBus = true, sendBye = false, reason = "Call ended")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ignoring malformed signaling payload: ${e.message}")
        }
    }

    private fun sendSignalingJson(json: JSONObject) {
        val serialized = json.toString()
        scope.launch {
            if (terminating.get()) return@launch
            try {
                synchronized(signalWriteLock) {
                    val writer = signalWriter
                    val key = signalingSessionKey
                    if (writer == null || key == null) {
                        pendingOutgoingSignals.add(serialized)
                    } else {
                        val encLine = CryptoManager.encryptSignalingLine(
                            key,
                            outSignalSeq.incrementAndGet(),
                            serialized
                        )
                        writer.write(encLine)
                        writer.newLine()
                        writer.flush()
                    }
                }
            } catch (e: Exception) {
                if (!terminating.get()) {
                    Log.w(TAG, "Failed to write signaling message: ${e.message}")
                }
            }
        }
    }

    // ======================== WebRtcAudioCallManager.Listener ========================

    override fun onLocalOffer(sdp: SessionDescription) {
        sendSignalingJson(JSONObject().apply {
            put("type", "offer")
            put("sdp", sdp.description)
        })
    }

    override fun onLocalAnswer(sdp: SessionDescription) {
        sendSignalingJson(JSONObject().apply {
            put("type", "answer")
            put("sdp", sdp.description)
        })
    }

    override fun onLocalIceCandidate(candidate: IceCandidate) {
        sendSignalingJson(JSONObject().apply {
            put("type", "ice")
            put("sdpMid", candidate.sdpMid ?: "")
            put("sdpMLineIndex", candidate.sdpMLineIndex)
            put("candidate", candidate.sdp)
        })
    }

    override fun onConnected() {
        isWebRtcConnected = true
        isReconnecting = false
        if (connectedSinceMs == 0L) {
            connectedSinceMs = System.currentTimeMillis()
        }
        refreshNotification()
        if (notifyCallBus && !terminating.get()) {
            CallSessionBus.notifyConnected()
        }
    }

    override fun onReconnecting() {
        isReconnecting = true
        refreshNotification()
        if (notifyCallBus && !terminating.get()) {
            CallSessionBus.notifyReconnecting()
        }
    }

    override fun onDisconnected(reason: String) {
        endCallInternal(notifyBus = true, sendBye = false, reason = reason)
    }

    override fun onError(message: String) {
        Log.e(TAG, "WebRTC error: $message")
        endCallInternal(notifyBus = true, sendBye = true, reason = message)
    }

    // ======================== Mute & Cleanup ========================

    private fun onMuteUpdated(muted: Boolean) {
        webRtcManager?.setMuted(muted)
        refreshNotification()
    }

    private fun closeSignalingSockets() {
        synchronized(signalWriteLock) {
            pendingOutgoingSignals.clear()
            try { signalWriter?.close() } catch (_: Exception) {}
            signalWriter = null
        }
        try { signalingSocket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        signalingSocket = null
        serverSocket = null
    }

    private fun endCallInternal(notifyBus: Boolean, sendBye: Boolean, reason: String) {
        if (!terminating.compareAndSet(false, true)) return
        if (notifyBus && notifyCallBus) {
            CallSessionBus.notifyEnded(reason)
        }
        connectJob?.cancel()
        readJob?.cancel()
        webRtcManager?.dispose()
        webRtcManager = null

        val writerSnapshot = synchronized(signalWriteLock) {
            val w = signalWriter
            signalWriter = null
            pendingOutgoingSignals.clear()
            w
        }
        val keySnapshot = signalingSessionKey
        signalingSessionKey = null
        val sockSnapshot = signalingSocket
        val srvSnapshot = serverSocket
        signalingSocket = null
        serverSocket = null

        Thread({
            if (sendBye && writerSnapshot != null && keySnapshot != null) {
                try {
                    val byeEnc = CryptoManager.encryptSignalingLine(
                        keySnapshot,
                        outSignalSeq.incrementAndGet(),
                        JSONObject().put("type", "bye").toString()
                    )
                    writerSnapshot.write(byeEnc)
                    writerSnapshot.newLine()
                    writerSnapshot.flush()
                } catch (_: Exception) {}
            }
            try { writerSnapshot?.close() } catch (_: Exception) {}
            try { sockSnapshot?.close() } catch (_: Exception) {}
            try { srvSnapshot?.close() } catch (_: Exception) {}
        }, "LanvoxCallBye").start()

        releaseBackgroundLocks()
        stopSelf()
    }

    // ======================== Wake & Wi-Fi Locks ========================

    @Suppress("DEPRECATION")
    private fun acquireBackgroundLocks() {
        try {
            val pm = getSystemService(POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lanvox:webrtc_audio_wake")?.apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire CPU wake lock: ${e.message}")
        }

        try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm?.createWifiLock(mode, "lanvox:webrtc_wifi_lock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire Wi-Fi lock: ${e.message}")
        }
    }

    private fun releaseBackgroundLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        wakeLock = null

        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {}
        wifiLock = null
    }

    // ======================== Ongoing Foreground Notification ========================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.call_notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.call_notif_channel_desc)
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun refreshNotification() {
        if (!isRunning || terminating.get()) return
        val statusText = when {
            isReconnecting -> getString(R.string.call_notif_reconnecting, peerName)
            isMuted && isWebRtcConnected -> getString(R.string.call_notif_muted, peerName)
            isWebRtcConnected -> getString(R.string.call_notif_active, peerName)
            else -> getString(R.string.call_notif_connecting)
        }
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    private fun buildNotification(statusText: String): Notification {
        val targetClass = if (notifyCallBus) AudioCallActivity::class.java else VideoCallActivity::class.java
        val openCallIntent = Intent(this, targetClass).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(AudioCallActivity.EXTRA_PEER_ID, peerId)
            putExtra(AudioCallActivity.EXTRA_PEER_NAME, peerName)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            100,
            openCallIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val muteIntent = PendingIntent.getService(
            this,
            101,
            Intent(this, AudioCallService::class.java).setAction(ACTION_TOGGLE_MUTE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val endIntent = PendingIntent.getService(
            this,
            102,
            Intent(this, AudioCallService::class.java).setAction(ACTION_END_CALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val muteLabel = if (isMuted) getString(R.string.unmute) else getString(R.string.mute)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .addAction(
                if (isMuted) R.drawable.ic_mic else R.drawable.ic_mic_off,
                muteLabel,
                muteIntent
            )
            .addAction(R.drawable.ic_phone_off, getString(R.string.end_call), endIntent)
            .apply {
                if (isWebRtcConnected && connectedSinceMs > 0L) {
                    setUsesChronometer(true)
                    setWhen(connectedSinceMs)
                }
            }
            .build()
    }
}
