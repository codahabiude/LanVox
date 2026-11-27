package com.example.wifip2pchat

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.SecretKey

/**
 * Foreground service that handles bidirectional JPEG-over-TCP video streaming over Wi-Fi Direct.
 *
 * Key engineering improvements:
 *  - Single conflated frame sender channel + reused DataOutputStream (eliminates TCP frame
 *    interleaving / protocol desync and OOM spikes when network bandwidth dips)
 *  - FPS pacing (~15 FPS) in OnImageAvailableListener
 *  - Sensor-aware JPEG_ORIENTATION + continuous-video autofocus
 *  - Dynamic [attachLocalPreviewSurface] handling late or recreated PiP surfaces safely
 *  - Serialized camera switching on [cameraHandler] and proper HandlerThread shutdown
 *  - SO_REUSEADDR, TCP_NODELAY, Android 14 foreground permission guard, and [CallSessionBus] events
 */
class VideoCallService : Service() {

    companion object {
        private const val TAG = "VideoCallService"
        private const val NOTIFICATION_ID = 3002
        private const val CHANNEL_ID = "video_call_channel"

        const val EXTRA_IS_GROUP_OWNER = "extra_is_group_owner"
        const val EXTRA_HOST_ADDRESS = "extra_host_address"
        const val EXTRA_PEER_ID = "extra_peer_id"

        private val MIN_FRAME_INTERVAL_MS = 1000L / Constants.VIDEO_FPS.coerceAtLeast(1)

        @Volatile
        var isRunning = false
            private set

        /** Callback to deliver received JPEG frames for decoding/display. */
        var onRemoteVideoFrame: ((ByteArray) -> Unit)? = null

        /** Surface for local camera preview — set dynamically by VideoCallActivity. */
        @Volatile
        var localPreviewSurface: Surface? = null

        private var instance: VideoCallService? = null

        fun attachLocalPreviewSurface(surface: Surface?) {
            localPreviewSurface = surface
            instance?.reconfigureSessionForPreviewSurface()
        }

        fun endCall() {
            instance?.endCallInternal(notifyBus = false, reason = "Call ended")
        }

        fun startAsGroupOwner(peerId: String? = null) {
            instance?.startSessionInternal(isGroupOwner = true, hostAddress = null, peerId = peerId)
        }

        fun connectToGroupOwner(hostAddress: String, peerId: String? = null) {
            instance?.startSessionInternal(isGroupOwner = false, hostAddress = hostAddress, peerId = peerId)
        }

        fun switchCamera() {
            instance?.switchCameraInternal()
        }

        internal fun establishAuthenticatedVideoChannel(
            context: android.content.Context,
            conn: Socket,
            expectedPeerId: String,
            expectedIp: String? = PeerRegistry.ipFor(expectedPeerId)
        ): CryptoManager.DirectionalCallKeys {
            require(expectedPeerId.isNotBlank() && expectedPeerId != PeerRegistry.selfId) {
                "Invalid intended peer for video call"
            }
            if (CryptoManager.isPeerKeyBlocked(context, expectedPeerId)) {
                throw SecurityException("Peer identity key changed and is blocked")
            }
            if (!CryptoManager.hasPeerKey(context, expectedPeerId)) {
                throw SecurityException("Missing saved identity key for $expectedPeerId")
            }
            val remoteIp = conn.inetAddress?.hostAddress
            if (!expectedIp.isNullOrBlank() && remoteIp != null && remoteIp != expectedIp) {
                throw SecurityException("Rejected video connection from unexpected IP $remoteIp (expected $expectedIp)")
            }

            conn.soTimeout = 10_000
            try {
                val ephKp = CryptoManager.generateEcP256KeyPair()
                val selfPub = ephKp.public.encoded
                val myNonce = CryptoManager.generateRandomNonce()
                val dos = DataOutputStream(conn.getOutputStream())
                dos.writeBytes("LVV2")
                dos.writeUTF(PeerRegistry.selfId)
                dos.writeShort(selfPub.size)
                dos.write(selfPub)
                dos.writeUTF(myNonce)
                dos.flush()

                val dis = DataInputStream(conn.getInputStream())
                val magic = ByteArray(4)
                dis.readFully(magic)
                require(magic.contentEquals("LVV2".toByteArray(Charsets.US_ASCII))) { "Invalid video E2EE magic" }
                val remotePeerId = dis.readUTF().trim()
                if (remotePeerId != expectedPeerId) {
                    throw SecurityException("Rejected video peer $remotePeerId (expected $expectedPeerId)")
                }
                val len = dis.readUnsignedShort()
                require(len in 32..256) { "Invalid video ECDH key length: $len" }
                val remotePubBytes = ByteArray(len)
                dis.readFully(remotePubBytes)
                val remoteEphPub = CryptoManager.decodeEcPublicKey(remotePubBytes)
                val remoteNonce = dis.readUTF().trim()
                require(remoteNonce.isNotBlank() && remoteNonce != myNonce) { "Invalid video peer nonce" }

                val authSig = CryptoManager.signCallHandshake(
                    context = context,
                    channelPurpose = "VIDEO_CALL",
                    verifierId = expectedPeerId,
                    verifierNonce = remoteNonce,
                    responderNonce = myNonce,
                    selfEphPub = selfPub,
                    peerEphPub = remoteEphPub.encoded
                ) ?: throw SecurityException("Unable to sign video call handshake")
                dos.writeUTF(authSig)
                dos.flush()

                val remoteSig = dis.readUTF().trim()
                val verified = CryptoManager.verifyCallHandshake(
                    context = context,
                    channelPurpose = "VIDEO_CALL",
                    signerId = expectedPeerId,
                    verifierNonce = myNonce,
                    responderNonce = remoteNonce,
                    signerEphPub = remoteEphPub.encoded,
                    verifierEphPub = selfPub,
                    sigB64 = remoteSig
                )
                if (!verified) {
                    throw SecurityException("Video call peer authentication failed for $expectedPeerId")
                }

                val ephSecret = CryptoManager.computeEcdhSecret(ephKp.private, remoteEphPub)
                return CryptoManager.deriveVideoStreamSessionKeys(
                    context = context,
                    peerId = expectedPeerId,
                    ephSecret = ephSecret,
                    ephPubA = selfPub,
                    ephPubB = remoteEphPub.encoded
                )
            } finally {
                runCatching { conn.soTimeout = 0 }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionStarted = AtomicBoolean(false)
    private val terminating = AtomicBoolean(false)
    private val isSwitchingCamera = AtomicBoolean(false)
    private val cameraLock = Any()

    // Network
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var outputStream: OutputStream? = null
    @Volatile private var inputStream: InputStream? = null
    @Volatile private var peerId: String? = null
    @Volatile private var sendVideoKey: SecretKey? = null
    @Volatile private var recvVideoKey: SecretKey? = null
    private val sendFrameSeq = AtomicLong(0L)
    private val recvFrameSeq = AtomicLong(0L)
    private val outgoingFrameChannel = Channel<ByteArray>(Channel.CONFLATED)

    // Camera
    private var cameraManager: CameraManager? = null
    @Volatile private var cameraDevice: CameraDevice? = null
    @Volatile private var captureSession: CameraCaptureSession? = null
    @Volatile private var imageReader: ImageReader? = null
    private var cameraHandler: Handler? = null
    private var cameraThread: HandlerThread? = null
    @Volatile private var isUsingFrontCamera = true
    @Volatile private var activeCameraId: String? = null
    @Volatile private var lastFrameSentAtMs = 0L

    // Jobs
    private var connectJob: Job? = null
    private var sendJob: Job? = null
    private var playJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        terminating.set(false)
        instance = this
        cameraManager = getSystemService(CAMERA_SERVICE) as? CameraManager
        setupCameraThread()
        createNotificationChannel()

        val hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

        if (!hasCamera || !hasMic) {
            Log.e(TAG, "Missing CAMERA/RECORD_AUDIO permissions — stopping VideoCallService")
            CallSessionBus.notifyEnded("Camera & mic permissions required")
            stopSelf()
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification("Video call active"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification("Video call active"))
            }
        } catch (se: SecurityException) {
            Log.e(TAG, "Cannot start camera foreground service: ${se.message}")
            CallSessionBus.notifyEnded("Camera permission denied")
            stopSelf()
        }
        Log.d(TAG, "Video call service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && intent.hasExtra(EXTRA_IS_GROUP_OWNER)) {
            val isGo = intent.getBooleanExtra(EXTRA_IS_GROUP_OWNER, false)
            val host = intent.getStringExtra(EXTRA_HOST_ADDRESS)
            val targetPeerId = intent.getStringExtra(EXTRA_PEER_ID)
            startSessionInternal(isGo, host, targetPeerId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        instance = null
        connectJob?.cancel()
        sendJob?.cancel()
        outgoingFrameChannel.close()
        stopStreaming()
        closeCamera()
        closeSockets()
        quitCameraThread()
        scope.cancel()
        super.onDestroy()
        Log.d(TAG, "Video call service destroyed")
    }

    // ======================== Server/Client Setup ========================

    private fun startSessionInternal(isGroupOwner: Boolean, hostAddress: String?, peerId: String? = null) {
        if (!isRunning) return
        if (!sessionStarted.compareAndSet(false, true)) return
        this.peerId = peerId

        if (peerId.isNullOrBlank() || peerId == PeerRegistry.selfId) {
            Log.e(TAG, "Refusing video call: missing intended peer ID")
            CallSessionBus.notifyEnded("Cannot verify peer identity")
            stopSelf()
            return
        }
        if (CryptoManager.isPeerKeyBlocked(applicationContext, peerId)) {
            Log.e(TAG, "Refusing video call: peer key changed and is blocked ($peerId)")
            CallSessionBus.notifyEnded("Peer identity key changed — verify before calling")
            stopSelf()
            return
        }
        if (!CryptoManager.hasPeerKey(applicationContext, peerId)) {
            Log.e(TAG, "Refusing video call: peer $peerId has no saved identity key")
            CallSessionBus.notifyEnded("Peer has no saved encryption key")
            stopSelf()
            return
        }

        if (isGroupOwner) {
            startAsGroupOwnerInternal()
        } else if (!hostAddress.isNullOrBlank()) {
            connectToGroupOwnerInternal(hostAddress)
        } else {
            Log.e(TAG, "Missing hostAddress for client video call")
            CallSessionBus.notifyEnded("Missing peer address")
            stopSelf()
        }
    }

    private fun startAsGroupOwnerInternal() {
        val targetPeerId = peerId ?: return
        connectJob = scope.launch {
            try {
                val srv = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(Constants.VIDEO_PORT))
                }
                serverSocket = srv
                Log.d(TAG, "Video server listening on port ${Constants.VIDEO_PORT} for $targetPeerId")
                repeat(10) {
                    if (!isActive || terminating.get()) return@launch
                    val conn = srv.accept()
                    conn.tcpNoDelay = true
                    val authenticated = try {
                        val keys = establishAuthenticatedVideoChannel(applicationContext, conn, targetPeerId)
                        socket = conn
                        outputStream = conn.getOutputStream()
                        inputStream = conn.getInputStream()
                        sendVideoKey = keys.sendKey
                        recvVideoKey = keys.recvKey
                        sendFrameSeq.set(0L)
                        recvFrameSeq.set(0L)
                        true
                    } catch (e: Exception) {
                        Log.w(TAG, "Rejected unauthorized video connection from ${conn.inetAddress?.hostAddress}: ${e.message}")
                        try { conn.close() } catch (_: Exception) {}
                        false
                    }
                    if (authenticated) {
                        Log.d(TAG, "Video client connected (authenticated E2EE stream ready)")
                        CallSessionBus.notifyConnected()
                        startVideoSender()
                        openCameraAndStream()
                        startVideoPlayback()
                        return@launch
                    }
                }
                CallSessionBus.notifyEnded("Video peer authentication failed")
                endCallInternal(notifyBus = false, reason = "Peer authentication failed")
            } catch (e: Exception) {
                if (scope.isActive && !terminating.get()) {
                    Log.e(TAG, "GO video error: ${e.message}")
                    CallSessionBus.notifyEnded("Video connection lost")
                    endCallInternal(notifyBus = false, reason = "GO video error")
                }
            }
        }
    }

    private fun connectToGroupOwnerInternal(hostAddress: String) {
        val targetPeerId = peerId ?: return
        connectJob = scope.launch {
            repeat(30) { attempt ->
                if (!isRunning || terminating.get() || !isActive) return@launch
                try {
                    val conn = Socket()
                    conn.tcpNoDelay = true
                    conn.connect(InetSocketAddress(hostAddress, Constants.VIDEO_PORT), 3_000)
                    try {
                        val keys = establishAuthenticatedVideoChannel(applicationContext, conn, targetPeerId)
                        socket = conn
                        outputStream = conn.getOutputStream()
                        inputStream = conn.getInputStream()
                        sendVideoKey = keys.sendKey
                        recvVideoKey = keys.recvKey
                        sendFrameSeq.set(0L)
                        recvFrameSeq.set(0L)
                    } catch (sec: SecurityException) {
                        try { conn.close() } catch (_: Exception) {}
                        Log.e(TAG, "Video peer authentication failed: ${sec.message}")
                        CallSessionBus.notifyEnded("Video peer authentication failed")
                        endCallInternal(notifyBus = false, reason = "Peer authentication failed")
                        return@launch
                    }
                    Log.d(TAG, "Connected to GO video at $hostAddress:${Constants.VIDEO_PORT} (authenticated E2EE stream ready)")
                    CallSessionBus.notifyConnected()
                    startVideoSender()
                    openCameraAndStream()
                    startVideoPlayback()
                    return@launch
                } catch (e: Exception) {
                    Log.d(TAG, "Video connect attempt ${attempt + 1} failed: ${e.message}")
                    delay(1_000)
                }
            }
            Log.e(TAG, "Could not reach peer video port after 30 attempts")
            if (!terminating.get()) {
                CallSessionBus.notifyEnded("Peer did not answer")
            }
            endCallInternal(notifyBus = false, reason = "Connect timeout")
        }
    }

    // ======================== Camera Setup ========================

    private fun setupCameraThread() {
        val thread = HandlerThread("CameraThread").apply { start() }
        cameraThread = thread
        cameraHandler = Handler(thread.looper)
    }

    private fun quitCameraThread() {
        try {
            cameraThread?.quitSafely()
        } catch (_: Exception) {}
        cameraThread = null
        cameraHandler = null
    }

    private fun getCameraId(front: Boolean): String? {
        try {
            val mgr = cameraManager ?: return null
            val ids = mgr.cameraIdList
            for (id in ids) {
                val chars = mgr.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (front && facing == CameraCharacteristics.LENS_FACING_FRONT) return id
                if (!front && facing == CameraCharacteristics.LENS_FACING_BACK) return id
            }
            return ids.firstOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "Error finding camera: ${e.message}")
            return null
        }
    }

    private fun openCameraAndStream() {
        if (!isRunning || terminating.get()) return
        val cameraId = getCameraId(isUsingFrontCamera) ?: run {
            Log.e(TAG, "No camera available")
            isSwitchingCamera.set(false)
            return
        }
        activeCameraId = cameraId

        try {
            cameraManager?.openCamera(cameraId, cameraStateCallback, cameraHandler)
        } catch (se: SecurityException) {
            Log.e(TAG, "Camera permission not granted: ${se.message}")
            isSwitchingCamera.set(false)
            CallSessionBus.notifyEnded("Camera permission denied")
            endCallInternal(notifyBus = false, reason = "Camera permission denied")
        } catch (e: Exception) {
            Log.e(TAG, "Error opening camera: ${e.message}")
            isSwitchingCamera.set(false)
        }
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            if (!isRunning || terminating.get()) {
                camera.close()
                isSwitchingCamera.set(false)
                return
            }
            synchronized(cameraLock) {
                cameraDevice = camera
            }
            isSwitchingCamera.set(false)
            Log.d(TAG, "Camera opened ($activeCameraId)")
            startCaptureSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            synchronized(cameraLock) {
                if (cameraDevice === camera) cameraDevice = null
            }
            camera.close()
            isSwitchingCamera.set(false)
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(TAG, "Camera error: $error")
            synchronized(cameraLock) {
                if (cameraDevice === camera) cameraDevice = null
            }
            camera.close()
            isSwitchingCamera.set(false)
        }
    }

    private fun reconfigureSessionForPreviewSurface() {
        val handler = cameraHandler ?: return
        handler.post {
            synchronized(cameraLock) {
                if (cameraDevice != null && isRunning && !terminating.get()) {
                    startCaptureSessionLocked()
                }
            }
        }
    }

    private fun startCaptureSession() {
        val handler = cameraHandler ?: return
        handler.post {
            synchronized(cameraLock) {
                startCaptureSessionLocked()
            }
        }
    }

    private fun startCaptureSessionLocked() {
        val device = cameraDevice ?: return
        if (!isRunning || terminating.get()) return

        // Tear down any prior capture session and reader cleanly before reconfiguring
        try { captureSession?.stopRepeating() } catch (_: Exception) {}
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null

        val reader = ImageReader.newInstance(
            Constants.VIDEO_WIDTH,
            Constants.VIDEO_HEIGHT,
            ImageFormat.JPEG,
            2
        )
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = System.currentTimeMillis()
                if (now - lastFrameSentAtMs >= MIN_FRAME_INTERVAL_MS && isRunning && !terminating.get()) {
                    lastFrameSentAtMs = now
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    outgoingFrameChannel.trySend(bytes)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Frame read error: ${e.message}")
            } finally {
                image.close()
            }
        }, cameraHandler)
        imageReader = reader

        val surfaces = mutableListOf(reader.surface)
        val preview = localPreviewSurface
        if (preview != null && preview.isValid) {
            surfaces.add(preview)
        }

        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            surfaces.forEach { builder.addTarget(it) }
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            computeJpegOrientation()?.let { orientation ->
                builder.set(CaptureRequest.JPEG_ORIENTATION, orientation)
            }

            @Suppress("DEPRECATION")
            device.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (!isRunning || terminating.get() || cameraDevice !== device) {
                            try { session.close() } catch (_: Exception) {}
                            return
                        }
                        synchronized(cameraLock) {
                            captureSession = session
                        }
                        try {
                            session.setRepeatingRequest(builder.build(), null, cameraHandler)
                            Log.d(TAG, "Video capture started (surfaces=${surfaces.size})")
                        } catch (e: Exception) {
                            Log.e(TAG, "Repeating request error: ${e.message}")
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Capture session config failed")
                    }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Capture session error: ${e.message}")
        }
    }

    private fun computeJpegOrientation(): Int? {
        return try {
            val id = activeCameraId ?: return null
            val chars = cameraManager?.getCameraCharacteristics(id) ?: return null
            val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            // VideoCallActivity is locked to portrait (display rotation 0°),
            // so sensorOrientation aligns the JPEG output upright for remote peers.
            if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                (sensorOrientation + 360) % 360
            } else {
                sensorOrientation % 360
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Switch between front and back camera safely on the camera thread. */
    private fun switchCameraInternal() {
        if (!isRunning || terminating.get()) return
        if (!isSwitchingCamera.compareAndSet(false, true)) return

        val handler = cameraHandler
        if (handler == null) {
            isSwitchingCamera.set(false)
            return
        }
        handler.post {
            stopStreaming()
            closeCamera()
            isUsingFrontCamera = !isUsingFrontCamera
            openCameraAndStream()
        }
    }

    // ======================== Send Video (Single Conflated Writer) ========================

    private fun startVideoSender() {
        sendJob?.cancel()
        sendJob = scope.launch {
            val out = outputStream ?: return@launch
            val key = sendVideoKey ?: return@launch
            val dos = DataOutputStream(out)
            try {
                for (frame in outgoingFrameChannel) {
                    if (!isRunning || terminating.get() || !isActive) break
                    val seq = sendFrameSeq.incrementAndGet()
                    val encrypted = CryptoManager.encryptVideoFrame(key, seq, frame)
                    dos.writeInt(encrypted.size)
                    dos.write(encrypted)
                    dos.flush()
                }
            } catch (e: Exception) {
                if (scope.isActive && !terminating.get()) {
                    Log.w(TAG, "Video send stopped: ${e.message}")
                }
            }
        }
    }

    // ======================== Receive Video ========================

    private fun startVideoPlayback() {
        playJob?.cancel()
        playJob = scope.launch {
            try {
                val key = recvVideoKey ?: return@launch
                val dis = DataInputStream(inputStream ?: return@launch)
                val maxEncryptedSize = Constants.VIDEO_BUFFER_SIZE + 64
                while (isActive && !terminating.get()) {
                    val frameSize = dis.readInt()
                    if (frameSize <= 28 || frameSize > maxEncryptedSize) {
                        Log.w(TAG, "Invalid encrypted frame size: $frameSize (max=$maxEncryptedSize)")
                        break
                    }
                    val packet = ByteArray(frameSize)
                    dis.readFully(packet)
                    val seq = recvFrameSeq.incrementAndGet()
                    val jpegBytes = CryptoManager.decryptVideoFrame(key, seq, packet)
                    if (jpegBytes == null) {
                        Log.w(TAG, "Failed to authenticate/decrypt video frame seq=$seq")
                        break
                    }
                    onRemoteVideoFrame?.invoke(jpegBytes)
                }
            } catch (_: EOFException) {
                Log.d(TAG, "Video stream ended by peer")
                if (!terminating.get()) {
                    CallSessionBus.notifyEnded("Video call ended")
                }
            } catch (e: Exception) {
                if (scope.isActive && !terminating.get()) {
                    Log.e(TAG, "Video playback error: ${e.message}")
                    CallSessionBus.notifyEnded("Video call ended")
                }
            } finally {
                if (!terminating.get()) {
                    endCallInternal(notifyBus = false, reason = "Playback ended")
                }
            }
        }
    }

    // ======================== Cleanup ========================

    private fun stopStreaming() {
        playJob?.cancel()
        synchronized(cameraLock) {
            try { captureSession?.stopRepeating() } catch (_: Exception) {}
            try { captureSession?.close() } catch (_: Exception) {}
            captureSession = null
        }
    }

    private fun closeCamera() {
        synchronized(cameraLock) {
            try { imageReader?.close() } catch (_: Exception) {}
            try { cameraDevice?.close() } catch (_: Exception) {}
            imageReader = null
            cameraDevice = null
        }
    }

    private fun closeSockets() {
        sendVideoKey = null
        recvVideoKey = null
        try { outputStream?.close() } catch (_: Exception) {}
        try { inputStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        outputStream = null
        inputStream = null
        socket = null
        serverSocket = null
    }

    private fun endCallInternal(notifyBus: Boolean, reason: String) {
        if (!terminating.compareAndSet(false, true)) return
        if (notifyBus) {
            CallSessionBus.notifyEnded(reason)
        }
        connectJob?.cancel()
        sendJob?.cancel()
        outgoingFrameChannel.close()
        stopStreaming()
        closeCamera()
        closeSockets()
        stopSelf()
    }

    // ======================== Notification ========================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Video Call",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Video call in progress"
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LanVox")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }
}
